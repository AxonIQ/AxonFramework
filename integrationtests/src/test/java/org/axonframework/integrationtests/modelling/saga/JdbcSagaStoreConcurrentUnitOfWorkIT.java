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
import org.axonframework.modelling.saga.repository.StubSaga;
import org.axonframework.modelling.saga.repository.jdbc.JdbcSagaStore;
import org.axonframework.modelling.saga.repository.jdbc.PostgresSagaSqlSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static java.util.Collections.singleton;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that two units of work running concurrently get separate transactions, so that one rolling back does not
 * disturb the other.
 * <p>
 * This matters because {@link JdbcSagaStore} is handed its {@link ConnectionProvider} once, at construction, while the
 * transaction it must join belongs to whichever unit of work is currently running. {@code forcedSameThreadInvocation},
 * which {@link SpringTransactionManager} triggers, pins the phases of a single unit of work to one thread; it
 * does not serialise units of work, so several can be in flight at once, as they are under a pooled event processor.
 * <p>
 * What makes that safe is the provider, not the store and not the {@code ProcessingContext}:
 * {@link SpringDataSourceConnectionProvider} resolves the connection bound to the calling thread's transaction on every
 * call. The same requirement applies to an Axon Framework 5 component such as {@code JdbcTokenStore}, since the
 * {@code ConnectionExecutor} the transaction manager publishes on the context wraps that very same construction-time
 * provider.
 * <p>
 * The two units of work are interleaved deliberately: both insert before either finishes, so a shared transaction or a
 * shared connection would show up as interference rather than being hidden by lucky sequencing.
 * <p>
 * This runs against PostgreSQL rather than the in-memory HSQLDB the rest of these tests use. Under HSQLDB's default
 * two-phase locking an insert takes a table-level write lock, so the two units of work serialise and the interleaving
 * deadlocks. That is a property of the database, not of the framework, and it has to be out of the way for this test to
 * be about transaction isolation.
 */
@Testcontainers
class JdbcSagaStoreConcurrentUnitOfWorkIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Container
    private static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:16-alpine");

    private static final AssociationValue ORDER_COMMITTED = new AssociationValue("orderId", "committed");
    private static final AssociationValue ORDER_ROLLED_BACK = new AssociationValue("orderId", "rolled-back");

    private PGSimpleDataSource dataSource;
    private JdbcSagaStore testSubject;
    private UnitOfWorkFactory unitOfWorkFactory;
    private ExecutorService executor;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRESQL.getJdbcUrl());
        dataSource.setUser(POSTGRESQL.getUsername());
        dataSource.setPassword(POSTGRESQL.getPassword());
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS AssociationValueEntry");
            statement.execute("DROP TABLE IF EXISTS SagaEntry");
        }

        ConnectionProvider connectionProvider = new SpringDataSourceConnectionProvider(dataSource);
        testSubject = JdbcSagaStore.builder()
                                   .connectionProvider(connectionProvider)
                                   .sqlSchema(new PostgresSagaSqlSchema())
                                   .converter(new JacksonConverter())
                                   .build();
        testSubject.createSchema();

        unitOfWorkFactory = new TransactionalUnitOfWorkFactory(
                new SpringTransactionManager(new DataSourceTransactionManager(dataSource), null, connectionProvider),
                new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE)
        );
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private List<String> committedSagaIds() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
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

    @Test
    void aRollbackInOneUnitOfWorkLeavesAConcurrentOneAlone() {
        // given two units of work, each inserting a saga, that meet before either finishes
        CyclicBarrier bothInserted = new CyclicBarrier(2);

        CompletableFuture<Void> committing = CompletableFuture.runAsync(() -> {
            UnitOfWork unitOfWork = unitOfWorkFactory.create();
            unitOfWork.runOnInvocation(context -> {
                testSubject.insertSaga(StubSaga.class,
                                       "saga-committed",
                                       new StubSaga(),
                                       singleton(ORDER_COMMITTED));
                awaitBarrier(bothInserted);
            });
            FutureUtils.joinAndUnwrap(unitOfWork.execute(), TIMEOUT);
        }, executor);

        CompletableFuture<Void> failing = CompletableFuture.runAsync(() -> {
            UnitOfWork unitOfWork = unitOfWorkFactory.create();
            unitOfWork.runOnInvocation(context -> {
                testSubject.insertSaga(StubSaga.class,
                                       "saga-rolled-back",
                                       new StubSaga(),
                                       singleton(ORDER_ROLLED_BACK));
                awaitBarrier(bothInserted);
            });
            unitOfWork.onPrepareCommit(context -> CompletableFuture.failedFuture(
                    new IllegalStateException("failing one of the two units of work on purpose")));
            // The failure is the point of this unit of work, so it is expected rather than asserted here.
            unitOfWork.execute().exceptionally(e -> null).join();
        }, executor);

        // when both have run to completion
        CompletableFuture.allOf(committing, failing)
                         .orTimeout(TIMEOUT.toSeconds(), TimeUnit.SECONDS)
                         .join();

        // then each unit of work kept its own transaction: one committed, the other rolled back, and neither took the
        // other with it
        assertThat(committedSagaIds()).containsExactly("saga-committed");
        assertThat(testSubject.findSagas(StubSaga.class, ORDER_COMMITTED)).containsExactly("saga-committed");
        assertThat(testSubject.findSagas(StubSaga.class, ORDER_ROLLED_BACK)).isEmpty();
    }

    @Test
    void twoConcurrentlyCommittingUnitsOfWorkBothPersist() {
        // given two units of work that both insert and both commit, meeting before either finishes
        CyclicBarrier bothInserted = new CyclicBarrier(2);

        CompletableFuture<Void> first = insertInOwnUnitOfWork("saga-one", ORDER_COMMITTED, bothInserted);
        CompletableFuture<Void> second = insertInOwnUnitOfWork("saga-two", ORDER_ROLLED_BACK, bothInserted);

        // when
        CompletableFuture.allOf(first, second).orTimeout(TIMEOUT.toSeconds(), TimeUnit.SECONDS).join();

        // then neither blocked or overwrote the other
        assertThat(committedSagaIds()).containsExactly("saga-one", "saga-two");
    }

    private CompletableFuture<Void> insertInOwnUnitOfWork(String sagaId,
                                                          AssociationValue associationValue,
                                                          CyclicBarrier barrier) {
        return CompletableFuture.runAsync(() -> {
            UnitOfWork unitOfWork = unitOfWorkFactory.create();
            unitOfWork.runOnInvocation(context -> {
                testSubject.insertSaga(StubSaga.class, sagaId, new StubSaga(), singleton(associationValue));
                awaitBarrier(barrier);
            });
            FutureUtils.joinAndUnwrap(unitOfWork.execute(), TIMEOUT);
        }, executor);
    }

    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to meet the other unit of work at the barrier", e);
        }
    }
}
