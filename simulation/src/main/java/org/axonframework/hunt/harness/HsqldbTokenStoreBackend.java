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

import org.axonframework.conversion.jackson.JacksonConverter;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.transaction.TransactionalExecutorProvider;
import org.axonframework.messaging.core.unitofwork.transaction.jdbc.JdbcTransactionalExecutorProvider;
import org.axonframework.common.tx.TransactionalExecutor;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jdbc.GenericTokenTableFactory;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jdbc.JdbcTokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jdbc.JdbcTokenStoreConfiguration;
import org.hsqldb.jdbc.JDBCDataSource;
import org.jspecify.annotations.Nullable;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The backend whose token store really decides who owns a segment.
 * <p>
 * <b>What this backend changes, and what it does not.</b> Events still go to the framework's in-heap storage engine,
 * exactly as on the in-heap backend: no store in this tree speaks the Dynamic Consistency Boundary protocol over
 * JDBC, so there is nothing to swap the event side for. What changes is the token store, which becomes a real
 * {@link JdbcTokenStore} over an in-process HSQLDB database, one row per segment carrying an owner and a timestamp.
 * That is the whole point. The in-heap token store implements no ownership at all -- no owner field, no expiry, and a
 * release that does nothing -- so every claim assertion made against it passes without checking anything, which reads
 * as coverage and is not. Running a scenario here gives the same event-store semantics with a real claim algebra
 * underneath, so a difference between the two backends is a claim difference and nothing else.
 * <p>
 * <b>Delivery is at-least-once here, by construction.</b> The token lives in HSQLDB and a workload's read model lives
 * in the heap, so nothing makes a token write and a projection update one transaction. Exactly-once is the guarantee
 * of the shared-resource deployment, which this backend is not; a scenario running here that declared exactly-once
 * would be declaring something the deployment cannot provide.
 * <p>
 * Each run gets its own in-memory database, named after the run, and each node gets its own store view carrying its
 * own node identity against the same table. The database is shut down when the run releases it, because an HSQLDB
 * in-memory catalogue outlives the last connection to it and a suite that leaves one behind per run leaks for the
 * length of the build.
 * <p>
 * <b>A node's view can be given a shortened claim timeout, and that is the whole of the clock-skew emulation.</b>
 * Expiry is the inequality {@code timestamp + claimTimeout < now}; a node whose clock runs {@code delta} ahead
 * evaluates it as {@code timestamp + (claimTimeout - delta) < now}, so shortening the timeout by {@code delta} on one
 * node's view reproduces that node's decisions exactly. See {@link TokenStores#forNode(String, Duration)} for what the
 * emulation deliberately does not model.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public final class HsqldbTokenStoreBackend implements HuntBackend {

    /**
     * The name this backend is selected by in a scenario record.
     */
    public static final String NAME = "hsqldb-tokens";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public EventStorageEngine createEngine() {
        return new InMemoryEventStorageEngine();
    }

    @Override
    public boolean arbitratesTokenClaims() {
        return true;
    }

    @Override
    public TokenStores createTokenStores(String runId, Duration claimTimeout) {
        Objects.requireNonNull(runId, "The runId cannot be null.");
        Objects.requireNonNull(claimTimeout, "The claimTimeout cannot be null.");
        return new HsqldbTokenStores(runId, claimTimeout);
    }

    /**
     * One in-process database per run, handing every node its own identity against the same token table.
     *
     * @author Stefan Dragisic
     * @since 5.3.0
     */
    private static final class HsqldbTokenStores implements TokenStores {

        private final String url;
        private final JDBCDataSource dataSource;
        private final Duration claimTimeout;
        private final AtomicBoolean schemaCreated = new AtomicBoolean();

        private HsqldbTokenStores(String runId, Duration claimTimeout) {
            this.url = "jdbc:hsqldb:mem:hunt-" + runId;
            this.claimTimeout = claimTimeout;
            this.dataSource = new JDBCDataSource();
            dataSource.setUrl(url);
            dataSource.setUser("sa");
            dataSource.setPassword("");
        }

        @Override
        public TokenStore forNode(String nodeId) {
            return forNode(nodeId, Duration.ZERO);
        }

        @Override
        public TokenStore forNode(String nodeId, Duration clockSkew) {
            // A claim timeout shortened by the skew is the same inequality a clock running that far ahead evaluates,
            // so the store's own setting carries the emulation. A skew at or beyond the timeout leaves a non-positive
            // timeout, under which every claim looks expired to this node and nothing else -- which is what a badly
            // skewed clock does, and is exactly the arm that is expected to produce overlapping ownership.
            JdbcTokenStore store = new JdbcTokenStore(new ContextIgnoringExecutorProvider(dataSource),
                                                      new JacksonConverter(),
                                                      JdbcTokenStoreConfiguration.DEFAULT
                                                              .claimTimeout(claimTimeout.minus(clockSkew))
                                                              .nodeId(nodeId));
            if (schemaCreated.compareAndSet(false, true)) {
                store.createSchema(GenericTokenTableFactory.INSTANCE);
            }
            return store;
        }

        @Override
        public void close() {
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("SHUTDOWN");
            } catch (SQLException e) {
                throw new IllegalStateException("Unable to shut down the run's database [" + url + "].", e);
            }
        }
    }

    /**
     * Runs every token-store call in its own database transaction, whatever processing context it arrives under.
     * <p>
     * The framework's own provider expects a connection executor to have been attached to the processing context, and
     * the only thing that attaches one is Spring's transaction manager. A plain-Java harness has no Spring, and the
     * streaming processor always passes a context, so without this the very first claim throws. Falling back to the
     * provider's own no-context branch gives each call its own commit, which is precisely the split-resource
     * deployment this backend represents: the token's transaction and the projection's update are separate, and the
     * delivery guarantee that follows is at-least-once.
     *
     * @author Stefan Dragisic
     * @since 5.3.0
     */
    private record ContextIgnoringExecutorProvider(javax.sql.DataSource dataSource)
            implements TransactionalExecutorProvider<Connection> {

        @Override
        public TransactionalExecutor<Connection> getTransactionalExecutor(@Nullable ProcessingContext context) {
            return new JdbcTransactionalExecutorProvider(dataSource).getTransactionalExecutor(null);
        }
    }
}
