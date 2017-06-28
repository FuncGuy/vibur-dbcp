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

package org.vibur.dbcp;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.runners.MockitoJUnitRunner;
import org.vibur.dbcp.pool.TakenConnection;
import org.vibur.dbcp.stcache.StatementHolder;
import org.vibur.dbcp.stcache.StatementMethod;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.vibur.dbcp.stcache.StatementHolder.State.AVAILABLE;
import static org.vibur.dbcp.stcache.StatementHolder.State.EVICTED;

/**
 * JDBC integration tests.
 *
 * @author Simeon Malchev
 */
@RunWith(MockitoJUnitRunner.class)
public class ViburDBCPDataSourceTest extends AbstractDataSourceTest {

    @Captor
    private ArgumentCaptor<StatementMethod> key1, key2;
    @Captor
    private ArgumentCaptor<StatementHolder> val1, val2;

    @Rule
    public final ExpectedException exception = ExpectedException.none();

    @Test
    public void testSelectStatementNoStatementsCache() throws SQLException {
        DataSource ds = createDataSourceNoStatementsCache();
        doTestSelectStatement(ds);
    }

    @Test
    public void testSelectStatementFromExternalDataSource() throws SQLException {
        DataSource ds = createDataSourceFromExternalDataSource();
        doTestSelectStatement(ds);
    }

    @Test
    public void testSelectStatementWithStatementsCache() throws SQLException {
        ViburDBCPDataSource ds = createDataSourceWithStatementsCache();
        ConcurrentMap<StatementMethod, StatementHolder> mockedStatementCache = mockStatementCache(ds);

        try (Connection connection = ds.getConnection()) {
            executeAndVerifySelectStatement(connection);
            executeAndVerifySelectStatement(connection);

            verifyZeroInteractions(mockedStatementCache);
        }
    }

    @Test
    public void testPreparedSelectStatementNoStatementsCache() throws SQLException {
        DataSource ds = createDataSourceNoStatementsCache();
        doTestPreparedSelectStatement(ds);
    }

    @Test
    public void testPreparedSelectStatementFromExternalDataSource() throws SQLException {
        DataSource ds = createDataSourceFromExternalDataSource();
        doTestPreparedSelectStatement(ds);
    }

    @Test
    public void testPreparedSelectStatementWithStatementsCache() throws SQLException {
        ViburDBCPDataSource ds = createDataSourceWithStatementsCache();
        ConcurrentMap<StatementMethod, StatementHolder> mockedStatementCache = mockStatementCache(ds);

        try (Connection connection = ds.getConnection()) {
            executeAndVerifyPreparedSelectStatement(connection);
            executeAndVerifyPreparedSelectStatement(connection);

            InOrder inOrder = inOrder(mockedStatementCache);
            inOrder.verify(mockedStatementCache).get(key1.capture());
            inOrder.verify(mockedStatementCache).putIfAbsent(same(key1.getValue()), val1.capture());
            inOrder.verify(mockedStatementCache).get(key2.capture());

            assertEquals(1, mockedStatementCache.size());
            assertTrue(mockedStatementCache.containsKey(key1.getValue()));
            assertEquals(key1.getValue(), key2.getValue());
            assertEquals(AVAILABLE, val1.getValue().state().get());
        }
    }

    @Test
    public void testTwoPreparedSelectStatementsWithStatementsCache() throws SQLException {
        ViburDBCPDataSource ds = createDataSourceWithStatementsCache();
        ConcurrentMap<StatementMethod, StatementHolder> mockedStatementCache = mockStatementCache(ds);

        try (Connection connection = ds.getConnection()) {
            executeAndVerifyPreparedSelectStatement(connection);
            executeAndVerifyPreparedSelectStatementByLastName(connection);

            InOrder inOrder = inOrder(mockedStatementCache);
            inOrder.verify(mockedStatementCache).get(key1.capture());
            inOrder.verify(mockedStatementCache).putIfAbsent(same(key1.getValue()), val1.capture());
            inOrder.verify(mockedStatementCache).get(key2.capture());
            inOrder.verify(mockedStatementCache).putIfAbsent(same(key2.getValue()), val2.capture());

            // key1 will be evicted from the StatementCache because its capacity is set to 1.
            assertEquals(1, mockedStatementCache.size());
            assertTrue(mockedStatementCache.containsKey(key2.getValue()));
            assertNotEquals(key1.getValue(), key2.getValue());
            assertEquals(EVICTED, val1.getValue().state().get());
            assertEquals(AVAILABLE, val2.getValue().state().get());
        }
    }

    @Test
    public void testExceptionOnOneConnectionDoesNotImpactOtherConnections() throws SQLException {
        ViburDBCPDataSource ds = createDataSourceNoStatementsCache();
        assertEquals(POOL_INITIAL_SIZE, ds.getPool().remainingCreated());

        // Executing a Statement that will produce an SQLException:
        Connection connection = ds.getConnection();
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("drop table nonexistent");
            fail("SQLException expected");
        } catch (SQLException ignored) {
            // no-op
        } finally {
            connection.close();
        }
        Connection internal1 = connection.unwrap(Connection.class);
        assertTrue(internal1.isClosed());
        assertEquals(POOL_INITIAL_SIZE - 1, ds.getPool().remainingCreated()); // the remainingCreated connections count should decrease by 1

        // Executing a Statement that will not cause an exception:
        connection = ds.getConnection();
        try {
            executeAndVerifySelectStatement(connection);
        } finally {
            connection.close();
        }
        Connection internal2 = connection.unwrap(Connection.class);
        assertNotSame(internal1, internal2);
        assertFalse(internal2.isClosed());
        assertEquals(POOL_INITIAL_SIZE - 1, ds.getPool().remainingCreated()); // the remainingCreated connections count should not decrease more
    }

    @Test
    public void testStatementCloseShouldCloseTheInternalStatementToo() throws SQLException {
        DataSource ds = createDataSourceNoStatementsCache();

        Connection connection = ds.getConnection();
        Statement statement = connection.createStatement();
        PreparedStatement pStatement = connection.prepareStatement("select * from actor where first_name = ?");

        pStatement.close();
        statement.close();
        connection.close();

        Statement internalStatement = statement.unwrap(Statement.class);
        assertTrue(statement.isClosed());
        assertTrue(internalStatement.isClosed());

        PreparedStatement internalPStatement = pStatement.unwrap(PreparedStatement.class);
        assertTrue(pStatement.isClosed());
        assertTrue(internalPStatement.isClosed());
    }

    @Test
    public void testConnectionCloseAfterPoolTerminationShouldCloseTheInternalConnectionToo() throws SQLException {
        ViburDBCPDataSource ds = createDataSourceWithTracking();

        Connection connection = ds.getConnection();
        ds.close();

        Connection internalConnection = connection.unwrap(Connection.class);
        assertFalse(connection.isClosed());
        assertFalse(internalConnection.isClosed());

        connection.close();
        assertTrue(connection.isClosed());
        assertTrue(internalConnection.isClosed());
    }

    @Test
    public void testGetConnectionAfterPoolTermination() throws SQLException {
        ViburDBCPDataSource ds = createDataSourceNoStatementsCache();
        ds.setAllowConnectionAfterTermination(true); // enable the feature
        ds.close();

        Connection connection = ds.getConnection();
        assertFalse(connection.isClosed());
        assertFalse(Proxy.isProxyClass(connection.getClass())); // i.e., that is a native Connection
        connection.close();
    }

    @Test
    public void testGetConnectionAfterPoolTerminationFail() throws SQLException {
        ViburDBCPDataSource ds = createDataSourceNoStatementsCache();
        ds.setAllowConnectionAfterTermination(false); // that's the default value
        ds.close();

        exception.expect(SQLException.class);
        ds.getConnection();
    }

    @Test
    public void testGetNonPooledConnection() throws SQLException {
        ViburDBCPDataSource ds = createDataSourceNoStatementsCache();
        Connection connection = ds.getNonPooledConnection();
        assertFalse(connection.isClosed());
        assertFalse(Proxy.isProxyClass(connection.getClass())); // i.e., that is a native Connection
        connection.close();
    }

    @Test
    public void testSeverPooledConnection() throws SQLException {
        ViburDBCPDataSource ds = createDataSourceNoStatementsCache();
        Connection connection = ds.getConnection();
        Connection internalConnection = connection.unwrap(Connection.class);
        int createdTotal = ds.getPool().createdTotal();

        assertFalse(connection.isClosed());
        assertFalse(internalConnection.isClosed());
        ds.severConnection(connection);
        assertTrue(connection.isClosed());
        assertTrue(internalConnection.isClosed());
        assertEquals(createdTotal - 1, ds.getPool().createdTotal());
    }

    @Test
    public void testSeverNonPooledConnection() throws SQLException {
        ViburDBCPDataSource ds = createDataSourceNoStatementsCache();
        Connection connection = ds.getNonPooledConnection();

        assertFalse(connection.isClosed());
        ds.severConnection(connection);
        assertTrue(connection.isClosed());
    }

    @Test
    public void testConnectionTracking() throws SQLException {
        ViburDBCPDataSource ds = createDataSourceWithTracking();
        Connection connection = ds.getConnection();

        TakenConnection[] takenConnections = ds.getTakenConnections();

        assertEquals(1, takenConnections.length);
        assertSame(connection, takenConnections[0].getProxyConnection());

        long currentNanoTime = System.nanoTime();
        long takenNanoTime = takenConnections[0].getTakenNanoTime();
        assertTrue(takenNanoTime > 0);
        assertTrue(currentNanoTime > takenNanoTime);
        assertEquals(0, takenConnections[0].getLastAccessNanoTime());

        connection.isClosed(); // "unrestricted" methods such as close(), isClosed(), etc don't impact the lastAccessNanoTime
        assertEquals(0, takenConnections[0].getLastAccessNanoTime());

        connection.isReadOnly(); // all other "restricted" methods impact the lastAccessNanoTime
        assertEquals(takenNanoTime, takenConnections[0].getTakenNanoTime()); // takenNanoTime shouldn't change as the connection is not restored, i.e., closed() yet
        assertTrue(currentNanoTime < takenConnections[0].getLastAccessNanoTime());

        connection.close();
    }

    private void doTestSelectStatement(DataSource ds) throws SQLException {
        try (Connection connection = ds.getConnection()) {
            executeAndVerifySelectStatement(connection);
        }
    }

    private void doTestPreparedSelectStatement(DataSource ds) throws SQLException {
        try (Connection connection = ds.getConnection()) {
            executeAndVerifyPreparedSelectStatement(connection);
        }
    }

    private void executeAndVerifySelectStatement(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("select * from actor where first_name = 'CHRISTIAN'")) {

            Set<String> expectedLastNames = new HashSet<>(Arrays.asList("GABLE", "AKROYD", "NEESON"));
            while (resultSet.next()) {
                String lastName = resultSet.getString("last_name");
                assertTrue(expectedLastNames.remove(lastName));
            }
            assertTrue(expectedLastNames.isEmpty());
        }
    }

    private void executeAndVerifyPreparedSelectStatement(Connection connection) throws SQLException {
        try (PreparedStatement pStatement = connection.prepareStatement("select * from actor where first_name = ?")) {
            pStatement.setString(1, "CHRISTIAN");
            try (ResultSet resultSet = pStatement.executeQuery()) {
                Set<String> expectedLastNames = new HashSet<>(Arrays.asList("GABLE", "AKROYD", "NEESON"));
                while (resultSet.next()) {
                    String lastName = resultSet.getString("last_name");
                    assertTrue(expectedLastNames.remove(lastName));
                }
                assertTrue(expectedLastNames.isEmpty());
            }
        }
    }

    private void executeAndVerifyPreparedSelectStatementByLastName(Connection connection) throws SQLException {
        try (PreparedStatement pStatement = connection.prepareStatement("select * from actor where last_name = ?")) {
            pStatement.setString(1, "CROWE");
            try (ResultSet resultSet = pStatement.executeQuery()) {
                Set<String> expectedFirstNames = new HashSet<>(Collections.singletonList("SIDNEY"));
                while (resultSet.next()) {
                    String firstName = resultSet.getString("first_name");
                    assertTrue(expectedFirstNames.remove(firstName));
                }
                assertTrue(expectedFirstNames.isEmpty());
            }
        }
    }
}
