/*
 * Copyright (c) 2010-2026. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.modelling.saga.repository.jdbc;

import org.axonframework.common.jdbc.ConnectionProvider;
import org.axonframework.common.jdbc.DataSourceConnectionProvider;
import org.axonframework.common.jdbc.ConnectionWrapperFactory;
import org.axonframework.conversion.jackson.JacksonConverter;
import org.axonframework.modelling.saga.AssociationValue;
import org.axonframework.modelling.saga.AssociationValuesImpl;
import org.axonframework.modelling.saga.repository.SagaStore;
import org.axonframework.modelling.saga.repository.SagaStoreTestSuite;
import org.axonframework.modelling.saga.repository.StubSaga;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

import static java.util.Collections.singleton;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating the {@link JdbcSagaStore}.
 * <p>
 * The store is exercised twice over the same HSQLDB database: once against a plain
 * {@link DataSourceConnectionProvider}, where no transaction is managed at all, and once against a connection provider
 * that hands out the connection bound to a Spring transaction. The second is the interesting one -- it is what shows the
 * store joins an ambient transaction through nothing more than its {@link ConnectionProvider}.
 *
 * @author Kristian Rosenvold
 */
class JdbcSagaStoreTest {

    private static final AssociationValue ORDER_1 = new AssociationValue("orderId", "order-1");

    private JDBCDataSource dataSource;
    private Connection keepAlive;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = new JDBCDataSource();
        dataSource.setUrl("jdbc:hsqldb:mem:sagastore");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        // HSQLDB discards an in-memory database once the last connection to it closes, and the store closes every
        // connection it obtains. This one is held open for the duration of the test to keep the database alive.
        keepAlive = dataSource.getConnection();

        JdbcSagaStore schemaCreator = store(new DataSourceConnectionProvider(dataSource));
        schemaCreator.createSchema();
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Statement statement = keepAlive.createStatement()) {
            statement.execute("SHUTDOWN");
        }
        keepAlive.close();
    }

    private JdbcSagaStore store(ConnectionProvider connectionProvider) {
        return JdbcSagaStore.builder()
                            .connectionProvider(connectionProvider)
                            .sqlSchema(new HsqlSagaSqlSchema())
                            .converter(new JacksonConverter())
                            .build();
    }

    /**
     * Runs the whole {@link SagaStore} contract with nobody managing a transaction, which is how Axon Framework 4
     * behaved when given a bare {@code DataSource}.
     */
    @Nested
    class Standalone extends SagaStoreTestSuite {

        @Override
        protected SagaStore<Object> testSubject() {
            return store(new DataSourceConnectionProvider(dataSource));
        }

        /**
         * Pins behaviour that differs per implementation, and is therefore not part of {@link SagaStoreTestSuite}. This
         * store guards on the update count and changes nothing; {@code InMemorySagaStore} creates the saga and
         * {@code JpaSagaStore} applies the association changes anyway.
         */
        @Test
        void updatingAnAbsentSagaChangesNothing() {
            // given no saga stored, and an association pending addition
            AssociationValuesImpl associations = new AssociationValuesImpl();
            associations.add(ORDER_1);

            // when
            testSubject().updateSaga(StubSaga.class, "saga-1", new StubSaga(), associations);

            // then the update count guard kept the association out too
            assertThat(testSubject().loadSaga(StubSaga.class, "saga-1")).isNull();
            assertThat(testSubject().findSagas(StubSaga.class, ORDER_1)).isEmpty();
        }
    }

    /**
     * Runs the whole {@link SagaStore} contract inside a Spring transaction, reached through a
     * {@link TransactionBoundConnectionProvider}.
     */
    @Nested
    class AmbientTransaction extends SagaStoreTestSuite {

        private PlatformTransactionManager transactionManager;

        @BeforeEach
        void startTransactionManager() {
            transactionManager = new DataSourceTransactionManager(dataSource);
        }

        @Override
        protected SagaStore<Object> testSubject() {
            return store(new TransactionBoundConnectionProvider(dataSource));
        }

        @Override
        protected void inTransaction(Runnable operation) {
            TransactionStatus transaction = transactionManager.getTransaction(new DefaultTransactionDefinition());
            try {
                operation.run();
            } catch (RuntimeException e) {
                transactionManager.rollback(transaction);
                throw e;
            }
            transactionManager.commit(transaction);
        }

        @Test
        void rollingBackTheTransactionDiscardsTheInsertedSaga() {
            // given a saga inserted inside a transaction that is then rolled back
            TransactionStatus transaction = transactionManager.getTransaction(new DefaultTransactionDefinition());
            testSubject().insertSaga(StubSaga.class, "saga-1", new StubSaga(), singleton(ORDER_1));
            transactionManager.rollback(transaction);

            // when / then nothing was persisted, so the store did join the transaction
            assertThat(testSubject().loadSaga(StubSaga.class, "saga-1")).isNull();
            assertThat(testSubject().findSagas(StubSaga.class, ORDER_1)).isEmpty();
        }

        @Test
        void twoInsertsInOneTransactionCommitTogether() {
            // given two sagas inserted in a single transaction that is rolled back
            TransactionStatus transaction = transactionManager.getTransaction(new DefaultTransactionDefinition());
            testSubject().insertSaga(StubSaga.class, "saga-1", new StubSaga(), singleton(ORDER_1));
            testSubject().insertSaga(StubSaga.class, "saga-2", new StubSaga(), singleton(ORDER_1));
            transactionManager.rollback(transaction);

            // then neither survived
            assertThat(testSubject().findSagas(StubSaga.class, ORDER_1)).isEmpty();
        }

        @Test
        void aSecondCallInTheSameTransactionSeesTheFirstCallsUncommittedWrite() {
            // given a saga inserted but not yet committed
            TransactionStatus transaction = transactionManager.getTransaction(new DefaultTransactionDefinition());
            testSubject().insertSaga(StubSaga.class, "saga-1", new StubSaga(), singleton(ORDER_1));

            // when a separate store instance reads within the same transaction
            SagaStore.Entry<StubSaga> entry = testSubject().loadSaga(StubSaga.class, "saga-1");

            // then it sees the write, so closing the connection after the insert did not release it from the
            // transaction and did not commit it either
            assertThat(entry).isNotNull();
            transactionManager.rollback(transaction);
            assertThat(testSubject().loadSaga(StubSaga.class, "saga-1")).isNull();
        }

        @Test
        void insertedSagaIsVisibleOutsideTheTransactionOnceCommitted() throws SQLException {
            // given
            inTransaction(() -> testSubject().insertSaga(StubSaga.class, "saga-1", new StubSaga(), singleton(ORDER_1)));

            // when reading through a connection that has nothing to do with the transaction
            // then the row is there
            assertThat(sagaRowCount()).isEqualTo(1);
        }

        private int sagaRowCount() throws SQLException {
            try (Statement statement = keepAlive.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM SagaEntry")) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    /**
     * Hands out the {@link Connection} bound to the current Spring transaction, wrapped so that closing it releases it
     * back to Spring rather than actually closing it, and so that committing it is a no-op while it is transactional.
     * <p>
     * This mirrors {@code org.axonframework.extension.spring.jdbc.SpringDataSourceConnectionProvider}, reproduced here
     * because {@code axon-legacy} does not depend on the Spring extension. Both halves matter: without the
     * transaction-bound lookup the store would not join the transaction, and without the wrapping the store's own
     * {@code closeQuietly} would close the transaction's connection out from under it.
     */
    private static class TransactionBoundConnectionProvider implements ConnectionProvider {

        private final DataSource dataSource;
        private final ConnectionWrapperFactory.ConnectionCloseHandler closeHandler;

        private TransactionBoundConnectionProvider(DataSource dataSource) {
            this.dataSource = dataSource;
            this.closeHandler = new ConnectionWrapperFactory.ConnectionCloseHandler() {
                @Override
                public void close(Connection connection) {
                    DataSourceUtils.releaseConnection(connection, dataSource);
                }

                @Override
                public void commit(Connection connection) throws SQLException {
                    if (!DataSourceUtils.isConnectionTransactional(connection, dataSource)) {
                        connection.commit();
                    }
                }
            };
        }

        @Override
        public Connection getConnection() throws SQLException {
            return ConnectionWrapperFactory.wrap(DataSourceUtils.doGetConnection(dataSource), closeHandler);
        }
    }
}
