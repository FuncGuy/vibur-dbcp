/**
 * Copyright 2014 Simeon Malchev
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
import org.vibur.dbcp.listener.DestroyListener;
import org.vibur.dbcp.proxy.Proxy;
import org.vibur.objectpool.ConcurrentHolderLinkedPool;
import org.vibur.objectpool.Holder;
import org.vibur.objectpool.HolderValidatingPoolService;
import org.vibur.objectpool.util.SamplingPoolReducer;
import org.vibur.objectpool.util.ThreadedPoolReducer;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * The facade class via which most of the connection pool's and connection factory's functionalities
 * are accessed. Exposes an interface which allows us to:
 *    1. Get and restore a JDBC connection proxy from the pool.
 *    2. Terminate the pool when it is no longer needed.
 *
 * @author Simeon Malchev
 */
public class PoolOperations {

    private static final Logger logger = LoggerFactory.getLogger(PoolOperations.class);

    private final ViburDBCPConfig config;
    private final Set<String> criticalSQLStates;

    private final ConnectionFactory connectionFactory;
    private final HolderValidatingPoolService<ConnState> pool;
    private final ThreadedPoolReducer poolReducer;

    public PoolOperations(ViburDBCPConfig config, DestroyListener destroyListener) {
        if (config == null || destroyListener == null)
            throw new NullPointerException();

        this.config = config;
        this.criticalSQLStates = new HashSet<String>(Arrays.asList(
            config.getCriticalSQLStates().replaceAll("\\s","").split(",")));

        this.connectionFactory = new ConnectionFactory(config, destroyListener);
        this.pool = new ConcurrentHolderLinkedPool<ConnState>(connectionFactory,
            config.getPoolInitialSize(), config.getPoolMaxSize(), config.isPoolFair(),
            config.isPoolEnableConnectionTracking());

        if (config.getReducerTimeIntervalInSeconds() > 0) {
            this.poolReducer = new SamplingPoolReducer(pool,
                config.getReducerTimeIntervalInSeconds(), TimeUnit.SECONDS, config.getReducerSamples()) {

                protected void afterReduce(int reduction, int reduced, Throwable thrown) {
                    if (thrown != null)
                        logger.error("{} thrown while intending to reduce by {}", thrown, reduction);
                    else
                        logger.debug("Intended reduction {} actual {}", reduction, reduced);
                }
            };
            this.poolReducer.start();
        } else
            this.poolReducer = null;
    }

    public Connection getConnection(long timeout) throws SQLException {
        Holder<ConnState> hConnection = timeout == 0 ?
            pool.take() : pool.tryTake(timeout, TimeUnit.MILLISECONDS);
        if (hConnection == null)
            throw new SQLException("Couldn't obtain SQL connection.");
        logger.trace("Getting {}", hConnection.value().connection());
        return Proxy.newConnection(hConnection, config);
    }

    public boolean restore(Holder<ConnState> hConnection, boolean aborted, List<Throwable> errors) {
        int connVersion = hConnection.value().version();
        boolean valid = !aborted && connVersion == connectionFactory.getVersion() && errors.isEmpty();
        boolean restored = pool.restore(hConnection, valid);
        SQLException sqlException;
        if (restored && (sqlException = hasCriticalSQLException(errors)) != null
            && connectionFactory.compareAndSetVersion(connVersion, connVersion + 1)) {
            int destroyed = pool.drainCreated(); // destroys all connections in the pool
            logger.error(String.format(
                "Critical SQLState %s occurred, destroyed %d connections, current connection version is %d.",
                sqlException.getSQLState(), destroyed, connectionFactory.getVersion()), sqlException);
        }
        return restored;
    }

    private SQLException hasCriticalSQLException(List<Throwable> errors) {
        for (Throwable error : errors)
            if (error instanceof SQLException) {
                SQLException sqlException = (SQLException) error;
                if (isCriticalSQLException(sqlException))
                    return sqlException;
            }
        return null;
    }

    private boolean isCriticalSQLException(SQLException sqlException) {
        if (sqlException == null)
            return false;
        if (criticalSQLStates.contains(sqlException.getSQLState()))
            return true;
        return isCriticalSQLException(sqlException.getNextException());
    }

    public void terminate() {
        poolReducer.terminate();
        pool.terminate();
    }

    public HolderValidatingPoolService<ConnState> getPool() {
        return pool;
    }
}
