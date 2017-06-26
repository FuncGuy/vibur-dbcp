/**
 * Copyright 2014 Daniel Caldeweyher
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
import org.vibur.dbcp.ViburConfig;
import org.vibur.dbcp.ViburDBCPException;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.vibur.dbcp.ViburConfig.SQLSTATE_CONN_VALIDATE_ERROR;
import static org.vibur.dbcp.util.JdbcUtils.*;

/**
 * The object factory which controls the lifecycle of the underlying JDBC Connections: creates them,
 * validates them if needed, and destroys them. Used by {@link org.vibur.dbcp.ViburDBCPDataSource}.
 *
 * <p>This {@code ConnectionFactory} is a versioned factory which creates versioned JDBC Connection
 * wrappers {@code ConnHolder(s)}. The version of each {@link ConnHolder} created by the factory is the same
 * as the version of the factory at the moment of the object creation.
 *
 * @see Hook
 * @see DefaultHook
 *
 * @author Simeon Malchev
 * @author Daniel Caldeweyher
 */
public class ConnectionFactory implements ViburObjectFactory {

    private static final Logger logger = LoggerFactory.getLogger(ConnectionFactory.class);

    private final ViburConfig config;
    private final ConnHooksHolder connHooks;
    private final AtomicInteger version = new AtomicInteger(1);

    /**
     * Instantiates this object factory.
     *
     * @param config the ViburConfig from which will initialize
     * @throws ViburDBCPException if cannot successfully initialize/configure the underlying SQL system
     */
    public ConnectionFactory(ViburConfig config) throws ViburDBCPException {
        this.config = config;
        this.connHooks = config.getConnHooks();
        initLoginTimeout(config);
    }

    @Override
    public ConnHolder create() throws ViburDBCPException {
        return create(config.getConnector());
    }

    @Override
    public ConnHolder create(Connector connector) throws ViburDBCPException {
        Hook.InitConnection[] onInit = connHooks.onInit();
        long startTime = onInit.length > 0 ? System.nanoTime() : 0;

        int attempt = 0;
        Connection rawConnection = null;
        while (rawConnection == null) {
            try {
                rawConnection = requireNonNull(connector.connect());
            } catch (SQLException e) {
                logger.debug("Couldn't create rawConnection, attempt {}", attempt, e);
                if (attempt++ >= config.getAcquireRetryAttempts())
                    throw new ViburDBCPException(e);
                try {
                    MILLISECONDS.sleep(config.getAcquireRetryDelayInMs());
                } catch (InterruptedException ignored) {
                }
            }
        }

        try {
            if (onInit.length > 0) {
                long takenNanos = System.nanoTime() - startTime;
                for (Hook.InitConnection hook : onInit)
                    hook.on(rawConnection, takenNanos);
            }
        } catch (SQLException e) {
            quietClose(rawConnection);
            throw new ViburDBCPException(e);
        }
        logger.debug("Created rawConnection {}", rawConnection);
        return prepareTracking(new ConnHolder(rawConnection, version(),
                config.getConnectionIdleLimitInSeconds() >= 0 ? System.nanoTime() : 0));
    }

    @Override
    public boolean readyToTake(ConnHolder conn) {
        if (conn.version() != version())
            return false;

        Connection rawConnection = conn.rawConnection();
        try {
            int idleLimit = config.getConnectionIdleLimitInSeconds();
            if (idleLimit >= 0) {
                long idleNanos = System.nanoTime() - conn.getRestoredNanoTime();
                if (NANOSECONDS.toSeconds(idleNanos) >= idleLimit
                        && !validateConnection(rawConnection, config.getTestConnectionQuery(), config))
                    throw new SQLException("validateConnection() returned false", SQLSTATE_CONN_VALIDATE_ERROR);
            }

            prepareTracking(conn);
            return true;
        } catch (SQLException e) {
            logger.debug("Couldn't validate rawConnection {}", rawConnection, e);
            return false;
        }
    }

    @Override
    public boolean readyToRestore(ConnHolder conn) {
        clearTracking(conn); // we don't want to keep the tracking objects references

        Connection rawConnection = conn.rawConnection();
        try {
            Hook.CloseConnection[] onClose = connHooks.onClose();
            if (onClose.length > 0) {
                long takenNanos = System.nanoTime() - conn.getTakenNanoTime();
                for (Hook.CloseConnection hook : onClose)
                    hook.on(rawConnection, takenNanos);
            }

            if (config.getConnectionIdleLimitInSeconds() >= 0)
                conn.setRestoredNanoTime(System.nanoTime());
            return true;
        } catch (SQLException e) {
            logger.debug("Couldn't reset rawConnection {}", rawConnection, e);
            return false;
        }
    }

    private ConnHolder prepareTracking(ConnHolder conn) {
        if (config.isPoolEnableConnectionTracking()) {
            conn.setTakenNanoTime(System.nanoTime());
            conn.setThread(Thread.currentThread());
            conn.setLocation(new Throwable());
        }
        else if (connHooks.onGet().length > 0 || connHooks.onClose().length > 0)
            conn.setTakenNanoTime(System.nanoTime());

        return conn;
    }

    private void clearTracking(ConnHolder conn) {
        if (config.isPoolEnableConnectionTracking()) {
            conn.setTakenNanoTime(0L);
            conn.setLastAccessNanoTime(0L);
            conn.setProxyConnection(null);
            conn.setThread(null);
            conn.setLocation(null);
        }
    }

    @Override
    public void destroy(ConnHolder conn) {
        Connection rawConnection = conn.rawConnection();
        logger.debug("Destroying rawConnection {}", rawConnection);
        closeStatements(rawConnection);

        Hook.DestroyConnection[] onDestroy = connHooks.onDestroy();
        long startTime = onDestroy.length == 0 ? 0 : System.nanoTime();

        quietClose(rawConnection);
        long takenNanos = onDestroy.length == 0 ? 0 : System.nanoTime() - startTime;
        for (Hook.DestroyConnection hook : onDestroy)
            hook.on(rawConnection, takenNanos);
    }

    private void closeStatements(Connection rawConnection) {
        if (config.getStatementCache() != null)
            config.getStatementCache().removeAll(rawConnection);
    }

    @Override
    public int version() {
        return version.get();
    }

    @Override
    public boolean compareAndSetVersion(int expect, int update) {
        return version.compareAndSet(expect, update);
    }
}
