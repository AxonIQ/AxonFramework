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
import java.util.List;
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
        return connect(Deployment.of(container, database));
    }

    private static Connection connect(Deployment deployment) throws SQLException {
        return java.sql.DriverManager.getConnection(deployment.jdbcUrl(),
                                                    deployment.username(),
                                                    deployment.password());
    }

    /**
     * Where one arm's PostgreSQL actually lives, so that an arm can point somewhere other than the shared container.
     * <p>
     * Introduced because the chaos arm has to reach its store through a proxy it can cut and a container it can kill,
     * neither of which the shared container may be: killing a container every other arm is using would break the whole
     * matrix. Every arm resolves its store through this record, so pointing one somewhere else changes no wiring at all.
     *
     * @param jdbcUrl  the URL the run connects on, which for a proxied arm is the proxy's address and not the store's
     * @param username the user to connect as
     * @param password the password to connect with
     * @author Stefan Dragisic
     * @since 5.3.0
     */
    record Deployment(String jdbcUrl, String username, String password) {

        private static Deployment of(PostgreSQLContainer container, String database) {
            return new Deployment(container.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1"),
                                  container.getUsername(),
                                  container.getPassword());
        }
    }

    /**
     * Returns where this arm's PostgreSQL lives.
     * <p>
     * The default is the shared container every read-only arm uses. An arm that breaks its store overrides this with a
     * deployment of its own, because a container the rest of the matrix is connected to cannot be killed.
     *
     * @return this arm's deployment
     */
    Deployment deployment() {
        return Deployment.of(Container.INSTANCE, Container.INSTANCE.getDatabaseName());
    }

    /**
     * Returns the storage-engine settings this arm runs with, given the ones every arm needs.
     * <p>
     * The default changes nothing, which is the framework's own configuration record with a conflict resolver and a
     * polling coordinator attached. An arm whose claim is about a specific setting -- the gap timeout, say, whose core
     * default and Spring Boot default differ -- overrides this and states which setting it is moving and why.
     *
     * @param base the settings every arm of this store needs
     * @return the settings this arm runs with
     */
    org.axonframework.eventsourcing.eventstore.jpa.AggregateBasedJpaEventStorageEngineConfiguration tune(
            org.axonframework.eventsourcing.eventstore.jpa.AggregateBasedJpaEventStorageEngineConfiguration base) {
        return base;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public EventStorageEngine createEngine() {
        Run run = Run.create(deployment());
        AggregateBasedJpaEventStorageEngine engine = new AggregateBasedJpaEventStorageEngine(
                new JpaTransactionalExecutorProvider(run.eventStoreFactory()),
                new DelegatingEventConverter(new JacksonConverter()),
                config -> tune(config
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
                                Duration.ofMillis(50)))));
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
    public @Nullable List<String> readableEventIds(EventStorageEngine engine) {
        return scanIdentifiers(engine);
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
    public boolean commitsOutsideAppendTransaction() {
        return true;
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

        private final Deployment deployment;
        private final String schema;
        private final EntityManagerFactory eventStore;
        private final ThreadBoundEntityManagers eventStoreManagers;

        private Run(Deployment deployment, String schema, EntityManagerFactory eventStore) {
            this.deployment = deployment;
            this.schema = schema;
            this.eventStore = eventStore;
            this.eventStoreManagers = new ThreadBoundEntityManagers(eventStore);
        }

        private static Run create(Deployment deployment) {
            String schema = "hunt_" + RUNS.incrementAndGet() + "_" + Long.toString(System.nanoTime(), 36);
            execute(deployment, "CREATE SCHEMA " + schema);
            Run run = new Run(deployment, schema, factory(deployment, schema,
                                                          AggregateEventEntry.class,
                                                          org.axonframework.messaging.eventhandling.processing.streaming.token.store.jpa.TokenEntry.class));
            WIRING.set(run);
            return run;
        }

        Deployment deployment() {
            return deployment;
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
            try (Connection connection = connect(deployment);
                 Statement statement = connection.createStatement()) {
                statement.execute("SET lock_timeout = '2s'");
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            } catch (SQLException e) {
                LOGGER.info("Left the run's schema [{}] behind: {}", schema, e.getMessage());
            }
        }
    }

    private static EntityManagerFactory factory(Deployment deployment,
                                                @Nullable String schema,
                                                Class<?>... managed) {
        PersistenceConfiguration configuration =
                new PersistenceConfiguration("hunt-" + Integer.toHexString(deployment.jdbcUrl().hashCode())
                                                     + (schema == null ? "" : "-" + schema))
                        .provider("org.hibernate.jpa.HibernatePersistenceProvider")
                        .transactionType(jakarta.persistence.PersistenceUnitTransactionType.RESOURCE_LOCAL)
                        .property("jakarta.persistence.jdbc.driver", "org.postgresql.Driver")
                        .property("jakarta.persistence.jdbc.url", deployment.jdbcUrl())
                        .property("jakarta.persistence.jdbc.user", deployment.username())
                        .property("jakarta.persistence.jdbc.password", deployment.password())
                        .property("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                        .property("hibernate.hbm2ddl.auto", "update")
                        // Hibernate's built-in pool holds twenty connections and refuses rather than waits, so a
                        // contended run exhausts it and every store call fails with a pool error. Measured on this
                        // suite before it was raised: the read side never caught up on any PostgreSQL arm.
                        .property("hibernate.connection.pool_size", "64")
                        // Hibernate's built-in pool hands a connection back without asking whether it still works, so a
                        // store that was killed leaves the pool full of handles to a process that no longer exists and
                        // every call after the restart fails on a connection rather than on anything real. A validation
                        // interval evicts them; one second is short enough that a crash-recovery arm gets a usable pool
                        // back within its heal phase.
                        .property("hibernate.connection.pool_validation_interval", "1")
                        .property("hibernate.show_sql", "false");
        if (schema != null) {
            configuration.property("hibernate.default_schema", schema);
        }
        for (Class<?> type : managed) {
            configuration.managedClass(type);
        }
        return configuration.createEntityManagerFactory();
    }

    private static void execute(PostgreSQLContainer container, String database, String sql) {
        execute(Deployment.of(container, database), sql);
    }

    private static void execute(Deployment deployment, String sql) {
        try (Connection connection = connect(deployment); Statement statement = connection.createStatement()) {
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
                    factory(Deployment.of(Container.INSTANCE, TOKEN_DATABASE), null,
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
        execute(run.deployment(),
                "TRUNCATE TABLE " + run.schema() + ".aggregateevententry, " + run.schema() + ".tokenentry");
    }

    /**
     * Returns every readable event identifier the given run's schema holds, read on a connection of its own.
     * <p>
     * <b>This exists because an oracle about durability must not be answered through the run's own plumbing.</b> After the
     * store has been killed, the entity-manager factory the run was using holds a pool of connections to a process that
     * no longer exists, and whether Hibernate notices is beside the point: the question "did the store keep what it said
     * it kept" has to be put to the store, on a fresh connection, in plain SQL. Anything less measures the harness's
     * recovery rather than the store's.
     *
     * @param engine the engine whose run is to be scanned
     * @return the identifiers the store holds, in global index order
     */
    public static List<String> scanIdentifiers(EventStorageEngine engine) {
        Run run = LIVE.get(Objects.requireNonNull(engine, "The engine cannot be null."));
        if (run == null) {
            return List.of();
        }
        List<String> identifiers = new java.util.ArrayList<>();
        try (Connection connection = connect(run.deployment());
             Statement statement = connection.createStatement();
             java.sql.ResultSet results = statement.executeQuery(
                     "SELECT identifier FROM " + run.schema() + ".aggregateevententry ORDER BY globalindex")) {
            while (results.next()) {
                identifiers.add(results.getString(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to scan the run's events.", e);
        }
        return List.copyOf(identifiers);
    }

    /**
     * The same PostgreSQL, in a container of its own behind a proxy, so that a run may cut it, kill it and freeze it.
     * <p>
     * The only differences from {@link PostgresJpaHuntBackend} are where the store lives and that it can be broken. A
     * divergence between this arm and the shared one is therefore attributable to what was done to the infrastructure and
     * to nothing else.
     * <p>
     * <b>The gap settings are compressed, and that is a declaration rather than a convenience.</b> The shipped gap timeout
     * is a minute, which would make any scenario about a timed-out gap cost a minute per cycle; this arm runs the same
     * mechanism at {@value #CHAOS_GAP_TIMEOUT_MS}ms. Nothing about the code path changes -- the reader still decides
     * whether to record a gap by comparing an event's own timestamp against now minus the timeout -- so a scenario that
     * drives it here drives exactly what a deployment on the default would take sixty times as long to reach.
     *
     * @author Stefan Dragisic
     * @since 5.3.0
     */
    public static class Chaos extends PostgresJpaHuntBackend {

        /**
         * The name the breakable arm is selected by in a scenario record and reported under in a verdict vector.
         */
        public static final String NAME = "postgres-jpa-chaos";

        /**
         * How long a gap survives before the reader stops recording one for an event older than it.
         * <p>
         * The shipped default is 60000. Compressed here for the same reason every other timing in this suite is: the
         * mechanism is a comparison against a threshold, and the comparison behaves identically whichever side of it a
         * second lands on.
         */
        public static final int CHAOS_GAP_TIMEOUT_MS = 1000;

        @Override
        public String name() {
            return NAME;
        }

        @Override
        Deployment deployment() {
            BreakablePostgres breakable = BreakablePostgres.Holder.INSTANCE;
            return new Deployment(breakable.jdbcUrl(), breakable.username(), breakable.password());
        }

        @Override
        org.axonframework.eventsourcing.eventstore.jpa.AggregateBasedJpaEventStorageEngineConfiguration tune(
                org.axonframework.eventsourcing.eventstore.jpa.AggregateBasedJpaEventStorageEngineConfiguration base) {
            return base.gapTimeout(CHAOS_GAP_TIMEOUT_MS);
        }

        @Override
        public StoreInfrastructure infrastructure(EventStorageEngine engine) {
            return BreakablePostgres.Holder.INSTANCE;
        }
    }

    /**
     * The breakable PostgreSQL configured the way Spring Boot's auto-configuration configures it.
     * <p>
     * <b>The two configuration paths do not agree, and this arm is the second half of the differential that shows it.</b>
     * The framework's own configuration record defaults the gap timeout to 60000ms and the maximum gap offset to 10000;
     * Spring Boot's auto-configuration defaults them the other way round, to 10000ms and 60000. A deployment therefore
     * gets different gap behaviour depending on how it was assembled, and a scenario about a timed-out gap that ran only
     * one of the two would report a guarantee it had verified on one configuration as verified on both.
     * <p>
     * Both arms compress the timeout by the same factor -- {@link Chaos#CHAOS_GAP_TIMEOUT_MS} against the core default of
     * 60000, {@value #SPRING_GAP_TIMEOUT_MS} against Spring Boot's 10000 -- so the ratio between the two configurations
     * is preserved and the comparison is the one the deployments actually differ by.
     *
     * @author Stefan Dragisic
     * @since 5.3.0
     */
    public static final class SpringConfigured extends Chaos {

        /**
         * The name the Spring-Boot-configured arm is selected by in a scenario record.
         */
        public static final String NAME = "postgres-jpa-chaos-spring-defaults";

        /**
         * The gap timeout, compressed from Spring Boot's 10000ms default by the same factor the core default is
         * compressed by.
         */
        public static final int SPRING_GAP_TIMEOUT_MS = 167;

        /**
         * The maximum gap offset Spring Boot's auto-configuration defaults to, which is six times the core default.
         */
        public static final int SPRING_MAX_GAP_OFFSET = 60000;

        @Override
        public String name() {
            return NAME;
        }

        @Override
        org.axonframework.eventsourcing.eventstore.jpa.AggregateBasedJpaEventStorageEngineConfiguration tune(
                org.axonframework.eventsourcing.eventstore.jpa.AggregateBasedJpaEventStorageEngineConfiguration base) {
            return base.gapTimeout(SPRING_GAP_TIMEOUT_MS).maxGapOffset(SPRING_MAX_GAP_OFFSET);
        }
    }
}
