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

package org.axonframework.hunt.harness;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceConfiguration;
import org.axonframework.common.jpa.EntityManagerProvider;
import org.axonframework.common.jpa.FactoryBasedEntityManagerProvider;
import org.axonframework.conversion.jackson.JacksonConverter;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.jpa.AggregateBasedJpaEventStorageEngine;
import org.axonframework.eventsourcing.eventstore.jpa.AggregateEventEntry;
import org.axonframework.eventsourcing.eventstore.jpa.JpaPollingEventCoordinator;
import org.axonframework.common.tx.TransactionalExecutor;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.transaction.TransactionManager;
import org.axonframework.messaging.core.unitofwork.transaction.jpa.EntityManagerTransactionManager;
import org.axonframework.messaging.core.unitofwork.transaction.jpa.JpaTransactionalExecutorProvider;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jpa.JpaTokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jpa.JpaTokenStoreConfiguration;
import org.jspecify.annotations.Nullable;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A real PostgreSQL event store, reached through the framework's aggregate-based JPA storage engine.
 * <p>
 * This is the first backend in the suite whose events outlive the process, and the differential against the in-heap
 * store is the whole point of it: a defect that shows up on both is the framework's, and one that shows up here alone
 * belongs to this adapter or to what PostgreSQL does that a {@code TreeMap} does not.
 * <p>
 * <b>What this store cannot express, stated before any scenario is run against it.</b> It is not a Dynamic Consistency
 * Boundary store, and the framework says so itself: the aggregate-based engine accepts at most one tag per event and
 * reads that tag as an aggregate identifier, so a boundary spanning several tags cannot be expressed and conflict
 * detection is the database's unique constraint on {@code (aggregateIdentifier, sequenceNumber)} rather than a scan
 * over a marker. Its {@code globalIndex} comes from a sequence taken before the transaction commits, so index order is
 * not commit order and a reader can see a gap that later fills; the engine is gap-aware for exactly that reason. Every
 * one of those is a difference in the protocol, not a defect, and a scenario whose claim depends on the boundary is
 * reported as not applicable here rather than passed quietly.
 * <p>
 * <b>The run's transaction is on the processing context, and it has to be.</b> The engine asks the context for the
 * executor to run its append in, so the backend supplies an {@link EntityManagerTransactionManager} through
 * {@link #transactionManager(EventStorageEngine)} and every unit of work of the run is wrapped in it. That also decides
 * when an append becomes durable: it commits in the framework's commit phase, which is where the framework's own
 * visibility guarantee puts it. Giving each store call its own transaction instead would make an append durable the
 * moment the engine was handed the events, and every visibility oracle would then report the harness's wiring as a
 * framework defect.
 * <p>
 * <b>Which matrix arm a scenario is running, because the delivery oracle's mode depends on it.</b> Two backends are
 * registered and they differ in one thing:
 * <ul>
 *     <li>{@value #NAME} -- the <b>shared-resource</b> arm. The event store and the token store are the same PostgreSQL
 *     database reached through the same {@code EntityManager}, so a batch's token write joins the transaction that
 *     commits the batch. This is the deployment the framework's exactly-once statement is about.</li>
 *     <li>{@value SplitTokenStore#NAME} -- the <b>split-resource</b> arm. The token store is a second PostgreSQL
 *     database with a transaction of its own per call, so a token write and the work it accounts for can succeed and
 *     fail independently. This is the deployment whose delivery guarantee is at-least-once.</li>
 * </ul>
 * <b>Both arms declare at-least-once, and the reason is not the token store.</b> Exactly-once is a statement about
 * committed effects, and the effects here are a read model in the heap, which no transaction can cover whatever the
 * token store does. Declaring exactly-once on the shared arm would also assert something the framework does not
 * promise: a batch whose transaction rolls back is redelivered even where the resources are shared, so a repeated
 * <em>delivery</em> is legitimate in both arms and only a repeated <em>effect</em> is not. Measuring that needs a
 * transactional read model and an applied-count oracle, which is a scenario rather than a backend.
 * <p>
 * <b>Container and schema lifecycle.</b> One container per virtual machine, started on first use and shared by every
 * run, marked reusable so that a developer who opts reuse in keeps it across builds too. Isolation between runs is a
 * fresh PostgreSQL schema per run, created when the run asks for its engine and dropped when the run releases it, which
 * is cheap where a container per run is not. The token-store view a node gets carries that node's identity, so claims
 * are arbitrated by the real claim algebra in {@code TokenEntry}.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public class PostgresJpaHuntBackend implements HuntBackend {

    /**
     * The name the shared-resource arm is selected by in a scenario record and reported under in a verdict vector.
     */
    public static final String NAME = "postgres-jpa";

    /**
     * The image the suite runs, pinned so that a differential is against one version of one store.
     */
    public static final String IMAGE = "postgres:16-alpine";

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(PostgresJpaHuntBackend.class);
    private static final AtomicLong RUNS = new AtomicLong();
    private static final String TOKEN_DATABASE = "hunt_tokens";

    /**
     * The run being wired, held per thread because a backend is asked for its engine, its transaction manager and its
     * token stores as three separate calls from the one thread that assembles a world.
     */
    private static final ThreadLocal<Run> WIRING = new ThreadLocal<>();

    private static final Map<EventStorageEngine, Run> LIVE = new ConcurrentHashMap<>();

    /**
     * Returns the shared container, starting it the first time anybody asks.
     * <p>
     * A holder class rather than a field initialiser, because a backend is instantiated by {@link java.util.ServiceLoader}
     * on every build of this module and starting a container just because the class was loaded would put Docker on the
     * critical path of a run that never touches PostgreSQL.
     */
    private static final class Container {

        private static final PostgreSQLContainer INSTANCE = started();

        private static PostgreSQLContainer started() {
            // A connection ceiling raised on purpose. A run has a writer thread per participant, a coordinator and
            // several workers, and the storage engine opens an entity manager of its own for every read it does not do
            // inside the run's transaction, so the shipped hundred is reached under a contended workload and the
            // failure arrives as a Hibernate pool error rather than as anything about the framework.
            PostgreSQLContainer container = new PostgreSQLContainer(IMAGE)
                    .withReuse(true)
                    .withCommand("postgres", "-c", "max_connections=400");
            container.start();
            createTokenDatabase(container);
            return container;
        }

        private static void createTokenDatabase(PostgreSQLContainer container) {
            try (Connection connection = connect(container, container.getDatabaseName());
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE " + TOKEN_DATABASE);
            } catch (SQLException e) {
                // A reused container already has it. PostgreSQL has no CREATE DATABASE IF NOT EXISTS, so the duplicate
                // is the answer rather than an error, and any other failure surfaces on the first connection to it.
                if (!"42P04".equals(e.getSQLState())) {
                    throw new IllegalStateException("Unable to create the token database.", e);
                }
            }
        }

        private Container() {
            // Holder.
        }
    }

    private static Connection connect(PostgreSQLContainer container, String database) throws SQLException {
        String url = container.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
        return java.sql.DriverManager.getConnection(url, container.getUsername(), container.getPassword());
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public EventStorageEngine createEngine() {
        Run run = Run.create();
        AggregateBasedJpaEventStorageEngine engine = new AggregateBasedJpaEventStorageEngine(
                new JpaTransactionalExecutorProvider(run.eventStoreFactory()),
                new DelegatingEventConverter(new JacksonConverter()),
                config -> config
                        // Without a resolver the engine cannot tell a conflict from any other failure, and a rejected
                        // append then arrives as a bare constraint violation instead of the framework's own rejection.
                        // On this engine the unique constraint on (aggregateIdentifier, sequenceNumber) *is* the
                        // conflict check.
                        .persistenceExceptionResolver(PostgresJpaHuntBackend::isDuplicateKey)
                        // The engine's default coordinator never tells a stream that new events landed, so a reader
                        // would only notice them on its own idle re-poll. Polling the table at the compressed timescale
                        // keeps the read side's latency comparable with the in-heap backend's, which is what makes a
                        // liveness horizon mean the same thing on both.
                        .eventCoordinator(new JpaPollingEventCoordinator(
                                new FactoryBasedEntityManagerProvider(run.eventStoreFactory()),
                                Duration.ofMillis(50))));
        LIVE.put(engine, run);
        return engine;
    }

    @Override
    public void release(EventStorageEngine engine) {
        Run run = LIVE.remove(engine);
        WIRING.remove();
        // The engine's own polling coordinator runs on a thread of its own, and it keeps polling after the run has
        // dropped its schema and closed its factory -- one "EntityManagerFactory is closed" stack trace every fifty
        // milliseconds, for the rest of the build. Closing the engine terminates that thread, and it has to happen
        // before the factory goes.
        if (engine instanceof AggregateBasedJpaEventStorageEngine jpa) {
            jpa.close();
        }
        if (run != null) {
            run.close();
        }
    }

    @Override
    public @Nullable TransactionManager transactionManager(EventStorageEngine engine) {
        return new EntityManagerTransactionManager(wiring().eventStoreEntityManagers());
    }

    @Override
    public boolean arbitratesTokenClaims() {
        return true;
    }

    @Override
    public boolean speaksDynamicConsistencyBoundaries() {
        return false;
    }

    @Override
    public TokenStores createTokenStores(String runId, Duration claimTimeout) {
        Objects.requireNonNull(runId, "The runId cannot be null.");
        Objects.requireNonNull(claimTimeout, "The claimTimeout cannot be null.");
        Run run = wiring();
        return new TokenStores() {
            @Override
            public TokenStore forNode(String nodeId) {
                return forNode(nodeId, Duration.ZERO);
            }

            @Override
            public TokenStore forNode(String nodeId, Duration clockSkew) {
                return new JpaTokenStore(new JpaTransactionalExecutorProvider(run.eventStoreFactory()),
                                         new JacksonConverter(),
                                         JpaTokenStoreConfiguration.DEFAULT
                                                 .claimTimeout(claimTimeout.minus(clockSkew))
                                                 .nodeId(nodeId));
            }
        };
    }

    /**
     * Returns the run this thread is assembling.
     *
     * @throws IllegalStateException if the caller asked for the run's resources before asking for its engine
     */
    static Run wiring() {
        Run run = WIRING.get();
        if (run == null) {
            throw new IllegalStateException("No PostgreSQL run is being assembled on this thread; a world asks for its "
                                                    + "engine before it asks for anything else.");
        }
        return run;
    }

    private static boolean isDuplicateKey(Exception exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    /**
     * One run's schema, its entity-manager factory, and the thread-bound managers handed out of it.
     *
     * @author Stefan Dragisic
     * @since 5.3.0
     */
    static final class Run implements AutoCloseable {

        private final String schema;
        private final EntityManagerFactory eventStore;
        private final ThreadBoundEntityManagers eventStoreManagers;

        private Run(String schema, EntityManagerFactory eventStore) {
            this.schema = schema;
            this.eventStore = eventStore;
            this.eventStoreManagers = new ThreadBoundEntityManagers(eventStore);
        }

        private static Run create() {
            PostgreSQLContainer container = Container.INSTANCE;
            String schema = "hunt_" + RUNS.incrementAndGet() + "_" + Long.toString(System.nanoTime(), 36);
            execute(container, container.getDatabaseName(), "CREATE SCHEMA " + schema);
            Run run = new Run(schema, factory(container, container.getDatabaseName(), schema,
                                              AggregateEventEntry.class,
                                              org.axonframework.messaging.eventhandling.processing.streaming.token.store.jpa.TokenEntry.class));
            WIRING.set(run);
            return run;
        }

        EntityManagerFactory eventStoreFactory() {
            return eventStore;
        }

        EntityManagerProvider eventStoreEntityManagers() {
            return eventStoreManagers;
        }

        String schema() {
            return schema;
        }

        /**
         * Releases the run's factory and, if it can, its schema.
         * <p>
         * <b>Dropping the schema is best-effort on purpose, and the reason is a measured hang.</b> A workload's writer
         * threads are daemons and the runner does not wait for them past its budget, so one of them can still hold a
         * transaction that took the store's global-index sequence. `DROP SCHEMA` then waits on that relation lock -- for
         * ever, because PostgreSQL has no default lock timeout -- and the build stops with no output at all. Measured:
         * `DROP SCHEMA ... CASCADE` blocked on a backend that was `idle in transaction` on
         * `select nextval('..."aggregate-event-global-index"')`.
         * <p>
         * A short lock timeout turns that into a failed statement, and a failed statement is ignored: the schema is named
         * after the run, nothing else will ever address it, and the container it lives in dies with the virtual machine.
         * Leaving one behind costs a few kilobytes; blocking on it costs the build.
         */
        @Override
        public void close() {
            eventStoreManagers.closeAll();
            if (eventStore.isOpen()) {
                eventStore.close();
            }
            try (Connection connection = connect(Container.INSTANCE, Container.INSTANCE.getDatabaseName());
                 Statement statement = connection.createStatement()) {
                statement.execute("SET lock_timeout = '2s'");
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            } catch (SQLException e) {
                LOGGER.info("Left the run's schema [{}] behind: {}", schema, e.getMessage());
            }
        }
    }

    private static EntityManagerFactory factory(PostgreSQLContainer container,
                                                String database,
                                                @Nullable String schema,
                                                Class<?>... managed) {
        PersistenceConfiguration configuration =
                new PersistenceConfiguration("hunt-" + database + (schema == null ? "" : "-" + schema))
                        .provider("org.hibernate.jpa.HibernatePersistenceProvider")
                        .transactionType(jakarta.persistence.PersistenceUnitTransactionType.RESOURCE_LOCAL)
                        .property("jakarta.persistence.jdbc.driver", container.getDriverClassName())
                        .property("jakarta.persistence.jdbc.url", urlFor(container, database))
                        .property("jakarta.persistence.jdbc.user", container.getUsername())
                        .property("jakarta.persistence.jdbc.password", container.getPassword())
                        .property("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                        .property("hibernate.hbm2ddl.auto", "update")
                        // Hibernate's built-in pool holds twenty connections and refuses rather than waits, so a
                        // contended run exhausts it and every store call fails with a pool error. Measured on this
                        // suite before it was raised: the read side never caught up on any PostgreSQL arm.
                        .property("hibernate.connection.pool_size", "64")
                        .property("hibernate.show_sql", "false");
        if (schema != null) {
            configuration.property("hibernate.default_schema", schema);
        }
        for (Class<?> type : managed) {
            configuration.managedClass(type);
        }
        return configuration.createEntityManagerFactory();
    }

    private static String urlFor(PostgreSQLContainer container, String database) {
        return container.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
    }

    private static void execute(PostgreSQLContainer container, String database, String sql) {
        try (Connection connection = connect(container, database); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to run [" + sql + "].", e);
        }
    }

    /**
     * One {@link EntityManager} per thread, because a JPA entity manager is not thread-safe and the transaction manager
     * says so through {@code requiresSameThreadInvocations()}.
     * <p>
     * A run has a writer thread per participant plus the processor's coordinator and worker threads, and each of them
     * opens its own units of work; giving them one shared manager would corrupt the run rather than fail it.
     *
     * @author Stefan Dragisic
     * @since 5.3.0
     */
    static final class ThreadBoundEntityManagers implements EntityManagerProvider {

        private final EntityManagerFactory factory;
        private final Map<Thread, EntityManager> perThread = new ConcurrentHashMap<>();

        ThreadBoundEntityManagers(EntityManagerFactory factory) {
            this.factory = factory;
        }

        @Override
        public EntityManager getEntityManager() {
            return perThread.computeIfAbsent(Thread.currentThread(), thread -> factory.createEntityManager());
        }

        void closeAll() {
            perThread.values().forEach(manager -> {
                if (manager.isOpen()) {
                    manager.close();
                }
            });
            perThread.clear();
        }
    }

    /**
     * The same PostgreSQL event store with its token store in a database of its own.
     * <p>
     * The token store's transaction is separate from the batch's, so a token write and the effects it accounts for can
     * succeed or fail independently -- which is the split-resource deployment, and the reason the framework's delivery
     * guarantee there is at-least-once. Everything else about the run is identical, so a difference between this arm
     * and {@link PostgresJpaHuntBackend} is a difference in the transaction boundary and nothing else.
     *
     * @author Stefan Dragisic
     * @since 5.3.0
     */
    public static final class SplitTokenStore extends PostgresJpaHuntBackend {

        /**
         * The name the split-resource arm is selected by in a scenario record.
         */
        public static final String NAME = "postgres-jpa-split-tokens";

        private static final class TokenFactory {

            private static final EntityManagerFactory INSTANCE =
                    factory(Container.INSTANCE, TOKEN_DATABASE, null,
                            org.axonframework.messaging.eventhandling.processing.streaming.token.store.jpa.TokenEntry.class);

            private TokenFactory() {
                // Holder.
            }
        }

        @Override
        public String name() {
            return NAME;
        }

        @Override
        public TokenStores createTokenStores(String runId, Duration claimTimeout) {
            Objects.requireNonNull(runId, "The runId cannot be null.");
            Objects.requireNonNull(claimTimeout, "The claimTimeout cannot be null.");
            // The processor always passes a processing context, and the framework's own provider then demands an entity
            // manager already attached to it -- which is the run's event-store transaction, and joining it is exactly
            // what this arm must not do. Ignoring the context gives each token call its own transaction, which is the
            // split-resource deployment rather than a workaround for a defect.
            JpaTransactionalExecutorProvider ownTransactions =
                    new JpaTransactionalExecutorProvider(TokenFactory.INSTANCE) {
                        @Override
                        public TransactionalExecutor<EntityManager> getTransactionalExecutor(
                                @Nullable ProcessingContext context) {
                            return super.getTransactionalExecutor(null);
                        }
                    };
            return new TokenStores() {
                @Override
                public TokenStore forNode(String nodeId) {
                    return forNode(nodeId, Duration.ZERO);
                }

                @Override
                public TokenStore forNode(String nodeId, Duration clockSkew) {
                    return new JpaTokenStore(ownTransactions,
                                             new JacksonConverter(),
                                             JpaTokenStoreConfiguration.DEFAULT
                                                     .claimTimeout(claimTimeout.minus(clockSkew))
                                                     .nodeId(nodeId));
                }
            };
        }

        @Override
        public void release(EventStorageEngine engine) {
            super.release(engine);
            // The token database is shared by every run of this arm, so its rows are the thing to clear rather than the
            // database. A run's processor name is the same in every run, so leaving them behind would hand the next run
            // the previous one's progress.
            execute(Container.INSTANCE, TOKEN_DATABASE, "TRUNCATE TABLE tokenentry");
        }
    }

    /**
     * Empties every table the shared-resource arm's schema holds, for a caller that wants a clean store without a new
     * schema.
     *
     * @param engine the engine whose run is to be purged
     */
    public static void purge(EventStorageEngine engine) {
        Run run = LIVE.get(Objects.requireNonNull(engine, "The engine cannot be null."));
        if (run == null) {
            return;
        }
        execute(Container.INSTANCE, Container.INSTANCE.getDatabaseName(),
                "TRUNCATE TABLE " + run.schema() + ".aggregateevententry, " + run.schema() + ".tokenentry");
    }
}
