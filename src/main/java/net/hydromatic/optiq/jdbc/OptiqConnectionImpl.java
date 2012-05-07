package net.hydromatic.optiq.jdbc;

import net.hydromatic.optiq.MutableSchema;
import net.hydromatic.optiq.impl.java.MapSchema;
import net.hydromatic.optiq.server.OptiqServer;
import net.hydromatic.optiq.server.OptiqServerStatement;
import org.eigenbase.reltype.RelDataTypeFactory;
import org.eigenbase.sql.type.SqlTypeFactoryImpl;

import java.sql.*;
import java.util.*;
import java.util.concurrent.Executor;

/**
 * Implementation of JDBC connection
 * in the OPTIQ engine.
 *
 * <p>Abstract to allow newer versions of JDBC to add methods.</p>
 */
abstract class OptiqConnectionImpl implements OptiqConnection {
    public static final String CONNECT_STRING_PREFIX = "jdbc:optiq:";
    public final RelDataTypeFactory typeFactory = new SqlTypeFactoryImpl();

    private boolean autoCommit;
    private boolean closed;
    private boolean readOnly;
    private int transactionIsolation;
    private int holdability;
    private int networkTimeout;
    private String catalog;

    final MutableSchema rootSchema = new MapSchema();
    private final UnregisteredDriver driver;
    final Factory factory;
    private final String url;
    private final Properties info;
    final Helper helper = new Helper();

    final OptiqServer server = new OptiqServer() {
        final List<OptiqServerStatement> statementList =
            new ArrayList<OptiqServerStatement>();

        public void removeStatement(OptiqServerStatement optiqServerStatement) {
            statementList.add(optiqServerStatement);
        }

        public void addStatement(OptiqServerStatement statement) {
            statementList.add(statement);
        }
    };

    OptiqConnectionImpl(
        UnregisteredDriver driver, Factory factory, String url, Properties info)
    {
        this.driver = driver;
        this.factory = factory;
        this.url = url;
        this.info = info;
    }

    public MutableSchema getRootSchema() {
        return rootSchema;
    }

    public RelDataTypeFactory getTypeFactory() {
        return typeFactory;
    }

    public Statement createStatement() throws SQLException {
        OptiqStatement statement = factory.newStatement(this);
        server.addStatement(statement);
        return statement;
    }

    public PreparedStatement prepareStatement(String sql) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public CallableStatement prepareCall(String sql) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public String nativeSQL(String sql) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void setAutoCommit(boolean autoCommit) throws SQLException {
        this.autoCommit = autoCommit;
    }

    public boolean getAutoCommit() throws SQLException {
        return autoCommit;
    }

    public void commit() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void rollback() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void close() throws SQLException {
        closed = true;
    }

    public boolean isClosed() throws SQLException {
        return closed;
    }

    public DatabaseMetaData getMetaData() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void setReadOnly(boolean readOnly) throws SQLException {
        this.readOnly = readOnly;
    }

    public boolean isReadOnly() throws SQLException {
        return readOnly;
    }

    public void setCatalog(String catalog) throws SQLException {
        this.catalog = catalog;
    }

    public String getCatalog() throws SQLException {
        return catalog;
    }

    public void setTransactionIsolation(int level) throws SQLException {
        this.transactionIsolation = level;
    }

    public int getTransactionIsolation() throws SQLException {
        return transactionIsolation;
    }

    public SQLWarning getWarnings() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void clearWarnings() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public Statement createStatement(
        int resultSetType, int resultSetConcurrency) throws SQLException
    {
        throw new UnsupportedOperationException();
    }

    public PreparedStatement prepareStatement(
        String sql,
        int resultSetType,
        int resultSetConcurrency) throws SQLException
    {
        throw new UnsupportedOperationException();
    }

    public CallableStatement prepareCall(
        String sql,
        int resultSetType,
        int resultSetConcurrency) throws SQLException
    {
        throw new UnsupportedOperationException();
    }

    public Map<String, Class<?>> getTypeMap() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void setHoldability(int holdability) throws SQLException {
        this.holdability = holdability;
    }

    public int getHoldability() throws SQLException {
        return holdability;
    }

    public Savepoint setSavepoint() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public Savepoint setSavepoint(String name) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void rollback(Savepoint savepoint) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public Statement createStatement(
        int resultSetType,
        int resultSetConcurrency,
        int resultSetHoldability) throws SQLException
    {
        throw new UnsupportedOperationException();
    }

    public PreparedStatement prepareStatement(
        String sql,
        int resultSetType,
        int resultSetConcurrency,
        int resultSetHoldability) throws SQLException
    {
        throw new UnsupportedOperationException();
    }

    public CallableStatement prepareCall(
        String sql,
        int resultSetType,
        int resultSetConcurrency,
        int resultSetHoldability) throws SQLException
    {
        throw new UnsupportedOperationException();
    }

    public PreparedStatement prepareStatement(
        String sql, int autoGeneratedKeys) throws SQLException
    {
        throw new UnsupportedOperationException();
    }

    public PreparedStatement prepareStatement(
        String sql, int[] columnIndexes) throws SQLException
    {
        throw new UnsupportedOperationException();
    }

    public PreparedStatement prepareStatement(
        String sql, String[] columnNames) throws SQLException
    {
        throw new UnsupportedOperationException();
    }

    public Clob createClob() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public Blob createBlob() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public NClob createNClob() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public SQLXML createSQLXML() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public boolean isValid(int timeout) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void setClientInfo(
        String name, String value) throws SQLClientInfoException
    {
        throw new UnsupportedOperationException();
    }

    public void setClientInfo(Properties properties)
        throws SQLClientInfoException
    {
        throw new UnsupportedOperationException();
    }

    public String getClientInfo(String name) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public Properties getClientInfo() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public Array createArrayOf(
        String typeName, Object[] elements) throws SQLException
    {
        throw new UnsupportedOperationException();
    }

    public Struct createStruct(
        String typeName, Object[] attributes) throws SQLException
    {
        throw new UnsupportedOperationException();
    }

    public void setSchema(String schema) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public String getSchema() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void abort(Executor executor) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void setNetworkTimeout(
        Executor executor, int milliseconds) throws SQLException
    {
        this.networkTimeout = milliseconds;
    }

    public int getNetworkTimeout() throws SQLException {
        return networkTimeout;
    }

    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw helper.createException(
            "does not implement '" + iface + "'");
    }

    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this);
    }

    static boolean acceptsURL(String url) {
        return url.startsWith(CONNECT_STRING_PREFIX);
    }

    static class Helper {
        public SQLException createException(String message, Exception e) {
            return new SQLException(message, e);
        }
        public SQLException createException(String message) {
            return new SQLException(message);
        }

        public SQLException toSQLException(SQLException exception) {
            return exception;
        }
    }
}

// End OptiqConnectionImpl.java