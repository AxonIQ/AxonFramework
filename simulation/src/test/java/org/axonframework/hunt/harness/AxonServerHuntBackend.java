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

import io.axoniq.axonserver.connector.AxonServerConnection;
import io.axoniq.axonserver.connector.AxonServerConnectionFactory;
import io.axoniq.axonserver.connector.impl.ServerAddress;
import org.axonframework.conversion.jackson.JacksonConverter;
import org.axonframework.eventsourcing.eventstore.ConsistencyMarker;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.SourcingCondition;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.jspecify.annotations.Nullable;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A real Axon Server, reached through the published connector, and the suite's first persistent store that speaks the
 * Dynamic Consistency Boundary protocol natively.
 * <p>
 * <b>Why this arm matters more than the count of backends suggests.</b> Every other persistent store in this tree is
 * the aggregate-based JPA engine, which is not a boundary store: it accepts one tag per event and reads it as an
 * aggregate identifier, so the suite's reference model reports itself inexpressible on it and the whole append protocol
 * goes unjudged wherever the events actually outlive the process. This backend closes that hole. Its append condition is
 * a boundary over tags and a marker, so {@code AppendConformsToDcbModel} is decided here rather than declined -- and it
 * is decided against a store reached over gRPC rather than a {@code TreeMap} in the same heap.
 * <p>
 * <b>The arm's exact label, because a verdict from it means nothing without one.</b> Framework
 * {@code 5.3.0-SNAPSHOT} (this reactor) crossed with connector {@value #CONNECTOR_VERSION} and Axon Server
 * {@value #IMAGE}, with <b>one</b> shimmed method:
 * {@code EventStorageEngine.source(SourcingCondition, ProcessingContext)}, supplied by
 * {@link ContextCarryingAxonServerEngine} because no published connector implements it. Read that class before reading
 * any verdict from this arm: it states precisely what the shim models and what it therefore does not.
 * <p>
 * <b>What this arm does not cover, stated before any scenario runs against it.</b>
 * <ul>
 *     <li><b>The token store is the framework's in-heap one</b>, because the connector carries none. Segment ownership
 *     is therefore not arbitrated, and {@link #arbitratesTokenClaims()} says so, which makes every claim invariant
 *     report itself inexpressible here exactly as it does on the in-heap backend. A verdict from this arm is a verdict
 *     about the <em>event store</em>.</li>
 *     <li><b>Server-side persistent streams are not driven.</b> The read side is a
 *     {@code PooledStreamingEventProcessor} over the connector's streaming source with client-side tokens, which is the
 *     same read side every other arm runs. The connector's {@code PersistentStreamEventSource}, where the position lives
 *     on the server, is a different delivery mechanism and no scenario declares it.</li>
 *     <li><b>Snapshots are not driven</b>, so the connector's two unimplemented snapshot methods are not shimmed.</li>
 *     <li><b>One node of Axon Server.</b> A cluster of servers disagreeing with each other is a different failure from
 *     anything here.</li>
 * </ul>
 * <b>Isolation is a purge, not a context per run, and that is a licence limit rather than a choice.</b> Creating a
 * context per run is what a schema per run is for PostgreSQL, and Axon Server refuses it:
 * {@code POST /v1/context} answers {@code 403 [AXONIQ-1700] Maximum number of replication groups reached} on the
 * standalone edition. Each run therefore empties the shared DCB context through
 * {@code DELETE /v1/public/purge-events}, which was measured to reset the store's global index to zero. The consequence
 * is that <b>two runs of this backend must not overlap</b>: they would purge each other. Every caller in this suite runs
 * its arms sequentially.
 * <p>
 * <b>The store must be scanned asynchronously, and getting this wrong is silent.</b> The harness's generic scan drains a
 * sourcing stream with a {@code next()} loop that stops at the first empty answer, which is right for a store that
 * materialises its answer in the heap and <b>always returns zero events here</b>, because a gRPC stream is empty until
 * the first message arrives. Measured on this backend: a store holding four events answered {@code 4} through
 * {@link org.axonframework.messaging.core.MessageStream#reduce} and {@code 0} through the {@code next()} loop. A scan
 * that always answers nothing makes quiescence trivially true and every delivery oracle hold vacuously, so
 * {@link #readableEventIds(EventStorageEngine)} is overridden with the asynchronous drain.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public class AxonServerHuntBackend implements HuntBackend {

    /**
     * The name this backend is selected by in a scenario record, and reported under in a verdict vector.
     */
    public static final String NAME = "axonserver";

    /**
     * The Axon Server image the suite runs, pinned so that a differential is against one version of one store.
     */
    public static final String IMAGE = "docker.axoniq.io/axoniq/axonserver:2026.0.0";

    /**
     * The published connector version this arm links against, which is part of the arm's label.
     */
    public static final String CONNECTOR_VERSION = "5.2.2";

    /**
     * The framework method the harness shims to make the connector loadable, which is the rest of the arm's label.
     */
    public static final String SHIMMED_METHOD =
            "EventStorageEngine.source(SourcingCondition, ProcessingContext)";

    /**
     * The Dynamic Consistency Boundary context every run drives, created by the image's standalone bootstrap.
     */
    static final String CONTEXT = "default";

    /**
     * The log line the server writes once the run's context exists, which is both the readiness signal and the
     * evidence that the context is a Dynamic Consistency Boundary one.
     */
    static final String DCB_CONTEXT_LINE = "Creating DCB context: " + CONTEXT;

    static final int GRPC_PORT = 8124;
    static final int ADMIN_PORT = 8024;

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(AxonServerHuntBackend.class);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(60);
    private static final AtomicLong RUNS = new AtomicLong();
    private static final Map<EventStorageEngine, Run> LIVE = new ConcurrentHashMap<>();
    private static final HttpClient HTTP = HttpClient.newBuilder()
                                                     .connectTimeout(Duration.ofSeconds(10))
                                                     .build();

    /**
     * The one shared Axon Server, started the first time a scenario asks for it.
     * <p>
     * A holder class rather than a field initialiser, because every build of this module instantiates every registered
     * backend through {@link java.util.ServiceLoader} and starting a container just because a class was loaded would put
     * Docker on the critical path of a run that never touches Axon Server.
     */
    private static final class Container {

        private static final GenericContainer<?> INSTANCE = started(Network.newNetwork(), null);

        private Container() {
            // Holder.
        }
    }

    /**
     * Starts one Axon Server with a Dynamic Consistency Boundary context, waiting for the context to exist.
     * <p>
     * The readiness signal is the server's own {@value #DCB_CONTEXT_LINE} log line rather than the health endpoint. The
     * endpoint reports {@code UP} for its sub-components before the {@code default} replication group has elected a
     * leader -- measured: {@code raft} reported {@code default.leader: None} and a {@code WARN} status for the first
     * twenty seconds -- so a naive substring match on the health body returns a server that cannot yet serve. Waiting on
     * the log line waits for exactly the thing a run needs.
     *
     * @param network the network the server joins, so that a proxy can address it by alias
     * @param alias   the alias to answer on, or {@code null} for a server nothing proxies
     * @return the started container
     */
    static GenericContainer<?> started(Network network, @Nullable String alias) {
        GenericContainer<?> container = new GenericContainer<>(IMAGE)
                .withNetwork(network)
                .withExposedPorts(ADMIN_PORT, GRPC_PORT)
                // Creates the built-in _admin and default contexts on first boot, and makes default a Dynamic
                // Consistency Boundary context. Without it the server comes up with no context a boundary store can
                // append to.
                .withEnv("AXONIQ_AXONSERVER_STANDALONE_DCB", "true")
                .withEnv("AXONIQ_AXONSERVER_DEVMODE_ENABLED", "true")
                .waitingFor(Wait.forLogMessage(".*" + DCB_CONTEXT_LINE + ".*\\n", 1)
                                .withStartupTimeout(Duration.ofMinutes(3)))
                .withReuse(true);
        if (alias != null) {
            container.withNetworkAliases(alias);
        }
        container.start();
        return container;
    }

    /**
     * Where one arm's Axon Server is addressed, so that an arm can point somewhere other than the shared container.
     * <p>
     * The gRPC address and the administration address are separate on purpose. The chaos arm's gRPC traffic goes through
     * a proxy it may cut, while its administration traffic goes straight to the container -- so a run can still empty the
     * store and read the server's own state while the application cannot reach it at all.
     *
     * @param grpcHost  the host the connector dials
     * @param grpcPort  the port the connector dials, which for a proxied arm is the proxy's
     * @param adminBase the base URL of the server's administration API, never proxied
     * @author Stefan Dragisic
     * @since 5.3.0
     */
    record Deployment(String grpcHost, int grpcPort, String adminBase) {

        static Deployment direct(GenericContainer<?> container) {
            return new Deployment(container.getHost(),
                                  container.getMappedPort(GRPC_PORT),
                                  "http://" + container.getHost() + ":" + container.getMappedPort(ADMIN_PORT));
        }
    }

    /**
     * Returns where this arm's Axon Server is addressed.
     * <p>
     * The default is the shared container every read-only arm uses. An arm that breaks its server overrides this,
     * because a container the rest of the matrix is connected to cannot be killed.
     *
     * @return this arm's deployment
     */
    Deployment deployment() {
        return Deployment.direct(Container.INSTANCE);
    }

    /**
     * Returns the identifier of the container this arm's server runs in, which is what a report quotes as evidence that
     * a real server served the run.
     *
     * @return the container identifier
     */
    String containerId() {
        return Container.INSTANCE.getContainerId();
    }

    @Override
    public String name() {
        return NAME;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Names the client library, the server image and the one method the harness shims, because a verdict from this arm
     * is unreadable without all three. The connector version is read from the jar's own manifest rather than from the
     * constant, so a dependency bump that nobody updated the constant for is visible in the history rather than hidden
     * behind it.
     */
    @Override
    public Map<String, String> versions() {
        return Map.of("connector",
                      "io.axoniq.framework:axon-server-connector:"
                              + HuntBackend.versionOf(io.axoniq.framework.axonserver.connector.event.AxonServerEventStorageEngine.class),
                      "image", IMAGE,
                      "engine.shimmed", SHIMMED_METHOD);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Empties the shared context first, then opens a connection of its own for the run. The engine is the shimmed
     * {@link ContextCarryingAxonServerEngine}, so a reader of a history from this arm can see from the class name that
     * one method of the store is the harness's and not the connector's.
     */
    @Override
    public EventStorageEngine createEngine() {
        Deployment deployment = deployment();
        purge(deployment);
        Run run = Run.create(deployment);
        EventStorageEngine engine = new ContextCarryingAxonServerEngine(
                run.connection(), new DelegatingEventConverter(new JacksonConverter()));
        LIVE.put(engine, run);
        return engine;
    }

    @Override
    public void release(EventStorageEngine engine) {
        Run run = LIVE.remove(engine);
        if (run != null) {
            run.close();
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Answered through {@link org.axonframework.messaging.core.MessageStream#reduce}, which completes when the finite
     * sourcing stream completes. The generic {@code next()} loop the harness uses for an in-heap store answers zero on
     * every gRPC stream, and a scan that always answers nothing is indistinguishable from a store holding nothing --
     * which makes quiescence trivially true and every delivery oracle hold vacuously.
     */
    @Override
    public @Nullable List<String> readableEventIds(EventStorageEngine engine) {
        return engine.source(SourcingCondition.conditionFor(EventCriteria.havingAnyTag()), null)
                     .reduce(new ArrayList<String>(), (identifiers, entry) -> {
                         if (entry.getResource(ConsistencyMarker.RESOURCE_KEY) == null) {
                             identifiers.add(entry.message().identifier());
                         }
                         return identifiers;
                     })
                     .orTimeout(CALL_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
                     .thenApply(List::copyOf)
                     .join();
    }

    /**
     * Empties the run's context, which is this backend's whole isolation mechanism.
     * <p>
     * Measured to reset the store's global index: a context holding four events reported
     * {@code firstToken=0 latestToken=0} immediately afterwards. A context per run is what a schema per run is for
     * PostgreSQL and the standalone edition refuses it, so two runs of this backend must not overlap.
     *
     * @param deployment the server whose context is to be emptied
     */
    static void purge(Deployment deployment) {
        // Retried, and only here. A previous arm may have killed the server, and a server coming back up refuses
        // administration calls for a few seconds before it accepts them -- which is the store legitimately recovering
        // rather than anything a verdict depends on. Nothing that decides a verdict is retried.
        IllegalStateException last = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            try {
                String body = send(HttpRequest.newBuilder(
                                                    URI.create(deployment.adminBase()
                                                                       + "/v1/public/purge-events?context=" + CONTEXT))
                                            .timeout(CALL_TIMEOUT)
                                            .DELETE()
                                            .build());
                LOGGER.info("Purged the Axon Server context [{}]: {}", CONTEXT, body);
                return;
            } catch (IllegalStateException e) {
                last = e;
                try {
                    Thread.sleep(2000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw new IllegalStateException("Axon Server would not accept a purge of context [" + CONTEXT
                                               + "] within a minute.", last);
    }

    /**
     * Returns what the server's administration API reports about its contexts, which is where a run reads the
     * evidence that the context it drove exists.
     *
     * @param deployment the server to ask
     * @return the API's answer, verbatim
     */
    static String contexts(Deployment deployment) {
        return send(HttpRequest.newBuilder(URI.create(deployment.adminBase() + "/v1/public/context"))
                               .timeout(CALL_TIMEOUT)
                               .GET()
                               .build());
    }

    private static String send(HttpRequest request) {
        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() + " " + response.body().replace("\n", " ").trim();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to reach the Axon Server API at [" + request.uri() + "].", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted reaching the Axon Server API at ["
                                                    + request.uri() + "].", e);
        }
    }

    /**
     * One run's connection factory and connection.
     *
     * @author Stefan Dragisic
     * @since 5.3.0
     */
    static final class Run implements AutoCloseable {

        private final AxonServerConnectionFactory factory;
        private final AxonServerConnection connection;

        private Run(AxonServerConnectionFactory factory, AxonServerConnection connection) {
            this.factory = factory;
            this.connection = connection;
        }

        /**
         * Connects to the run's context.
         * <p>
         * {@code forceReconnectViaRoutingServers(true)} is not a tuning knob here. Axon Server hands a client the
         * address it advertises for itself, and on a container that address is neither the mapped port the run dialled
         * nor the proxy the chaos arm dialled -- so a client that reconnected to the advertised address would come back
         * to a place the run cannot reach, or would come back <em>around</em> the proxy a partition is being made with.
         * Forcing every reconnect back through the address the run dialled is what makes a reconnect observable at all.
         */
        static Run create(Deployment deployment) {
            AxonServerConnectionFactory factory =
                    AxonServerConnectionFactory.forClient("hunt-" + RUNS.incrementAndGet())
                                               .routingServers(new ServerAddress(deployment.grpcHost(),
                                                                                 deployment.grpcPort()))
                                               .forceReconnectViaRoutingServers(true)
                                               .connectTimeout(30, TimeUnit.SECONDS)
                                               .reconnectInterval(500, TimeUnit.MILLISECONDS)
                                               .build();
            return new Run(factory, factory.connect(CONTEXT));
        }

        AxonServerConnection connection() {
            return connection;
        }

        @Override
        public void close() {
            try {
                connection.disconnect();
            } finally {
                factory.shutdown();
            }
        }
    }

    /**
     * The same Axon Server, in a container of its own behind a proxy, so that a run may cut it, kill it and freeze it.
     * <p>
     * The only differences from {@link AxonServerHuntBackend} are where the server lives and that it can be broken, so a
     * divergence between this arm and the shared one is attributable to what was done to the infrastructure and to
     * nothing else. A fault that kills the process cannot be aimed at a container the rest of the matrix is connected
     * to, which is why this is a second deployment rather than a setting on the first.
     *
     * @author Stefan Dragisic
     * @since 5.3.0
     */
    public static final class Chaos extends AxonServerHuntBackend {

        /**
         * The name the breakable arm is selected by in a scenario record.
         */
        public static final String NAME = "axonserver-chaos";

        @Override
        public String name() {
            return NAME;
        }

        @Override
        Deployment deployment() {
            BreakableAxonServer breakable = BreakableAxonServer.Holder.INSTANCE;
            return new Deployment(breakable.grpcHost(), breakable.grpcPort(), breakable.adminBase());
        }

        @Override
        String containerId() {
            return BreakableAxonServer.Holder.INSTANCE.containerId();
        }

        @Override
        public StoreInfrastructure infrastructure(EventStorageEngine engine) {
            return BreakableAxonServer.Holder.INSTANCE;
        }
    }

    /**
     * Returns the label a verdict from this arm must carry, so that no report of one can omit the version skew.
     *
     * @return the arm's framework version, connector version, image and shimmed method set
     */
    public static String label() {
        return "framework=" + Objects.requireNonNullElse(
                AxonServerHuntBackend.class.getPackage().getImplementationVersion(), "5.3.0-SNAPSHOT")
                + " connector=" + CONNECTOR_VERSION
                + " image=" + IMAGE
                + " shimmed=[" + SHIMMED_METHOD + "]";
    }
}
