/**
 * Copyright 2013 Simeon Malchev
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.vibur.dbcp.pool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vibur.dbcp.ViburDBCPConfig;
import org.vibur.dbcp.ViburDBCPException;
import org.vibur.dbcp.cache.MethodDefinition;
import org.vibur.dbcp.cache.MethodResult;
import org.vibur.objectpool.PoolObjectFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.vibur.dbcp.util.StatementUtils.closeStatement;

/**
 * The object factory which controls the lifecycle of the underlying JDBC Connections: creates them,
 * validates them if needed, and destroys them. Used by {@link org.vibur.dbcp.ViburDBCPDataSource}.
 *
 * <p>This {@code ConnectionFactory} is a {@code VersionedObject} which creates versioned JDBC Connection
 * wrappers {@code ConnState(s)}. The version of each {@code ConnState} created by the factory is the same
 * as the version of the factory at the moment of the object creation.
 *
 * @author Simeon Malchev
 */
public class ConnectionFactory implements PoolObjectFactory<ConnState>, VersionedObject {

    private static final Logger logger = LoggerFactory.getLogger(ConnectionFactory.class);

    private final ViburDBCPConfig config;
    private final AtomicInteger version = new AtomicInteger(1);

    public ConnectionFactory(ViburDBCPConfig config) throws ViburDBCPException {
        if (config == null)
            throw new NullPointerException();
        this.config = config;

        initLoginTimeout(config);
        initJdbcDriver(config);
    }

    private void initLoginTimeout(ViburDBCPConfig config) throws ViburDBCPException {
        int loginTimeout = config.getLoginTimeoutInSeconds();
        if (config.getExternalDataSource() == null)
            DriverManager.setLoginTimeout(loginTimeout);
        else
            try {
                config.getExternalDataSource().setLoginTimeout(loginTimeout);
            } catch (SQLException e) {
                throw new ViburDBCPException(e);
            }
    }

    private void initJdbcDriver(ViburDBCPConfig config) throws ViburDBCPException {
        if (config.getDriverClassName() != null)
            try {
                Class.forName(config.getDriverClassName()).newInstance();
            } catch (ClassNotFoundException e) {
                throw new ViburDBCPException(e);
            } catch (InstantiationException e) {
                throw new ViburDBCPException(e);
            } catch (IllegalAccessException e) {
                throw new ViburDBCPException(e);
            }
    }

    /**
     * {@inheritDoc}
     *
     * @throws org.vibur.dbcp.ViburDBCPException if cannot create the underlying JDBC Connection.
     */
    public ConnState create() throws ViburDBCPException {
        int attempt = 0;
        Connection connection = null;
        while (connection == null) {
            try {
                connection = doCreate();
            } catch (SQLException e) {
                logger.debug("Couldn't create a java.sql.Connection, attempt " + attempt, e);
                if (attempt++ >= config.getAcquireRetryAttempts())
                    throw new ViburDBCPException(e);
                try {
                    TimeUnit.MILLISECONDS.sleep(config.getAcquireRetryDelayInMs());
                } catch (InterruptedException ignore) {
                }
            }
        }

        setDefaultValues(connection);
        logger.trace("Created {}", connection);
        return new ConnState(connection, getVersion(), System.currentTimeMillis());
    }

    private Connection doCreate() throws SQLException {
        Connection connection;
        DataSource externalDataSource = config.getExternalDataSource();
        String userName = config.getUsername();
        if (externalDataSource == null) {
            if (userName != null)
                connection = DriverManager.getConnection(config.getJdbcUrl(), userName, config.getPassword());
            else
                connection = DriverManager.getConnection(config.getJdbcUrl());
        } else {
            if (userName != null)
                connection = externalDataSource.getConnection(userName, config.getPassword());
            else
                connection = externalDataSource.getConnection();
        }
        return connection;
    }

    private void setDefaultValues(Connection connection) throws ViburDBCPException {
        try {
            if (config.getDefaultAutoCommit() != null)
                connection.setAutoCommit(config.getDefaultAutoCommit());
            if (config.getDefaultReadOnly() != null)
                connection.setReadOnly(config.getDefaultReadOnly());
            if (config.getDefaultTransactionIsolationValue() != null)
                connection.setTransactionIsolation(config.getDefaultTransactionIsolationValue());
            if (config.getDefaultCatalog() != null)
                connection.setCatalog(config.getDefaultCatalog());
        } catch (SQLException e) {
            throw new ViburDBCPException(e);
        }
    }

    /** {@inheritDoc} */
    public boolean readyToTake(ConnState connState) {
        if (connState.version() != getVersion())
            return false;

        int idleLimit = config.getConnectionIdleLimitInSeconds();
        if (idleLimit >= 0) {
            int idle = (int) (System.currentTimeMillis() - connState.getLastTimeUsedInMillis()) / 1000;
            if (idle >= idleLimit && !executeTestStatement(connState.connection()))
                return false;
        }
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * @throws ViburDBCPException if cannot restore the default values for the underlying JDBC Connection.
     */
    public boolean readyToRestore(ConnState connState) throws ViburDBCPException {
        if (config.isResetDefaultsAfterUse())
            setDefaultValues(connState.connection());
        connState.setLastTimeUsedInMillis(System.currentTimeMillis());
        return true;
    }

    private boolean executeTestStatement(Connection connection) {
        Statement statement = null;
        try {
            String testConnectionQuery = config.getTestConnectionQuery();
            if (testConnectionQuery.equals(ViburDBCPConfig.IS_VALID_QUERY))
                return connection.isValid(ViburDBCPConfig.TEST_CONNECTION_TIMEOUT);

            statement = connection.createStatement();
            statement.setQueryTimeout(ViburDBCPConfig.TEST_CONNECTION_TIMEOUT);
            statement.execute(testConnectionQuery);
            statement.close();
            return true;
        } catch (SQLException e) {
            logger.debug("Couldn't validate " + connection, e);
            try {
                if (statement != null) statement.close();
            } catch (SQLException ignore) {
            }
            return false;
        }
    }

    /** {@inheritDoc} */
    public void destroy(ConnState connState) {
        Connection connection = connState.connection();
        logger.trace("Destroying {}", connection);
        try {
            closeStatements(connection);
            connection.close();
        } catch (SQLException e) {
            logger.debug("Couldn't close " + connection, e);
        }
    }

    private void closeStatements(Connection connection) {
        ConcurrentMap<MethodDefinition, MethodResult<Statement>> statementCache = config.getStatementCache();
        if (statementCache == null)
            return;

        for (Iterator<Map.Entry<MethodDefinition, MethodResult<Statement>>> i = statementCache.entrySet().iterator();
             i.hasNext(); ) {
            Map.Entry<MethodDefinition, MethodResult<Statement>> entry = i.next();
            if (entry.getKey().getConnection().equals(connection)) {
                closeStatement(entry.getValue().value());
                i.remove();
            }
        }
    }

    /** {@inheritDoc} */
    public int getVersion() {
        return version.get();
    }

    /** {@inheritDoc} */
    public void setVersion(int newValue) {
        version.set(newValue);
    }

    /** {@inheritDoc} */
    public boolean compareAndSetVersion(int expect, int update) {
        return version.compareAndSet(expect, update);
    }
}
