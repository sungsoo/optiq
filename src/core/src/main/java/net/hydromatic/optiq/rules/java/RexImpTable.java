/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hydromatic.optiq.rules.java;

import net.hydromatic.linq4j.Ord;
import net.hydromatic.linq4j.expressions.*;

import net.hydromatic.optiq.*;
import net.hydromatic.optiq.Function;
import net.hydromatic.optiq.impl.AggregateFunctionImpl;
import net.hydromatic.optiq.runtime.SqlFunctions;

import org.eigenbase.rel.Aggregation;
import org.eigenbase.reltype.*;
import org.eigenbase.rex.*;
import org.eigenbase.sql.*;
import org.eigenbase.sql.fun.SqlStdOperatorTable;
import org.eigenbase.sql.fun.SqlTrimFunction;
import org.eigenbase.sql.type.SqlTypeName;
import org.eigenbase.sql.validate.SqlUserDefinedAggFunction;
import org.eigenbase.sql.validate.SqlUserDefinedFunction;
import org.eigenbase.util.Util;
import org.eigenbase.util14.DateTimeUtil;

import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.util.*;

import static net.hydromatic.linq4j.expressions.ExpressionType.*;

import static net.hydromatic.optiq.DataContext.ROOT;

import static org.eigenbase.sql.fun.SqlStdOperatorTable.*;

/**
 * Contains implementations of Rex operators as Java code.
 */
public class RexImpTable {
  public static final ConstantExpression NULL_EXPR =
      Expressions.constant(null);
  public static final ConstantExpression FALSE_EXPR =
      Expressions.constant(false);
  public static final ConstantExpression TRUE_EXPR =
      Expressions.constant(true);
  public static final MemberExpression BOXED_FALSE_EXPR =
      Expressions.field(null, Boolean.class, "FALSE");
  public static final MemberExpression BOXED_TRUE_EXPR =
      Expressions.field(null, Boolean.class, "TRUE");

  private final Map<SqlOperator, CallImplementor> map =
      new HashMap<SqlOperator, CallImplementor>();
  private final Map<Aggregation, Supplier<? extends AggImplementor>> aggMap =
      new HashMap<Aggregation, Supplier<? extends AggImplementor>>();
  private final Map<Aggregation, Supplier<? extends WinAggImplementor>>
  winAggMap =
      new HashMap<Aggregation, Supplier<? extends WinAggImplementor>>();

  RexImpTable() {
    defineMethod(UPPER, BuiltinMethod.UPPER.method, NullPolicy.STRICT);
    defineMethod(LOWER, BuiltinMethod.LOWER.method, NullPolicy.STRICT);
    defineMethod(INITCAP,  BuiltinMethod.INITCAP.method, NullPolicy.STRICT);
    defineMethod(SUBSTRING, BuiltinMethod.SUBSTRING.method, NullPolicy.STRICT);
    defineMethod(CHARACTER_LENGTH, BuiltinMethod.CHAR_LENGTH.method,
        NullPolicy.STRICT);
    defineMethod(CHAR_LENGTH, BuiltinMethod.CHAR_LENGTH.method,
        NullPolicy.STRICT);
    defineMethod(CONCAT, BuiltinMethod.STRING_CONCAT.method,
        NullPolicy.STRICT);
    defineMethod(OVERLAY, BuiltinMethod.OVERLAY.method, NullPolicy.STRICT);
    defineMethod(POSITION, BuiltinMethod.POSITION.method, NullPolicy.STRICT);

    final TrimImplementor trimImplementor = new TrimImplementor();
    defineImplementor(TRIM, NullPolicy.STRICT, trimImplementor, false);

    // logical
    defineBinary(AND, AndAlso, NullPolicy.AND, null);
    defineBinary(OR, OrElse, NullPolicy.OR, null);
    defineUnary(NOT, Not, NullPolicy.NOT);

    // comparisons
    defineBinary(LESS_THAN, LessThan, NullPolicy.STRICT, "lt");
    defineBinary(LESS_THAN_OR_EQUAL, LessThanOrEqual, NullPolicy.STRICT, "le");
    defineBinary(GREATER_THAN, GreaterThan, NullPolicy.STRICT, "gt");
    defineBinary(GREATER_THAN_OR_EQUAL, GreaterThanOrEqual, NullPolicy.STRICT,
        "ge");
    defineBinary(EQUALS, Equal, NullPolicy.STRICT, "eq");
    defineBinary(NOT_EQUALS, NotEqual, NullPolicy.STRICT, "ne");

    // arithmetic
    defineBinary(PLUS, Add, NullPolicy.STRICT, "plus");
    defineBinary(MINUS, Subtract, NullPolicy.STRICT, "minus");
    defineBinary(MULTIPLY, Multiply, NullPolicy.STRICT, "multiply");
    defineBinary(DIVIDE, Divide, NullPolicy.STRICT, "divide");
    defineBinary(DIVIDE_INTEGER, Divide, NullPolicy.STRICT, "divide");
    defineUnary(UNARY_MINUS, Negate, NullPolicy.STRICT);
    defineUnary(UNARY_PLUS, UnaryPlus, NullPolicy.STRICT);

    defineMethod(MOD, "mod", NullPolicy.STRICT);
    defineMethod(EXP, "exp", NullPolicy.STRICT);
    defineMethod(POWER, "power", NullPolicy.STRICT);
    defineMethod(LN, "ln", NullPolicy.STRICT);
    defineMethod(LOG10, "log10", NullPolicy.STRICT);
    defineMethod(ABS, "abs", NullPolicy.STRICT);
    defineMethod(CEIL, "ceil", NullPolicy.STRICT);
    defineMethod(FLOOR, "floor", NullPolicy.STRICT);

    // datetime
    defineImplementor(DATETIME_PLUS, NullPolicy.STRICT,
        new DatetimeArithmeticImplementor(), false);
    defineMethod(EXTRACT_DATE, BuiltinMethod.UNIX_DATE_EXTRACT.method,
        NullPolicy.STRICT);

    map.put(IS_NULL, new IsXxxImplementor(null, false));
    map.put(IS_NOT_NULL, new IsXxxImplementor(null, true));
    map.put(IS_TRUE, new IsXxxImplementor(true, false));
    map.put(IS_NOT_TRUE, new IsXxxImplementor(true, true));
    map.put(IS_FALSE, new IsXxxImplementor(false, false));
    map.put(IS_NOT_FALSE, new IsXxxImplementor(false, true));

    // LIKE and SIMILAR
    final MethodImplementor likeImplementor =
        new MethodImplementor(BuiltinMethod.LIKE.method);
    defineImplementor(LIKE, NullPolicy.STRICT, likeImplementor, false);
    defineImplementor(NOT_LIKE, NullPolicy.STRICT,
        NotImplementor.of(likeImplementor), false);
    final MethodImplementor similarImplementor =
        new MethodImplementor(BuiltinMethod.SIMILAR.method);
    defineImplementor(SIMILAR_TO, NullPolicy.STRICT, similarImplementor, false);
    defineImplementor(NOT_SIMILAR_TO, NullPolicy.STRICT,
        NotImplementor.of(similarImplementor), false);

    // Multisets & arrays
    defineMethod(CARDINALITY, BuiltinMethod.COLLECTION_SIZE.method,
        NullPolicy.STRICT);
    defineMethod(SLICE, BuiltinMethod.SLICE.method, NullPolicy.NONE);
    defineMethod(ELEMENT, BuiltinMethod.ELEMENT.method, NullPolicy.STRICT);

    map.put(CASE, new CaseImplementor());

    map.put(CAST, new CastOptimizedImplementor());

    defineImplementor(REINTERPRET, NullPolicy.STRICT,
        new ReinterpretImplementor(), false);

    final CallImplementor value = new ValueConstructorImplementor();
    map.put(MAP_VALUE_CONSTRUCTOR, value);
    map.put(ARRAY_VALUE_CONSTRUCTOR, value);
    map.put(ITEM, new ItemImplementor());

    // System functions
    final SystemFunctionImplementor systemFunctionImplementor =
        new SystemFunctionImplementor();
    map.put(USER, systemFunctionImplementor);
    map.put(CURRENT_USER, systemFunctionImplementor);
    map.put(SESSION_USER, systemFunctionImplementor);
    map.put(SYSTEM_USER, systemFunctionImplementor);
    map.put(CURRENT_PATH, systemFunctionImplementor);
    map.put(CURRENT_ROLE, systemFunctionImplementor);

    // Current time functions
    map.put(CURRENT_TIME, systemFunctionImplementor);
    map.put(CURRENT_TIMESTAMP, systemFunctionImplementor);
    map.put(CURRENT_DATE, systemFunctionImplementor);
    map.put(LOCALTIME, systemFunctionImplementor);
    map.put(LOCALTIMESTAMP, systemFunctionImplementor);

    aggMap.put(COUNT, constructorSupplier(CountImplementor.class));
    aggMap.put(SUM0, constructorSupplier(SumImplementor.class));
    aggMap.put(SUM, constructorSupplier(SumImplementor.class));
    Supplier<MinMaxImplementor> minMax =
        constructorSupplier(MinMaxImplementor.class);
    aggMap.put(MIN, minMax);
    aggMap.put(MAX, minMax);
    aggMap.put(SINGLE_VALUE, constructorSupplier(SingleValueImplementor.class));
    winAggMap.put(RANK, constructorSupplier(RankImplementor.class));
    winAggMap.put(DENSE_RANK, constructorSupplier(DenseRankImplementor.class));
    winAggMap.put(ROW_NUMBER, constructorSupplier(RowNumberImplementor.class));
    winAggMap.put(FIRST_VALUE,
        constructorSupplier(FirstValueImplementor.class));
    winAggMap.put(LAST_VALUE, constructorSupplier(LastValueImplementor.class));
    winAggMap.put(LEAD, constructorSupplier(LeadImplementor.class));
    winAggMap.put(LAG, constructorSupplier(LagImplementor.class));
    winAggMap.put(NTILE, constructorSupplier(NtileImplementor.class));
    winAggMap.put(COUNT, constructorSupplier(CountWinImplementor.class));
  }

  private <T> Supplier<T> constructorSupplier(Class<T> klass) {
    final Constructor<T> constructor;
    try {
      constructor = klass.getDeclaredConstructor();
    } catch (NoSuchMethodException e) {
      throw new IllegalArgumentException(
          klass + " should implement zero arguments constructor");
    }
    return new Supplier<T>() {
      public T get() {
        try {
          return constructor.newInstance();
        } catch (InstantiationException e) {
          throw new IllegalStateException(
              "Unable to instantiate aggregate implementor " + constructor, e);
        } catch (IllegalAccessException e) {
          throw new IllegalStateException(
              "Error while creating aggregate implementor " + constructor, e);
        } catch (InvocationTargetException e) {
          throw new IllegalStateException(
              "Error while creating aggregate implementor " + constructor, e);
        }
      }
    };
  }

  private void defineImplementor(
      SqlOperator operator,
      NullPolicy nullPolicy,
      NotNullImplementor implementor,
      boolean harmonize) {
    CallImplementor callImplementor =
        createImplementor(implementor, nullPolicy, harmonize);
    map.put(operator, callImplementor);
  }

  private static RexCall call2(
      boolean harmonize,
      RexToLixTranslator translator,
      RexCall call) {
    if (!harmonize) {
      return call;
    }
    final List<RexNode> operands2 =
        harmonize(translator, call.getOperands());
    if (operands2.equals(call.getOperands())) {
      return call;
    }
    return call.clone(call.getType(), operands2);
  }

  public static CallImplementor createImplementor(
      final NotNullImplementor implementor,
      final NullPolicy nullPolicy,
      final boolean harmonize) {
    switch (nullPolicy) {
    case ANY:
    case STRICT:
      return new CallImplementor() {
        public Expression implement(
            RexToLixTranslator translator, RexCall call, NullAs nullAs) {
          return implementNullSemantics0(
              translator, call, nullAs, nullPolicy, harmonize,
              implementor);
        }
      };
    case AND:
/* TODO:
            if (nullAs == NullAs.FALSE) {
                nullPolicy2 = NullPolicy.ANY;
            }
*/
      // If any of the arguments are false, result is false;
      // else if any arguments are null, result is null;
      // else true.
      //
      // b0 == null ? (b1 == null || b1 ? null : Boolean.FALSE)
      //   : b0 ? b1
      //   : Boolean.FALSE;
      return new CallImplementor() {
        public Expression implement(
            RexToLixTranslator translator, RexCall call, NullAs nullAs) {
          final RexCall call2 = call2(false, translator, call);
          final NullAs nullAs2 = nullAs == NullAs.TRUE ? NullAs.NULL : nullAs;
          final List<Expression> expressions =
              translator.translateList(call2.getOperands(), nullAs2);
          switch (nullAs) {
          case NOT_POSSIBLE:
          case TRUE:
            return Expressions.foldAnd(expressions);
          }
          return Expressions.foldAnd(Lists.transform(expressions,
              new com.google.common.base.Function<Expression, Expression>() {
                public Expression apply(Expression e) {
                  return nullAs2.handle(e);
                }
              }));
        }
      };
    case OR:
      // If any of the arguments are true, result is true;
      // else if any arguments are null, result is null;
      // else false.
      //
      // b0 == null ? (b1 == null || !b1 ? null : Boolean.TRUE)
      //   : !b0 ? b1
      //   : Boolean.TRUE;
      return new CallImplementor() {
        public Expression implement(
            RexToLixTranslator translator, RexCall call, NullAs nullAs) {
          final RexCall call2 = call2(harmonize, translator, call);
          final NullAs nullAs2 = nullAs == NullAs.TRUE ? NullAs.NULL : nullAs;
          final List<Expression> expressions =
              translator.translateList(call2.getOperands(), nullAs2);
          switch (nullAs) {
          case NOT_POSSIBLE:
          case FALSE:
            return Expressions.foldOr(expressions);
          }
          final Expression t0 = expressions.get(0);
          final Expression t1 = expressions.get(1);
          if (!nullable(call2, 0) && !nullable(call2, 1)) {
            return Expressions.orElse(t0, t1);
          }
          return optimize(
              Expressions.condition(
                  Expressions.equal(t0, NULL_EXPR),
                  Expressions.condition(
                      Expressions.orElse(
                          Expressions.equal(t1, NULL_EXPR),
                          Expressions.not(t1)),
                      NULL_EXPR,
                      BOXED_TRUE_EXPR),
                  Expressions.condition(
                      Expressions.not(t0),
                      t1,
                      BOXED_TRUE_EXPR)));
        }
      };
    case NOT:
      // If any of the arguments are false, result is true;
      // else if any arguments are null, result is null;
      // else false.
      return new CallImplementor() {
        public Expression implement(
            RexToLixTranslator translator, RexCall call, NullAs nullAs) {
          NullAs nullAs2;
          switch (nullAs) {
          case FALSE:
            nullAs2 = NullAs.TRUE;
            break;
          case TRUE:
            nullAs2 = NullAs.FALSE;
            break;
          default:
            nullAs2 = nullAs;
          }
          return implementNullSemantics0(
              translator, call, nullAs2, nullPolicy, harmonize, implementor);
        }
      };
    case NONE:
      return new CallImplementor() {
        public Expression implement(
            RexToLixTranslator translator, RexCall call, NullAs nullAs) {
          final RexCall call2 = call2(false, translator, call);
          return implementCall(
              translator, call2, implementor, nullAs);
        }
      };
    default:
      throw new AssertionError(nullPolicy);
    }
  }

  private void defineMethod(
      SqlOperator operator, String functionName, NullPolicy nullPolicy) {
    defineImplementor(
        operator,
        nullPolicy,
        new MethodNameImplementor(functionName),
        false);
  }

  private void defineMethod(
      SqlOperator operator, Method method, NullPolicy nullPolicy) {
    defineImplementor(
        operator, nullPolicy, new MethodImplementor(method), false);
  }

  private void defineUnary(
      SqlOperator operator, ExpressionType expressionType,
      NullPolicy nullPolicy) {
    defineImplementor(
        operator,
        nullPolicy,
        new UnaryImplementor(expressionType), false);
  }

  private void defineBinary(
      SqlOperator operator,
      ExpressionType expressionType,
      NullPolicy nullPolicy,
      String backupMethodName) {
    defineImplementor(
        operator,
        nullPolicy,
        new BinaryImplementor(expressionType, backupMethodName),
        true);
  }

  public static final RexImpTable INSTANCE = new RexImpTable();

  public CallImplementor get(final SqlOperator operator) {
    if (operator instanceof SqlUserDefinedFunction) {
      Function udf =
        ((SqlUserDefinedFunction) operator).getFunction();
      if (!(udf instanceof ImplementableFunction)) {
        throw new IllegalStateException(
            "User defined function " + operator + " must implement "
            + "ImplementableFunction");
      }
      return ((ImplementableFunction) udf).getImplementor();
    }
    return map.get(operator);
  }

  public AggImplementor get(final Aggregation aggregation,
      boolean forWindowAggregate) {
    if (aggregation instanceof SqlUserDefinedAggFunction) {
      final SqlUserDefinedAggFunction udaf =
          (SqlUserDefinedAggFunction) aggregation;
      if (!(udaf.function instanceof ImplementableAggFunction)) {
        throw new IllegalStateException(
            "User defined aggregation " + aggregation + " must implement "
                + "ImplementableAggFunction");
      }
      return ((ImplementableAggFunction) udaf.function)
          .getImplementor(forWindowAggregate);
    }
    if (forWindowAggregate) {
      Supplier<? extends WinAggImplementor> winAgg =
          winAggMap.get(aggregation);
      if (winAgg != null) {
        return winAgg.get();
      }
      // Regular aggregates can be used in window context as well
    }

    Supplier<? extends AggImplementor> aggSupplier = aggMap.get(aggregation);
    if (aggSupplier == null) {
      return null;
    }

    return aggSupplier.get();
  }

  static Expression maybeNegate(boolean negate, Expression expression) {
    if (!negate) {
      return expression;
    } else {
      return Expressions.not(expression);
    }
  }

  static Expression optimize(Expression expression) {
    return expression.accept(new OptimizeVisitor());
  }

  static Expression optimize2(Expression operand, Expression expression) {
    if (Primitive.is(operand.getType())) {
      // Primitive values cannot be null
      return optimize(expression);
    } else {
      return optimize(
          Expressions.condition(
              Expressions.equal(
                  operand,
                  NULL_EXPR),
              NULL_EXPR,
              expression));
    }
  }

  private static boolean nullable(RexCall call, int i) {
    return call.getOperands().get(i).getType().isNullable();
  }

  /** Ensures that operands have identical type. */
  private static List<RexNode> harmonize(
      final RexToLixTranslator translator, final List<RexNode> operands) {
    int nullCount = 0;
    final List<RelDataType> types = new ArrayList<RelDataType>();
    final RelDataTypeFactory typeFactory =
        translator.builder.getTypeFactory();
    for (RexNode operand : operands) {
      RelDataType type = operand.getType();
      type = toSql(typeFactory, type);
      if (translator.isNullable(operand)) {
        ++nullCount;
      } else {
        type = typeFactory.createTypeWithNullability(type, false);
      }
      types.add(type);
    }
    if (allSame(types)) {
      // Operands have the same nullability and type. Return them
      // unchanged.
      return operands;
    }
    final RelDataType type = typeFactory.leastRestrictive(types);
    if (type == null) {
      // There is no common type. Presumably this is a binary operator with
      // asymmetric arguments (e.g. interval / integer) which is not intended
      // to be harmonized.
      return operands;
    }
    assert (nullCount > 0) == type.isNullable();
    final List<RexNode> list = new ArrayList<RexNode>();
    for (RexNode operand : operands) {
      list.add(
          translator.builder.ensureType(type, operand, false));
    }
    return list;
  }

  private static RelDataType toSql(RelDataTypeFactory typeFactory,
      RelDataType type) {
    if (type instanceof RelDataTypeFactoryImpl.JavaType) {
      final SqlTypeName typeName = type.getSqlTypeName();
      if (typeName != null && typeName != SqlTypeName.OTHER) {
        return typeFactory.createTypeWithNullability(
            typeFactory.createSqlType(typeName),
            type.isNullable());
      }
    }
    return type;
  }

  private static <E> boolean allSame(List<E> list) {
    E prev = null;
    for (E e : list) {
      if (prev != null && !prev.equals(e)) {
        return false;
      }
      prev = e;
    }
    return true;
  }

  private static Expression implementNullSemantics0(
      RexToLixTranslator translator,
      RexCall call,
      NullAs nullAs,
      NullPolicy nullPolicy,
      boolean harmonize,
      NotNullImplementor implementor) {
    switch (nullAs) {
    case IS_NOT_NULL:
      // If "f" is strict, then "f(a0, a1) IS NOT NULL" is
      // equivalent to "a0 IS NOT NULL AND a1 IS NOT NULL".
      if (nullPolicy == NullPolicy.STRICT) {
        return Expressions.foldAnd(
            translator.translateList(
                call.getOperands(), nullAs));
      }
      break;
    case IS_NULL:
      // If "f" is strict, then "f(a0, a1) IS NULL" is
      // equivalent to "a0 IS NULL OR a1 IS NULL".
      if (nullPolicy == NullPolicy.STRICT) {
        return Expressions.foldOr(
            translator.translateList(
                call.getOperands(), nullAs));
      }
      break;
    }
    final RexCall call2 = call2(harmonize, translator, call);
    try {
      return implementNullSemantics(
          translator, call2, nullAs, nullPolicy, implementor);
    } catch (RexToLixTranslator.AlwaysNull e) {
      switch (nullAs) {
      case NOT_POSSIBLE:
        throw e;
      case FALSE:
        return FALSE_EXPR;
      case TRUE:
        return TRUE_EXPR;
      default:
        return NULL_EXPR;
      }
    }
  }

  private static Expression implementNullSemantics(
      RexToLixTranslator translator,
      RexCall call,
      NullAs nullAs,
      NullPolicy nullPolicy, NotNullImplementor implementor) {
    final List<Expression> list = new ArrayList<Expression>();
    switch (nullAs) {
    case NULL:
      // v0 == null || v1 == null ? null : f(v0, v1)
      for (Ord<RexNode> operand : Ord.zip(call.getOperands())) {
        if (translator.isNullable(operand.e)) {
          list.add(
              translator.translate(
                  operand.e, NullAs.IS_NULL));
          translator = translator.setNullable(operand.e, false);
        }
      }
      final Expression box =
          Expressions.box(
              implementCall(translator, call, implementor, nullAs));
      return optimize(
          Expressions.condition(
              Expressions.foldOr(list),
              Types.castIfNecessary(box.getType(), NULL_EXPR),
              box));
    case FALSE:
      // v0 != null && v1 != null && f(v0, v1)
      for (Ord<RexNode> operand : Ord.zip(call.getOperands())) {
        if (translator.isNullable(operand.e)) {
          list.add(
              translator.translate(
                  operand.e, NullAs.IS_NOT_NULL));
          translator = translator.setNullable(operand.e, false);
        }
      }
      list.add(implementCall(translator, call, implementor, nullAs));
      return Expressions.foldAnd(list);
    case NOT_POSSIBLE:
      // Need to transmit to the implementor the fact that call cannot
      // return null. In particular, it should return a primitive (e.g.
      // int) rather than a box type (Integer).
      // The cases with setNullable above might not help since the same
      // RexNode can be referred via multiple ways: RexNode itself, RexLocalRef,
      // and may be others.
      Map<RexNode, Boolean> nullable = new HashMap<RexNode, Boolean>();
      if (nullPolicy == NullPolicy.STRICT) {
        // The arguments should be not nullable if STRICT operator is computed
        // in nulls NOT_POSSIBLE mode
        for (RexNode arg : call.getOperands()) {
          if (translator.isNullable(arg) && !nullable.containsKey(arg)) {
            nullable.put(arg, false);
          }
        }
      }
      nullable.put(call, false);
      translator = translator.setNullable(nullable);
      // fall through
    default:
      return implementCall(translator, call, implementor, nullAs);
    }
  }

  private static Expression implementCall(
      RexToLixTranslator translator,
      RexCall call,
      NotNullImplementor implementor,
      NullAs nullAs) {
    final List<Expression> translatedOperands =
        translator.translateList(call.getOperands());
    switch (nullAs) {
    case NOT_POSSIBLE:
    case NULL:
      for (Expression translatedOperand : translatedOperands) {
        if (Expressions.isConstantNull(translatedOperand)) {
          return NULL_EXPR;
        }
      }
    }
    Expression result;
    result = implementor.implement(translator, call, translatedOperands);
    return nullAs.handle(result);
  }

  /** Strategy what an operator should return if one of its
   * arguments is null. */
  public enum NullAs {
    /** The most common policy among the SQL built-in operators. If
     * one of the arguments is null, returns null. */
    NULL,

    /** If one of the arguments is null, the function returns
     * false. Example: {@code IS NOT NULL}. */
    FALSE,

    /** If one of the arguments is null, the function returns
     * true. Example: {@code IS NULL}. */
    TRUE,

    /** It is not possible for any of the arguments to be null.  If
     * the argument type is nullable, the enclosing code will already
     * have performed a not-null check. This may allow the operator
     * implementor to generate a more efficient implementation, for
     * example, by avoiding boxing or unboxing. */
    NOT_POSSIBLE,

    /** Return false if result is not null, true if result is null. */
    IS_NULL,

    /** Return true if result is not null, false if result is null. */
    IS_NOT_NULL;

    public static NullAs of(boolean nullable) {
      return nullable ? NULL : NOT_POSSIBLE;
    }

    /** Adapts an expression with "normal" result to one that adheres to
     * this particular policy. */
    public Expression handle(Expression x) {
      switch (Primitive.flavor(x.getType())) {
      case PRIMITIVE:
        // Expression cannot be null. We can skip any runtime checks.
        switch (this) {
        case NULL:
        case NOT_POSSIBLE:
        case FALSE:
        case TRUE:
          return x;
        case IS_NULL:
          return FALSE_EXPR;
        case IS_NOT_NULL:
          return TRUE_EXPR;
        default:
          throw new AssertionError();
        }
      case BOX:
        switch (this) {
        case NOT_POSSIBLE:
          return RexToLixTranslator.convert(
              x,
              Primitive.ofBox(x.getType()).primitiveClass);
        }
        // fall through
      }
      switch (this) {
      case NULL:
      case NOT_POSSIBLE:
        return x;
      case FALSE:
        return Expressions.call(
            BuiltinMethod.IS_TRUE.method,
            x);
      case TRUE:
        return Expressions.call(
            BuiltinMethod.IS_NOT_FALSE.method,
            x);
      case IS_NULL:
        return Expressions.equal(x, NULL_EXPR);
      case IS_NOT_NULL:
        return Expressions.notEqual(x, NULL_EXPR);
      default:
        throw new AssertionError();
      }
    }
  }

  static Expression getDefaultValue(Type type) {
    if (Primitive.is(type)) {
      Primitive p = Primitive.of(type);
      return Expressions.constant(p.defaultValue, type);
    }
    return Expressions.constant(null, type);
  }

  static class CountImplementor extends StrictAggImplementor {
    @Override
    public void implementNotNullAdd(AggContext info, AggAddContext add) {
      add.currentBlock().add(Expressions.statement(
          Expressions.postIncrementAssign(add.accumulator().get(0))));
    }
  }

  static class CountWinImplementor extends StrictWinAggImplementor {
    boolean justFrameRowCount;

    @Override
    public List<Type> getNotNullState(WinAggContext info) {
      boolean hasNullable = false;
      for (RelDataType type : info.parameterRelTypes()) {
        if (type.isNullable()) {
          hasNullable = true;
          break;
        }
      }
      if (!hasNullable) {
        justFrameRowCount = true;
        return Collections.emptyList();
      }
      return super.getNotNullState(info);
    }

    @Override
    public void implementNotNullAdd(WinAggContext info, WinAggAddContext add) {
      if (justFrameRowCount) {
        return;
      }
      add.currentBlock().add(Expressions.statement(
          Expressions.postIncrementAssign(add.accumulator().get(0))));
    }

    @Override
    protected Expression implementNotNullResult(WinAggContext info,
        WinAggResultContext result) {
      if (justFrameRowCount) {
        return result.getFrameRowCount();
      }
      return super.implementNotNullResult(info, result);
    }
  }

  static class SumImplementor extends StrictAggImplementor {
    @Override
    protected void implementNotNullReset(AggContext info,
        AggResetContext reset) {
      Expression start = info.returnType() == BigDecimal.class
          ? Expressions.constant(BigDecimal.ZERO)
          : Expressions.constant(0);

      reset.currentBlock().add(Expressions.statement(Expressions.assign(
          reset.accumulator().get(0), start)));
    }

    @Override
    public void implementNotNullAdd(AggContext info, AggAddContext add) {
      Expression acc = add.accumulator().get(0);
      Expression next;
      if (info.returnType() == BigDecimal.class) {
        next = Expressions.call(acc, "add", add.arguments().get(0));
      } else {
        next = Expressions.add(acc,
            Types.castIfNecessary(acc.type, add.arguments().get(0)));
      }
      accAdvance(add, acc, next);
    }

    @Override
    public Expression implementNotNullResult(AggContext info,
        AggResultContext result) {
      return super.implementNotNullResult(info, result);
    }
  }

  static class MinMaxImplementor extends StrictAggImplementor {
    @Override
    protected void implementNotNullReset(AggContext info,
        AggResetContext reset) {
      Expression acc = reset.accumulator().get(0);
      Primitive p = Primitive.of(acc.getType());
      boolean isMin = MIN == info.aggregation();
      Object inf = p == null ? null : (isMin ? p.max : p.min);
      reset.currentBlock().add(Expressions.statement(Expressions.assign(
          acc, Expressions.constant(inf, acc.getType()))));
    }

    @Override
    public void implementNotNullAdd(AggContext info, AggAddContext add) {
      Expression acc = add.accumulator().get(0);
      Expression arg = add.arguments().get(0);
      Aggregation aggregation = info.aggregation();
      Expression next = Expressions.call(
          SqlFunctions.class,
          aggregation == MIN ? "lesser" : "greater",
          acc,
          Expressions.unbox(arg));
      accAdvance(add, acc, next);
    }
  }

  static class SingleValueImplementor implements AggImplementor {
    public List<Type> getStateType(AggContext info) {
      return Arrays.asList(boolean.class, info.returnType());
    }

    public void implementReset(AggContext info, AggResetContext reset) {
      List<Expression> acc = reset.accumulator();
      reset.currentBlock().add(Expressions.statement(Expressions.assign(
          acc.get(0), Expressions.constant(false))));
      reset.currentBlock().add(Expressions.statement(Expressions.assign(
          acc.get(1), getDefaultValue(acc.get(1).getType()))));
    }

    public void implementAdd(AggContext info, AggAddContext add) {
      List<Expression> acc = add.accumulator();
      Expression flag = acc.get(0);
      add.currentBlock().add(Expressions.ifThen(flag,
          Expressions.throw_(Expressions.new_(IllegalStateException.class,
              Expressions.constant("more than one value in agg "
                  + info.aggregation().toString())))));
      add.currentBlock().add(Expressions.statement(
          Expressions.assign(flag, Expressions.constant(true))));
      add.currentBlock().add(Expressions.statement(Expressions.assign(
          acc.get(1), add.arguments().get(0))));
    }

    public Expression implementResult(AggContext info,
        AggResultContext result) {
      return RexToLixTranslator.convert(result.accumulator().get(1),
          info.returnType());
    }
  }

  public static class UserDefinedAggReflectiveImplementor
      extends StrictAggImplementor {
    private final AggregateFunctionImpl afi;

    public UserDefinedAggReflectiveImplementor(AggregateFunctionImpl afi) {
      this.afi = afi;
    }

    @Override
    public List<Type> getNotNullState(AggContext info) {
      if (afi.isStatic) {
        return Collections.<Type>singletonList(afi.accumulatorType);
      }
      return Arrays.<Type>asList(afi.accumulatorType, afi.declaringClass);
    }

    @Override
    protected void implementNotNullReset(AggContext info,
        AggResetContext reset) {
      List<Expression> acc = reset.accumulator();
      if (!afi.isStatic) {
        reset.currentBlock().add(Expressions.statement(Expressions.assign(
            acc.get(1), Expressions.new_(afi.declaringClass)
        )));
      }
      reset.currentBlock().add(Expressions.statement(Expressions.assign(
          acc.get(0), Expressions.call(
              afi.isStatic ? null : acc.get(1), afi.initMethod))));
    }

    @Override
    protected void implementNotNullAdd(AggContext info, AggAddContext add) {
      List<Expression> acc = add.accumulator();
      List<Expression> aggArgs = add.arguments();
      List<Expression> args = new ArrayList<Expression>(aggArgs.size() + 1);
      args.add(acc.get(0));
      args.addAll(aggArgs);
      add.currentBlock().add(Expressions.statement(Expressions.assign(
          acc.get(0), Expressions.call(
              afi.isStatic ? null : acc.get(1), afi.addMethod,
              args))));
    }

    @Override
    protected Expression implementNotNullResult(AggContext info,
        AggResultContext result) {
      List<Expression> acc = result.accumulator();
      return Expressions.call(
          afi.isStatic ? null : acc.get(1), afi.resultMethod, acc.get(0));
    }
  }

  static class RankImplementor extends StrictWinAggImplementor {
    @Override
    protected void implementNotNullAdd(WinAggContext info,
        WinAggAddContext add) {
      Expression acc = add.accumulator().get(0);
      // This is an example of the generated code
      if (false) {
        new Object() {
          int curentPosition; // position in for-win-agg-loop
          int startIndex;     // index of start of window
          Comparable[] rows;  // accessed via WinAggAddContext.compareRows
          {
            if (curentPosition > startIndex) {
              if (rows[curentPosition - 1].compareTo(rows[curentPosition]) > 0)
              {
                // update rank
              }
            }
          }
        };
      }
      BlockBuilder builder = add.nestBlock();
      add.currentBlock().add(Expressions.ifThen(Expressions.lessThan(
              add.compareRows(Expressions.subtract(add.currentPosition(),
                  Expressions.constant(1)), add.currentPosition()),
              Expressions.constant(0)),
          Expressions.statement(Expressions.assign(
              acc, computeNewRank(acc, add)))));
      add.exitBlock();
      add.currentBlock().add(
          Expressions.ifThen(Expressions.greaterThan(add.currentPosition(),
              add.startIndex()), builder.toBlock()));
    }

    protected Expression computeNewRank(Expression acc, WinAggAddContext add) {
      Expression pos = add.currentPosition();
      if (!add.startIndex().equals(Expressions.constant(0))) {
        // In general, currentPosition-startIndex should be used
        // However, rank/dense_rank does not allow preceding/following clause
        // so we always result in startIndex==0.
        pos = Expressions.subtract(pos, add.startIndex());
      }
      return pos;
    }

    @Override
    protected Expression implementNotNullResult(
        WinAggContext info, WinAggResultContext result) {
      // Rank is 1-based
      return Expressions.add(super.implementNotNullResult(info, result),
          Expressions.constant(1));
    }
  }

  static class DenseRankImplementor extends RankImplementor {
    @Override
    protected Expression computeNewRank(Expression acc, WinAggAddContext add) {
      return Expressions.add(acc, Expressions.constant(1));
    }
  }

  static class FirstLastValueImplementor implements WinAggImplementor {
    private final SeekType seekType;

    protected FirstLastValueImplementor(SeekType seekType) {
      this.seekType = seekType;
    }

    public List<Type> getStateType(AggContext info) {
      return Collections.emptyList();
    }

    public void implementReset(AggContext info, AggResetContext reset) {
      // no op
    }

    public void implementAdd(AggContext info, AggAddContext add) {
      // no op
    }

    public boolean needCacheWhenFrameIntact() {
      return true;
    }

    public Expression implementResult(AggContext info,
        AggResultContext result) {
      WinAggResultContext winResult = (WinAggResultContext) result;

      return Expressions.condition(winResult.hasRows(),
          winResult.rowTranslator(winResult.computeIndex(
              Expressions.constant(0), seekType)).translate(
              winResult.rexArguments().get(0), info.returnType()),
          getDefaultValue(info.returnType()));
    }
  }

  static class FirstValueImplementor extends FirstLastValueImplementor {
    protected FirstValueImplementor() {
      super(SeekType.START);
    }
  }

  static class LastValueImplementor extends FirstLastValueImplementor {
    protected LastValueImplementor() {
      super(SeekType.END);
    }
  }

  static class LeadLagImplementor implements WinAggImplementor {
    private final boolean isLead;

    protected LeadLagImplementor(boolean isLead) {
      this.isLead = isLead;
    }

    public List<Type> getStateType(AggContext info) {
      return Collections.emptyList();
    }

    public void implementReset(AggContext info, AggResetContext reset) {
      // no op
    }

    public void implementAdd(AggContext info, AggAddContext add) {
      // no op
    }

    public boolean needCacheWhenFrameIntact() {
      return false;
    }

    public Expression implementResult(AggContext info,
        AggResultContext result) {
      WinAggResultContext winResult = (WinAggResultContext) result;

      List<RexNode> rexArgs = winResult.rexArguments();

      ParameterExpression res = Expressions.parameter(0, info.returnType(),
          result.currentBlock().newName(isLead ? "lead" : "lag"));

      Expression offset;
      RexToLixTranslator currentRowTranslator =
          winResult.rowTranslator(winResult.computeIndex(
              Expressions.constant(0), SeekType.SET));
      if (rexArgs.size() >= 2) {
        // lead(x, offset) or lead(x, offset, default)
        offset = currentRowTranslator.translate(
            rexArgs.get(1), int.class);
      } else {
        offset = Expressions.constant(1);
      }
      if (!isLead) {
        offset = Expressions.negate(offset);
      }
      Expression dstIndex = winResult.computeIndex(offset, SeekType.SET);

      Expression rowInRange = winResult.rowInPartition(dstIndex);

      BlockBuilder thenBlock = result.nestBlock();
      Expression lagResult = winResult.rowTranslator(dstIndex).translate(
          rexArgs.get(0), res.type);
      thenBlock.add(Expressions.statement(Expressions.assign(res, lagResult)));
      result.exitBlock();
      BlockStatement thenBranch = thenBlock.toBlock();

      Expression defaultValue = rexArgs.size() == 3
          ? currentRowTranslator.translate(rexArgs.get(2), res.type)
          : getDefaultValue(res.type);

      result.currentBlock().add(Expressions.declare(0, res, null));
      result.currentBlock().add(Expressions.ifThenElse(rowInRange, thenBranch,
          Expressions.statement(Expressions.assign(res, defaultValue))));
      return res;
    }
  }

  public static class LeadImplementor extends LeadLagImplementor {
    protected LeadImplementor() {
      super(true);
    }
  }

  public static class LagImplementor extends LeadLagImplementor {
    protected LagImplementor() {
      super(false);
    }
  }

  static class NtileImplementor implements WinAggImplementor {
    public List<Type> getStateType(AggContext info) {
      return Collections.emptyList();
    }

    public void implementReset(AggContext info, AggResetContext reset) {
      // no op
    }

    public void implementAdd(AggContext info, AggAddContext add) {
      // no op
    }

    public boolean needCacheWhenFrameIntact() {
      return false;
    }

    public Expression implementResult(AggContext info,
        AggResultContext result) {
      WinAggResultContext winResult = (WinAggResultContext) result;

      List<RexNode> rexArgs = winResult.rexArguments();

      Expression tiles =
          winResult.rowTranslator(winResult.index()).translate(
              rexArgs.get(0), int.class);

      Expression ntile =
          Expressions.add(Expressions.constant(1),
              Expressions.divide(
                  Expressions.multiply(
                      tiles,
                      Expressions.subtract(
                          winResult.index(), winResult.startIndex())),
                  winResult.getPartitionRowCount()));

      return ntile;
    }
  }

  static class RowNumberImplementor extends StrictWinAggImplementor {
    @Override
    public List<Type> getNotNullState(WinAggContext info) {
      return Collections.emptyList();
    }

    @Override
    protected void implementNotNullAdd(WinAggContext info,
        WinAggAddContext add) {
      // no op
    }

    @Override
    protected Expression implementNotNullResult(
        WinAggContext info, WinAggResultContext result) {
      // Window cannot be empty since ROWS/RANGE is not possible for ROW_NUMBER
      return Expressions.add(Expressions.subtract(
          result.index(), result.startIndex()), Expressions.constant(1));
    }
  }

  private static class TrimImplementor implements NotNullImplementor {
    public Expression implement(RexToLixTranslator translator, RexCall call,
        List<Expression> translatedOperands) {
      final Object value =
          ((ConstantExpression) translatedOperands.get(0)).value;
      SqlTrimFunction.Flag flag = (SqlTrimFunction.Flag) value;
      return Expressions.call(
          BuiltinMethod.TRIM.method,
          Expressions.constant(
              flag == SqlTrimFunction.Flag.BOTH
              || flag == SqlTrimFunction.Flag.LEADING),
          Expressions.constant(
              flag == SqlTrimFunction.Flag.BOTH
              || flag == SqlTrimFunction.Flag.TRAILING),
          translatedOperands.get(1),
          translatedOperands.get(2));
    }
  }

  private static class MethodImplementor implements NotNullImplementor {
    private final Method method;

    MethodImplementor(Method method) {
      this.method = method;
    }

    public Expression implement(
        RexToLixTranslator translator,
        RexCall call,
        List<Expression> translatedOperands) {
      if (Modifier.isStatic(method.getModifiers())) {
        return Expressions.call(method, translatedOperands);
      } else {
        return Expressions.call(translatedOperands.get(0), method,
            Util.skip(translatedOperands, 1));
      }
    }
  }

  private static class MethodNameImplementor implements NotNullImplementor {
    private final String methodName;

    MethodNameImplementor(String methodName) {
      this.methodName = methodName;
    }

    public Expression implement(
        RexToLixTranslator translator,
        RexCall call,
        List<Expression> translatedOperands) {
      return Expressions.call(
          SqlFunctions.class,
          methodName,
          translatedOperands);
    }
  }

  private static class BinaryImplementor implements NotNullImplementor {
    /** Types that can be arguments to comparison operators such as
     * {@code <}. */
    private static final List<Primitive> COMP_OP_TYPES =
        ImmutableList.of(
            Primitive.BYTE,
            Primitive.CHAR,
            Primitive.SHORT,
            Primitive.INT,
            Primitive.LONG,
            Primitive.FLOAT,
            Primitive.DOUBLE);

    private static final List<SqlBinaryOperator> COMPARISON_OPERATORS =
        ImmutableList.of(
            SqlStdOperatorTable.LESS_THAN,
            SqlStdOperatorTable.LESS_THAN_OR_EQUAL,
            SqlStdOperatorTable.GREATER_THAN,
            SqlStdOperatorTable.GREATER_THAN_OR_EQUAL);

    private final ExpressionType expressionType;
    private final String backupMethodName;

    BinaryImplementor(
        ExpressionType expressionType,
        String backupMethodName) {
      this.expressionType = expressionType;
      this.backupMethodName = backupMethodName;
    }

    public Expression implement(
        RexToLixTranslator translator,
        RexCall call,
        List<Expression> expressions) {
      // neither nullable:
      //   return x OP y
      // x nullable
      //   null_returns_null
      //     return x == null ? null : x OP y
      //   ignore_null
      //     return x == null ? null : y
      // x, y both nullable
      //   null_returns_null
      //     return x == null || y == null ? null : x OP y
      //   ignore_null
      //     return x == null ? y : y == null ? x : x OP y
      if (backupMethodName != null) {
        final Primitive primitive =
            Primitive.ofBoxOr(expressions.get(0).getType());
        final SqlBinaryOperator op = (SqlBinaryOperator) call.getOperator();
        if (primitive == null
            || COMPARISON_OPERATORS.contains(op)
            && !COMP_OP_TYPES.contains(primitive)) {
          return Expressions.call(
              SqlFunctions.class,
              backupMethodName,
              expressions);
        }
      }
      return Expressions.makeBinary(
          expressionType, expressions.get(0), expressions.get(1));
    }
  }

  private static class UnaryImplementor implements NotNullImplementor {
    private final ExpressionType expressionType;

    UnaryImplementor(ExpressionType expressionType) {
      this.expressionType = expressionType;
    }

    public Expression implement(
        RexToLixTranslator translator,
        RexCall call,
        List<Expression> translatedOperands) {
      return Expressions.makeUnary(
          expressionType,
          translatedOperands.get(0));
    }
  }

  private static class CaseImplementor implements CallImplementor {
    public Expression implement(RexToLixTranslator translator, RexCall call,
                                NullAs nullAs) {
      return implementRecurse(translator, call, nullAs, 0);
    }

    private Expression implementRecurse(
        RexToLixTranslator translator, RexCall call, NullAs nullAs, int i) {
      List<RexNode> operands = call.getOperands();
      if (i == operands.size() - 1) {
        // the "else" clause
        return translator.translate(
            translator.builder.ensureType(
                call.getType(), operands.get(i), false), nullAs);
      } else {
        Expression ifTrue;
        try {
          ifTrue = translator.translate(
              translator.builder.ensureType(call.getType(),
                  operands.get(i + 1),
                  false), nullAs);
        } catch (RexToLixTranslator.AlwaysNull e) {
          ifTrue = null;
        }

        Expression ifFalse;
        try {
          ifFalse = implementRecurse(translator, call, nullAs, i + 2);
        } catch (RexToLixTranslator.AlwaysNull e) {
          if (ifTrue == null) {
            throw RexToLixTranslator.AlwaysNull.INSTANCE;
          }
          ifFalse = null;
        }

        Expression test = translator.translate(operands.get(i), NullAs.FALSE);

        return ifTrue == null || ifFalse == null
            ? Util.first(ifTrue, ifFalse)
            : Expressions.condition(test, ifTrue, ifFalse);
      }
    }
  }

  private static class CastOptimizedImplementor implements CallImplementor {
    private final CallImplementor accurate;

    private CastOptimizedImplementor() {
      accurate = createImplementor(new CastImplementor(),
          NullPolicy.STRICT, false);
    }

    public Expression implement(RexToLixTranslator translator, RexCall call,
        NullAs nullAs) {
      // Short-circuit if no cast is required
      RexNode arg = call.getOperands().get(0);
      if (call.getType().equals(arg.getType())) {
        // No cast required, omit cast
        return translator.translate(arg, nullAs);
      }
      return accurate.implement(translator, call, nullAs);
    }
  }

  private static class CastImplementor implements NotNullImplementor {
    public Expression implement(
        RexToLixTranslator translator,
        RexCall call,
        List<Expression> translatedOperands) {
      assert call.getOperands().size() == 1;
      final RelDataType sourceType = call.getOperands().get(0).getType();
      // It's only possible for the result to be null if both expression
      // and target type are nullable. We assume that the caller did not
      // make a mistake. If expression looks nullable, caller WILL have
      // checked that expression is not null before calling us.
      final boolean nullable =
          translator.isNullable(call)
              && sourceType.isNullable()
              && !Primitive.is(translatedOperands.get(0).getType());
      final RelDataType targetType =
          translator.nullifyType(call.getType(), nullable);
      return translator.translateCast(sourceType,
          targetType,
          translatedOperands.get(0));
    }
  }

  private static class ReinterpretImplementor implements NotNullImplementor {
    public Expression implement(
        RexToLixTranslator translator,
        RexCall call,
        List<Expression> translatedOperands) {
      assert call.getOperands().size() == 1;
      return translatedOperands.get(0);
    }
  }

  private static class ValueConstructorImplementor
      implements CallImplementor {
    public Expression implement(
        RexToLixTranslator translator,
        RexCall call,
        NullAs nullAs) {
      return translator.translateConstructor(call.getOperands(),
          call.getOperator().getKind());
    }
  }

  private static class ItemImplementor
      implements CallImplementor {
    public Expression implement(
        RexToLixTranslator translator,
        RexCall call,
        NullAs nullAs) {
      final MethodImplementor implementor =
          getImplementor(
              call.getOperands().get(0).getType().getSqlTypeName());
      return implementNullSemantics0(
          translator, call, nullAs, NullPolicy.STRICT, false,
          implementor);
    }

    private MethodImplementor getImplementor(SqlTypeName sqlTypeName) {
      switch (sqlTypeName) {
      case ARRAY:
        return new MethodImplementor(BuiltinMethod.ARRAY_ITEM.method);
      case MAP:
        return new MethodImplementor(BuiltinMethod.MAP_ITEM.method);
      default:
        return new MethodImplementor(BuiltinMethod.ANY_ITEM.method);
      }
    }
  }

  private static class SystemFunctionImplementor
      implements CallImplementor {
    public Expression implement(
        RexToLixTranslator translator,
        RexCall call,
        NullAs nullAs) {
      switch (nullAs) {
      case IS_NULL:
        return Expressions.constant(false);
      case IS_NOT_NULL:
        return Expressions.constant(true);
      }
      final SqlOperator op = call.getOperator();
      if (op == CURRENT_USER
          || op == SESSION_USER
          || op == USER) {
        return Expressions.constant("sa");
      } else if (op == SYSTEM_USER) {
        return Expressions.constant(System.getProperty("user.name"));
      } else if (op == CURRENT_PATH
          || op == CURRENT_ROLE) {
        // By default, the CURRENT_ROLE function returns
        // the empty string because a role has to be set explicitly.
        return Expressions.constant("");
      } else if (op == CURRENT_TIMESTAMP) {
        return Expressions.call(BuiltinMethod.CURRENT_TIMESTAMP.method, ROOT);
      } else if (op == CURRENT_TIME) {
        return Expressions.call(BuiltinMethod.CURRENT_TIME.method, ROOT);
      } else if (op == CURRENT_DATE) {
        return Expressions.call(BuiltinMethod.CURRENT_DATE.method, ROOT);
      } else if (op == LOCALTIMESTAMP) {
        return Expressions.call(BuiltinMethod.LOCAL_TIMESTAMP.method, ROOT);
      } else if (op == LOCALTIME) {
        return Expressions.call(BuiltinMethod.LOCAL_TIME.method, ROOT);
      } else {
        throw new AssertionError("unknown function " + op);
      }
    }
  }

  /** Implements "IS XXX" operations such as "IS NULL"
   * or "IS NOT TRUE".
   *
   * <p>What these operators have in common:</p>
   * 1. They return TRUE or FALSE, never NULL.
   * 2. Of the 3 input values (TRUE, FALSE, NULL) they return TRUE for 1 or 2,
   *    FALSE for the other 2 or 1.
   */
  private static class IsXxxImplementor
      implements CallImplementor {
    private final Boolean seek;
    private final boolean negate;

    public IsXxxImplementor(Boolean seek, boolean negate) {
      this.seek = seek;
      this.negate = negate;
    }

    public Expression implement(
        RexToLixTranslator translator, RexCall call, NullAs nullAs) {
      List<RexNode> operands = call.getOperands();
      assert operands.size() == 1;
      if (seek == null) {
        return translator.translate(operands.get(0),
            negate ? NullAs.IS_NOT_NULL : NullAs.IS_NULL);
      } else {
        return maybeNegate(
            negate == seek,
            translator.translate(
                operands.get(0),
                seek ? NullAs.FALSE : NullAs.TRUE));
      }
    }
  }

  private static class NotImplementor implements NotNullImplementor {
    private final NotNullImplementor implementor;

    public NotImplementor(NotNullImplementor implementor) {
      this.implementor = implementor;
    }

    private static NotNullImplementor of(NotNullImplementor implementor) {
      return new NotImplementor(implementor);
    }

    public Expression implement(
        RexToLixTranslator translator,
        RexCall call,
        List<Expression> translatedOperands) {
      final Expression expression =
          implementor.implement(translator, call, translatedOperands);
      return Expressions.not(expression);
    }
  }

  private static class DatetimeArithmeticImplementor
      implements NotNullImplementor {
    public Expression implement(RexToLixTranslator translator, RexCall call,
        List<Expression> translatedOperands) {
      final RexNode operand0 = call.getOperands().get(0);
      final Expression trop0 = translatedOperands.get(0);
      Expression trop1 = translatedOperands.get(1);
      switch (operand0.getType().getSqlTypeName()) {
      case DATE:
        trop1 =
            Expressions.convert_(
                Expressions.divide(trop1,
                    Expressions.constant(DateTimeUtil.MILLIS_PER_DAY)),
                int.class);
        break;
      case TIME:
        trop1 = Expressions.convert_(trop1, int.class);
        break;
      }
      return Expressions.add(trop0, trop1);
    }
  }
}

// End RexImpTable.java
