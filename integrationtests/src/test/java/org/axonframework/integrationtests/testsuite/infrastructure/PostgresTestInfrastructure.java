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

package org.axonframework.integrationtests.testsuite.infrastructure;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.PersistenceUnitTransactionType;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.tx.TransactionalExecutor;
import org.axonframework.common.jpa.EntityManagerProvider;
import org.axonframework.common.jpa.FactoryBasedEntityManagerProvider;
import org.axonframework.conversion.jackson.JacksonConverter;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.jpa.AggregateBasedJpaEventStorageEngine;
import org.axonframework.eventsourcing.eventstore.jpa.AggregateEventEntry;
import org.axonframework.eventsourcing.eventstore.jpa.JpaPollingEventCoordinator;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.transaction.TransactionManager;
import org.axonframework.messaging.core.unitofwork.transaction.jpa.EntityManagerTransactionManager;
import org.axonframework.messaging.core.unitofwork.transaction.jpa.JpaTransactionalExecutorProvider;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jpa.JpaTokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jpa.JpaTokenStoreConfiguration;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jpa.TokenEntry;
import org.jspecify.annotations.Nullable;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link TestInfrastructure} that puts the event store, and optionally the token store, in a real PostgreSQL database.
 * <p>
 * Until this class existed, every integration test in the suite ran against the framework's in-heap components, so a
 * defect that only a persistent store can produce -- a durable ordering that is not commit order, a gap that fills
 * later, an append that becomes visible at the wrong moment in a transaction -- had nothing in the suite that could
 * reach it. An abstract suite that passes in memory and fails here is not an obstacle; it is the reason the arm exists.
 * <p>
 * <b>Which arm a leaf is running, because the guarantee genuinely differs between them.</b>
 * <ul>
 *     <li>{@link #sharedTransactionalResource()} -- the event store and the token store are the same database reached
 *     through the same {@link EntityManager}, and every unit of work of the application is wrapped in one transaction
 *     over it. A batch's token write therefore commits with the batch, which is the deployment the framework's
 *     exactly-once statement is about.</li>
 *     <li>{@link #separateTokenDatabase()} -- the token store is a second PostgreSQL database with a transaction of its
 *     own per call, so a token write and the work it accounts for can succeed and fail independently. This is the
 *     deployment whose delivery guarantee is at-least-once and whose handlers must be idempotent.</li>
 * </ul>
 * <b>What the shared arm does not on its own prove.</b> Exactly-once is a statement about committed effects, and a
 * suite whose read models are fields on a test class has no effect a transaction can cover. The arm is the deployment
 * that can provide the guarantee; measuring that it does needs a read model in the same database and an applied-count
 * assertion, which belongs to a test rather than to the infrastructure.
 * <p>
 * <b>What this store cannot express.</b> The only aggregate-based storage engine in the tree is the JPA one, and it is
 * not a Dynamic Consistency Boundary store: it accepts at most one tag per event and reads that tag as an aggregate
 * identifier, so a boundary spanning several tags cannot be expressed, and its conflict check is the database's unique
 * constraint on {@code (aggregateIdentifier, sequenceNumber)} rather than a scan over a consistency marker. A suite
 * whose assertions depend on the boundary is expected to behave differently here, and that difference is a protocol
 * difference rather than a defect.
 * <p>
 * <b>Lifecycle, and the two traps in it.</b> {@code start()} and {@code stop()} are called around every test method,
 * not around the class, so the container lives in a static field, is started once per virtual machine, and is marked
 * reusable for a developer who opts reuse in; {@code stop()} releases nothing. And {@code purgeData()} is called before
 * the application configuration exists, so it talks to its own database rather than to any component.
 * <p>
 * Isolation is one PostgreSQL schema per infrastructure instance, and a leaf holds one instance in a
 * {@code private static final} field, so two suites never share tables:
 * <pre>{@code
 * public class SealedClassCoursePostgresIT extends SealedClassCourseIT {
 *
 *     private static final TestInfrastructure INFRASTRUCTURE =
 *             PostgresTestInfrastructure.sharedTransactionalResource();
 *
 *     @Override
 *     protected TestInfrastructure testInfrastructure() {
 *         return INFRASTRUCTURE;
 *     }
 * }
 * }</pre>
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
@Internal
public final class PostgresTestInfrastructure implements TestInfrastructure {

    /**
     * The image every arm runs, pinned so that a differential is against one version of one store.
     */
    public static final String IMAGE = "postgres:16-alpine";

    /**
     * Fully-qualified name of the AxonServer configuration enhancer, disabled by name so that no compile-time
     * dependency on the connector is taken and an absent connector is not an error.
     */
    private static final String AXON_SERVER_ENHANCER =
            "io.axoniq.framework.axonserver.connector.configuration.AxonServerConfigurationEnhancer";

    private static final String TOKEN_DATABASE = "axon_it_tokens";
    private static final AtomicLong SCHEMAS = new AtomicLong();

    private final boolean tokenStoreInItsOwnDatabase;
    private final String schema = "axon_it_" + SCHEMAS.incrementAndGet();

    private @Nullable EntityManagerFactory eventStoreFactory;
    private @Nullable EntityManagerFactory tokenStoreFactory;
    private @Nullable ThreadBoundEntityManagers eventStoreManagers;

    private PostgresTestInfrastructure(boolean tokenStoreInItsOwnDatabase) {
        this.tokenStoreInItsOwnDatabase = tokenStoreInItsOwnDatabase;
    }

    /**
     * Returns the arm whose event store and token store are one transactional resource.
     *
     * @return infrastructure whose token write commits with the batch that produced it
     */
    public static PostgresTestInfrastructure sharedTransactionalResource() {
        return new PostgresTestInfrastructure(false);
    }

    /**
     * Returns the arm whose token store is a second database with a transaction of its own per call.
     *
     * @return infrastructure whose token write and the work it accounts for can succeed and fail independently
     */
    public static PostgresTestInfrastructure separateTokenDatabase() {
        return new PostgresTestInfrastructure(true);
    }

    /**
     * Returns the arm this infrastructure is running, for a test that reports which guarantee it measured.
     *
     * @return {@code shared-transactional-resource} or {@code separate-token-database}
     */
    public String arm() {
        return tokenStoreInItsOwnDatabase ? "separate-token-database" : "shared-transactional-resource";
    }

    @Override
    public synchronized void start() {
        if (eventStoreFactory != null) {
            return;
        }
        PostgreSQLContainer container = Container.INSTANCE;
        execute(container.getDatabaseName(), "CREATE SCHEMA IF NOT EXISTS " + schema);
        eventStoreFactory = factory("events-" + schema, container.getDatabaseName(), schema,
                                    AggregateEventEntry.class, TokenEntry.class);
        eventStoreManagers = new ThreadBoundEntityManagers(eventStoreFactory);
        if (tokenStoreInItsOwnDatabase) {
            tokenStoreFactory = factory("tokens-" + schema, TOKEN_DATABASE, schema, TokenEntry.class);
        }
    }

    @Override
    public void configureInfrastructure(ComponentRegistry registry) {
        registry.disableEnhancer(AXON_SERVER_ENHANCER);
        EntityManagerFactory events = Objects.requireNonNull(eventStoreFactory, "start() must run first.");
        EntityManagerProvider managers = Objects.requireNonNull(eventStoreManagers, "start() must run first.");
        // Registered so that the framework's default unit-of-work factory wraps every unit of work in one JPA
        // transaction on the processing context. The storage engine asks the context for the executor to append
        // through, so without this the very first append fails; and having it is what makes an append become durable
        // in the framework's commit phase rather than the moment the engine is handed the events.
        registry.registerComponent(TransactionManager.class,
                                   configuration -> new EntityManagerTransactionManager(managers));
        registry.registerComponent(EventStorageEngine.class, configuration -> new AggregateBasedJpaEventStorageEngine(
                new JpaTransactionalExecutorProvider(events),
                configuration.getComponent(EventConverter.class),
                engine -> engine
                        // On this engine the unique constraint on an aggregate's sequence number is the conflict check,
                        // so without a resolver a rejected append arrives as a bare constraint violation instead of the
                        // framework's own rejection.
                        .persistenceExceptionResolver(PostgresTestInfrastructure::isDuplicateKey)
                        // The default coordinator never tells an open stream that new events landed, so a projection
                        // would only notice them on its own idle re-poll and every test would wait for it.
                        .eventCoordinator(new JpaPollingEventCoordinator(new FactoryBasedEntityManagerProvider(events),
                                                                        Duration.ofMillis(100)))));
        EntityManagerFactory tokens = tokenStoreInItsOwnDatabase
                ? Objects.requireNonNull(tokenStoreFactory, "start() must run first.")
                : events;
        registry.registerComponent(TokenStore.class, configuration -> new JpaTokenStore(
                tokenStoreInItsOwnDatabase
                        // A transaction per call, which is what makes this the split-resource deployment: the token's
                        // transaction is not the batch's, so the two can succeed and fail independently. The framework's
                        // own provider would demand the batch's entity manager off the processing context, and joining
                        // it is exactly what this arm must not do.
                        ? new OwnTransactionPerCall(tokens)
                        : new JpaTransactionalExecutorProvider(tokens),
                new JacksonConverter(),
                JpaTokenStoreConfiguration.DEFAULT.nodeId(arm())));
    }

    @Override
    public void purgeData() {
        // Called before the application configuration exists, so it cannot ask a component for a connection.
        if (eventStoreFactory == null) {
            start();
        }
        execute(Container.INSTANCE.getDatabaseName(),
                "TRUNCATE TABLE " + schema + ".AggregateEventEntry, " + schema + ".TokenEntry");
        if (tokenStoreInItsOwnDatabase) {
            execute(TOKEN_DATABASE, "TRUNCATE TABLE " + schema + ".TokenEntry");
        }
    }

    @Override
    public void stop() {
        // Nothing to release. The container is static and shared by every test in the virtual machine, and this hook
        // runs after every test method rather than after the class, so closing anything here would leave the next test
        // without a database.
    }

    private static boolean isDuplicateKey(Exception exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private static EntityManagerFactory factory(String unit, String database, String schema, Class<?>... managed) {
        PersistenceConfiguration configuration = new PersistenceConfiguration(unit)
                .provider("org.hibernate.jpa.HibernatePersistenceProvider")
                .transactionType(PersistenceUnitTransactionType.RESOURCE_LOCAL)
                .property("jakarta.persistence.jdbc.driver", Container.INSTANCE.getDriverClassName())
                .property("jakarta.persistence.jdbc.url", url(database))
                .property("jakarta.persistence.jdbc.user", Container.INSTANCE.getUsername())
                .property("jakarta.persistence.jdbc.password", Container.INSTANCE.getPassword())
                .property("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                .property("hibernate.default_schema", schema)
                .property("hibernate.hbm2ddl.auto", "update")
                // Hibernate's built-in pool holds twenty connections and refuses rather than waits, so a suite with a
                // streaming processor and several writers exhausts it and the failure arrives as a pool error.
                .property("hibernate.connection.pool_size", "64")
                .property("hibernate.show_sql", "false");
        for (Class<?> type : managed) {
            configuration.managedClass(type);
        }
        return configuration.createEntityManagerFactory();
    }

    private static String url(String database) {
        return Container.INSTANCE.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
    }

    private static void execute(String database, String sql) {
        try (Connection connection = DriverManager.getConnection(url(database),
                                                                 Container.INSTANCE.getUsername(),
                                                                 Container.INSTANCE.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to run [" + sql + "] against [" + database + "].", e);
        }
    }

    /**
     * The one container every arm shares, started the first time an arm asks for it.
     * <p>
     * A holder class rather than a field, so that constructing an infrastructure -- which every leaf does while its
     * class is being loaded -- does not put Docker on the critical path of a build that runs no PostgreSQL test.
     */
    private static final class Container {

        private static final PostgreSQLContainer INSTANCE = started();

        private static PostgreSQLContainer started() {
            PostgreSQLContainer container = new PostgreSQLContainer(IMAGE)
                    .withReuse(true)
                    .withCommand("postgres", "-c", "max_connections=400");
            container.start();
            try (Connection connection = DriverManager.getConnection(container.getJdbcUrl(),
                                                                     container.getUsername(),
                                                                     container.getPassword());
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE " + TOKEN_DATABASE);
            } catch (SQLException e) {
                // PostgreSQL has no CREATE DATABASE IF NOT EXISTS, and a reused container already has it, so the
                // duplicate is the answer rather than a failure.
                if (!"42P04".equals(e.getSQLState())) {
                    throw new IllegalStateException("Unable to create the token database.", e);
                }
            }
            return container;
        }

        private Container() {
            // Holder.
        }
    }

    /**
     * One {@link EntityManager} per thread, because an entity manager is not thread-safe and the JPA transaction
     * manager says as much through {@code requiresSameThreadInvocations()}.
     */
    private static final class ThreadBoundEntityManagers implements EntityManagerProvider {

        private final EntityManagerFactory factory;
        private final Map<Thread, EntityManager> perThread = new ConcurrentHashMap<>();

        private ThreadBoundEntityManagers(EntityManagerFactory factory) {
            this.factory = factory;
        }

        @Override
        public EntityManager getEntityManager() {
            return perThread.computeIfAbsent(Thread.currentThread(), thread -> factory.createEntityManager());
        }
    }

    /**
     * Runs every call in a transaction of its own, whatever processing context it arrives under.
     */
    private static final class OwnTransactionPerCall extends JpaTransactionalExecutorProvider {

        private OwnTransactionPerCall(EntityManagerFactory factory) {
            super(factory);
        }

        @Override
        public TransactionalExecutor<EntityManager> getTransactionalExecutor(@Nullable ProcessingContext context) {
            return super.getTransactionalExecutor(null);
        }
    }
}
