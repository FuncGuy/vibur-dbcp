/**
 * Copyright 2015 Simeon Malchev
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
import org.vibur.dbcp.ViburConfig;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static java.lang.String.format;
import static org.vibur.dbcp.ViburConfig.SQLSTATE_CONN_INIT_ERROR;
import static org.vibur.dbcp.ViburConfig.SQLSTATE_CONN_VALIDATE_ERROR;
import static org.vibur.dbcp.util.JdbcUtils.*;
import static org.vibur.dbcp.util.ViburUtils.*;

/**
 * Contains all built-in hooks implementations including hooks for logging of long lasting getConnection() calls,
 * slow SQL queries, and large ResultSets, as well as hooks for initializing and preparing/clearing of the held in
 * the pool raw connections before and after they're used by the application.
 *
 * @see Hook
 * @see ConnectionFactory
 *
 * @author Simeon Malchev
 */
public abstract class DefaultHook {

    private static final Logger logger = LoggerFactory.getLogger(DefaultHook.class);

    final ViburConfig config;

    private DefaultHook(ViburConfig config) {
        this.config = config;
    }

    abstract boolean isEnabled();

    ///////////////////////////////////////////////////////////////////////////////////////////////

    public static class InitConnection extends DefaultHook implements Hook.InitConnection {
        public InitConnection(ViburConfig config) {
            super(config);
        }

        @Override
        public void on(Connection rawConnection, long takenNanos) throws SQLException {
            if (!validateConnection(rawConnection, config.getInitSQL(), config))
                throw new SQLException("validateConnection() returned false", SQLSTATE_CONN_INIT_ERROR);

            setDefaultValues(rawConnection, config);
        }

        @Override
        boolean isEnabled() {
            return config.getInitSQL() != null ||
                    config.getDefaultAutoCommit() != null || config.getDefaultReadOnly() != null ||
                    config.getDefaultTransactionIsolationValue() != null || config.getDefaultCatalog() != null;
        }
    }

    public static class CloseConnection extends DefaultHook implements Hook.CloseConnection {
        public CloseConnection(ViburConfig config) {
            super(config);
        }

        @Override
        public void on(Connection rawConnection, long takenNanos) throws SQLException {
            if (config.isClearSQLWarnings())
                clearWarnings(rawConnection);
            if (config.isResetDefaultsAfterUse())
                setDefaultValues(rawConnection, config);
        }

        @Override
        boolean isEnabled() {
            return config.isClearSQLWarnings() || config.isResetDefaultsAfterUse();
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////

    public static class GetConnectionTiming extends DefaultHook implements Hook.GetConnection {
        public GetConnectionTiming(ViburConfig config) {
            super(config);
        }

        @Override
        public void on(Connection rawConnection, long takenNanos) {
            double takenMillis = takenNanos * 0.000001;
            if (takenMillis < config.getLogConnectionLongerThanMs())
                return;

            StringBuilder log = new StringBuilder(4096)
                    .append(format("Call to getConnection() from pool %s took %f ms, connProxy = %s",
                            getPoolName(config), takenMillis, rawConnection));
            if (config.isLogStackTraceForLongConnection() )
                log.append('\n').append(getStackTraceAsString(new Throwable().getStackTrace()));
            logger.warn(log.toString());
        }

        @Override
        boolean isEnabled() {
            return config.getLogConnectionLongerThanMs() >= 0;
        }
    }

    public static class ValidateConnection extends DefaultHook implements Hook.ValidateConnection {
        public ValidateConnection(ViburConfig config) {
            super(config);
        }

        @Override
        public void on(Connection rawConnection, long idleNanos) throws SQLException {
            if (!validateConnection(rawConnection, config.getTestConnectionQuery(), config))
                throw new SQLException("validateConnection() returned false", SQLSTATE_CONN_VALIDATE_ERROR);
        }

        @Override
        boolean isEnabled() {
            return config.getConnectionIdleLimitInSeconds() >= 0 && config.getTestConnectionQuery() != null;
        }
    }

    public static class QueryTiming extends DefaultHook implements Hook.StatementExecution {
        public QueryTiming(ViburConfig config) {
            super(config);
        }

        @Override
        public void on(String sqlQuery, List<Object[]> queryParams, long takenNanos, SQLException sqlException) {
            double takenMillis = takenNanos * 0.000001;
            boolean logTime = takenMillis >= config.getLogQueryExecutionLongerThanMs();
            boolean logException = sqlException != null && logger.isDebugEnabled();
            if (!logTime && !logException)
                return;

            String poolName = getPoolName(config);
            String formattedSql = formatSql(sqlQuery, queryParams);
            if (logException)
                logger.debug("SQL query execution from pool {}:\n{}\n-- threw:", poolName, formattedSql, sqlException);

            if (logTime) {
                StringBuilder message = new StringBuilder(4096).append(
                        format("SQL query execution from pool %s took %f ms:\n%s", poolName, takenMillis, formattedSql));
                if (config.isLogStackTraceForLongQueryExecution())
                    message.append('\n').append(getStackTraceAsString(new Throwable().getStackTrace()));
                logger.warn(message.toString());
            }
        }

        @Override
        boolean isEnabled() {
            return config.getLogQueryExecutionLongerThanMs() >= 0;
        }
    }

    public static class ResultSetSize extends DefaultHook implements Hook.ResultSetRetrieval {
        public ResultSetSize(ViburConfig config) {
            super(config);
        }

        @Override
        public void on(String sqlQuery, List<Object[]> queryParams, long resultSetSize) {
            if (config.getLogLargeResultSet() > resultSetSize)
                return;

            StringBuilder message = new StringBuilder(4096).append(
                    format("SQL query execution from pool %s retrieved a ResultSet with size %d:\n%s",
                            getPoolName(config), resultSetSize, formatSql(sqlQuery, queryParams)));
            if (config.isLogStackTraceForLargeResultSet())
                message.append('\n').append(getStackTraceAsString(new Throwable().getStackTrace()));
            logger.warn(message.toString());
        }

        @Override
        boolean isEnabled() {
            return config.getLogLargeResultSet() >= 0;
        }
    }
}
