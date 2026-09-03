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

package org.axonframework.integrationtests.modelling.saga;

import org.axonframework.common.FutureUtils;
import org.axonframework.common.jdbc.ConnectionProvider;
import org.axonframework.conversion.jackson.JacksonConverter;
import org.axonframework.extension.spring.jdbc.SpringDataSourceConnectionProvider;
import org.axonframework.extension.spring.messaging.unitofwork.SpringTransactionManager;
import org.axonframework.messaging.core.EmptyApplicationContext;
import org.axonframework.messaging.core.unitofwork.SimpleUnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.TransactionalUnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.modelling.saga.AssociationValue;
import org.axonframework.modelling.saga.AssociationValuesImpl;
import org.axonframework.modelling.saga.repository.SagaStore;
import org.axonframework.modelling.saga.repository.StubSaga;
import org.axonframework.modelling.saga.repository.jdbc.HsqlSagaSqlSchema;
import org.axonframework.modelling.saga.repository.jdbc.JdbcSagaStore;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Collections.singleton;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that {@link JdbcSagaStore} takes part in a transaction driven by a real
 * {@link TransactionalUnitOfWorkFactory} against a real database.
 * <p>
 * This is the JDBC counterpart to the JPA test in {@code axon-legacy}, and it lives here because it needs the real
 * {@link SpringTransactionManager} and {@link SpringDataSourceConnectionProvider}: {@code axon-legacy} deliberately does
 * not depend on the Spring extension, and {@code axon-messaging} ships no JDBC transaction manager of its own.
 * <p>
 * The wiring is the production one. The unit of work starts, commits and rolls back a Spring transaction, and the store
 * is handed the same {@link ConnectionProvider} the transaction manager holds, which is the entirety of the connection
 * between them. The processing context passed to the store does not replace that provider, and no
 * {@code TransactionalExecutorProvider} is involved.
 * <p>
 * Every assertion reads through a connection taken outside the transaction, so what is observed is committed database
 * state.
 */
class JdbcSagaStoreTransactionalUnitOfWorkIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static final AssociationValue ORDER_1 = new AssociationValue("orderId", "order-1");
    private static final AssociationValue ORDER_2 = new AssociationValue("orderId", "order-2");

    private JDBCDataSource dataSource;
    private Connection keepAlive;
    private JdbcSagaStore testSubject;
    private UnitOfWorkFactory unitOfWorkFactory;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = new JDBCDataSource();
        dataSource.setUrl("jdbc:hsqldb:mem:sagastore-uow");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        // HSQLDB discards an in-memory database once the last connection to it closes, and the store closes every
        // connection it obtains. This one is held open for the duration of the test to keep the database alive, and is
        // also what the assertions read through.
        keepAlive = dataSource.getConnection();

        ConnectionProvider connectionProvider = new SpringDataSourceConnectionProvider(dataSource);
        testSubject = JdbcSagaStore.builder()
                                   .connectionProvider(connectionProvider)
                                   .sqlSchema(new HsqlSagaSqlSchema())
                                   .converter(new JacksonConverter())
                                   .build();
        testSubject.createSchema();

        // The transaction manager and the store share one ConnectionProvider, as they would when both are wired from
        // the same bean. Nothing else connects them.
        unitOfWorkFactory = new TransactionalUnitOfWorkFactory(
                new SpringTransactionManager(new DataSourceTransactionManager(dataSource), null, connectionProvider),
                new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE)
        );
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Statement statement = keepAlive.createStatement()) {
            statement.execute("SHUTDOWN");
        }
        keepAlive.close();
    }

    private List<String> committedSagaIds() {
        try (Statement statement = keepAlive.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT sagaId FROM SagaEntry ORDER BY sagaId")) {
            List<String> ids = new ArrayList<>();
            while (resultSet.next()) {
                ids.add(resultSet.getString(1));
            }
            return ids;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read the committed saga rows", e);
        }
    }

    private List<String> committedAssociationSagaIds(AssociationValue associationValue) {
        try (Statement statement = keepAlive.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT sagaId FROM AssociationValueEntry WHERE associationKey = '"
                             + associationValue.getKey() + "' AND associationValue = '"
                             + associationValue.getValue() + "' ORDER BY sagaId")) {
            List<String> ids = new ArrayList<>();
            while (resultSet.next()) {
                ids.add(resultSet.getString(1));
            }
            return ids;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read the committed association rows", e);
        }
    }

    @Nested
    class WhenTheUnitOfWorkCommits {

        @Test
        void theSagaIsPersisted() {
            // given a unit of work that inserts a saga
            UnitOfWork unitOfWork = unitOfWorkFactory.create();
            unitOfWork.runOnInvocation(context -> testSubject.insertSaga(StubSaga.class,
                                                                         "saga-1",
                                                                         new StubSaga(),
                                                                         singleton(ORDER_1)));

            // when
            FutureUtils.joinAndUnwrap(unitOfWork.execute(), TIMEOUT);

            // then the unit of work committed the transaction the store wrote in
            assertThat(committedSagaIds()).containsExactly("saga-1");
            assertThat(committedAssociationSagaIds(ORDER_1)).containsExactly("saga-1");
        }

        @Test
        void everythingWrittenAcrossPhasesIsPersistedTogether() {
            // given a unit of work writing one saga during invocation and another at prepare-commit
            UnitOfWork unitOfWork = unitOfWorkFactory.create();
            unitOfWork.runOnInvocation(context -> testSubject.insertSaga(StubSaga.class,
                                                                         "saga-1",
                                                                         new StubSaga(),
                                                                         singleton(ORDER_1)));
            unitOfWork.runOnPrepareCommit(context -> testSubject.insertSaga(StubSaga.class,
                                                                            "saga-2",
                                                                            new StubSaga(),
                                                                            singleton(ORDER_2)));

            // when
            FutureUtils.joinAndUnwrap(unitOfWork.execute(), TIMEOUT);

            // then both landed in the one transaction
            assertThat(committedSagaIds()).containsExactly("saga-1", "saga-2");
        }

        @Test
        void aSagaUpdatedInALaterUnitOfWorkIsPersisted() {
            // given a saga committed by one unit of work
            UnitOfWork insertingUnitOfWork = unitOfWorkFactory.create();
            insertingUnitOfWork.runOnInvocation(context -> testSubject.insertSaga(StubSaga.class,
                                                                                  "saga-1",
                                                                                  new StubSaga(),
                                                                                  singleton(ORDER_1)));
            FutureUtils.joinAndUnwrap(insertingUnitOfWork.execute(), TIMEOUT);

            // when a second unit of work loads it and writes new state
            StubSaga updated = new StubSaga();
            updated.handled("OrderShipped");
            UnitOfWork updatingUnitOfWork = unitOfWorkFactory.create();
            updatingUnitOfWork.runOnInvocation(context -> {
                SagaStore.Entry<StubSaga> loaded = testSubject.loadSaga(StubSaga.class, "saga-1");
                assertThat(loaded).isNotNull();
                testSubject.updateSaga(StubSaga.class,
                                       "saga-1",
                                       updated,
                                       new AssociationValuesImpl(singleton(ORDER_1)));
            });
            FutureUtils.joinAndUnwrap(updatingUnitOfWork.execute(), TIMEOUT);

            // then the new state is committed
            SagaStore.Entry<StubSaga> entry = testSubject.loadSaga(StubSaga.class, "saga-1");
            assertThat(entry).isNotNull();
            assertThat(entry.saga().getHandledEvents()).containsExactly("OrderShipped");
        }
    }

    @Nested
    class WhenTheUnitOfWorkFails {

        @Test
        void theSagaIsNotPersisted() {
            // given a unit of work that inserts a saga and then fails
            UnitOfWork unitOfWork = unitOfWorkFactory.create();
            unitOfWork.runOnInvocation(context -> testSubject.insertSaga(StubSaga.class,
                                                                         "saga-1",
                                                                         new StubSaga(),
                                                                         singleton(ORDER_1)));
            unitOfWork.onPrepareCommit(context -> CompletableFuture.failedFuture(
                    new IllegalStateException("failing the unit of work on purpose")));

            // when
            assertThatThrownBy(() -> FutureUtils.joinAndUnwrap(unitOfWork.execute(), TIMEOUT))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("failing the unit of work on purpose");

            // then the rollback took the saga with it, which it could only do if the store wrote in the unit of
            // work's transaction rather than one of its own
            assertThat(committedSagaIds()).isEmpty();
            assertThat(committedAssociationSagaIds(ORDER_1)).isEmpty();
        }

        @Test
        void aSagaWrittenBeforeTheFailingPhaseIsNotPersistedEither() {
            // given two sagas written in separate phases, the second of which fails after the first was written
            UnitOfWork unitOfWork = unitOfWorkFactory.create();
            unitOfWork.runOnInvocation(context -> testSubject.insertSaga(StubSaga.class,
                                                                         "saga-1",
                                                                         new StubSaga(),
                                                                         singleton(ORDER_1)));
            unitOfWork.onPrepareCommit(context -> {
                testSubject.insertSaga(StubSaga.class, "saga-2", new StubSaga(), singleton(ORDER_2));
                return CompletableFuture.failedFuture(new IllegalStateException("failing after the second write"));
            });

            // when
            assertThatThrownBy(() -> FutureUtils.joinAndUnwrap(unitOfWork.execute(), TIMEOUT))
                    .isInstanceOf(IllegalStateException.class);

            // then neither survived, so the two writes really shared one transaction
            assertThat(committedSagaIds()).isEmpty();
        }

        @Test
        void anEarlierCommittedSagaIsUnaffected() {
            // given a saga committed by an earlier unit of work
            UnitOfWork committed = unitOfWorkFactory.create();
            committed.runOnInvocation(context -> testSubject.insertSaga(StubSaga.class,
                                                                        "saga-1",
                                                                        new StubSaga(),
                                                                        singleton(ORDER_1)));
            FutureUtils.joinAndUnwrap(committed.execute(), TIMEOUT);

            // when a later unit of work writes and then fails
            UnitOfWork failing = unitOfWorkFactory.create();
            failing.runOnInvocation(context -> testSubject.insertSaga(StubSaga.class,
                                                                      "saga-2",
                                                                      new StubSaga(),
                                                                      singleton(ORDER_2)));
            failing.onPrepareCommit(context -> CompletableFuture.failedFuture(new IllegalStateException("boom")));
            assertThatThrownBy(() -> FutureUtils.joinAndUnwrap(failing.execute(), TIMEOUT))
                    .isInstanceOf(IllegalStateException.class);

            // then only the failed unit of work's write was discarded
            assertThat(committedSagaIds()).containsExactly("saga-1");
        }
    }

    /**
     * Pins the requirement that a component built on this store completes its work on the thread it was invoked on,
     * and shows what the framework does and does not do to help.
     * <p>
     * Axon Framework 4 sagas were synchronous: {@code AnnotatedSagaRepository} wrote through the store during
     * prepare-commit, on the unit of work's thread, and there was no way to be anywhere else. That has to stay true,
     * because {@link SpringDataSourceConnectionProvider} finds the transaction by thread and the store has no
     * {@code ProcessingContext} to find it by instead.
     * <p>
     * The framework gets it most of the way. A {@link SpringTransactionManager} reports
     * {@code requiresSameThreadInvocations() == true}, so {@link TransactionalUnitOfWorkFactory} configures the unit of
     * work for same-thread invocation. But that configures the <b>scheduler</b> the unit of work runs phase handlers
     * on; it does not reject a handler that dispatches work of its own. A handler returning an incomplete
     * {@link CompletableFuture} is awaited, not refused, so whatever ran inside it ran wherever it liked.
     * <p>
     * That is the gap the saga manager has to close by construction rather than by configuration. As an
     * {@code EventHandlingComponent} it returns a {@code MessageStream}, and it must do its store work before
     * completing it, on the invoking thread. Completing it from elsewhere reproduces the first case below.
     */
    @Nested
    class WhenAHandlerCompletesOnAnotherThread {

        @Test
        void theUnitOfWorkAwaitsTheHandlerRatherThanRejectingIt() {
            // given a handler in the shape an EventHandlingComponent produces: one returning a future that completes
            // elsewhere. Same-thread invocation does not forbid this.
            AtomicReference<Thread> writingThread = new AtomicReference<>();
            Thread invokingThread = Thread.currentThread();

            UnitOfWork unitOfWork = unitOfWorkFactory.create();
            unitOfWork.onInvocation(context -> CompletableFuture.runAsync(() -> {
                writingThread.set(Thread.currentThread());
                testSubject.insertSaga(StubSaga.class, "saga-off-thread", new StubSaga(), singleton(ORDER_1));
            }));

            // when
            FutureUtils.joinAndUnwrap(unitOfWork.execute(), TIMEOUT);

            // then the unit of work waited for it and committed normally, so nothing signalled a problem, and the
            // write really did happen somewhere else
            assertThat(committedSagaIds()).containsExactly("saga-off-thread");
            assertThat(writingThread.get()).isNotSameAs(invokingThread);
        }

        @Test
        void aWriteFromAnotherThreadEscapesTheTransactionAndSurvivesARollback() {
            // given the same handler, in a unit of work that goes on to fail
            UnitOfWork unitOfWork = unitOfWorkFactory.create();
            unitOfWork.onInvocation(context -> CompletableFuture.runAsync(() -> testSubject.insertSaga(
                    StubSaga.class, "saga-off-thread", new StubSaga(), singleton(ORDER_1))));
            unitOfWork.onPrepareCommit(context -> CompletableFuture.failedFuture(new IllegalStateException("boom")));

            // when
            assertThatThrownBy(() -> FutureUtils.joinAndUnwrap(unitOfWork.execute(), TIMEOUT))
                    .isInstanceOf(IllegalStateException.class);

            // then the write survived the rollback. The provider found no transaction bound to that thread, so it
            // handed out a connection of its own, which committed independently of the unit of work.
            assertThat(committedSagaIds()).containsExactly("saga-off-thread");
        }

        @Test
        void aWriteOnTheInvokingThreadIsDiscardedByTheSameRollback() {
            // given the same insert where the framework puts it
            UnitOfWork unitOfWork = unitOfWorkFactory.create();
            unitOfWork.runOnInvocation(context -> testSubject.insertSaga(
                    StubSaga.class, "saga-on-thread", new StubSaga(), singleton(ORDER_1)));
            unitOfWork.onPrepareCommit(context -> CompletableFuture.failedFuture(new IllegalStateException("boom")));

            // when
            assertThatThrownBy(() -> FutureUtils.joinAndUnwrap(unitOfWork.execute(), TIMEOUT))
                    .isInstanceOf(IllegalStateException.class);

            // then nothing survived, which is the difference the thread makes and what the pair above is measured
            // against
            assertThat(committedSagaIds()).isEmpty();
        }
    }
}
