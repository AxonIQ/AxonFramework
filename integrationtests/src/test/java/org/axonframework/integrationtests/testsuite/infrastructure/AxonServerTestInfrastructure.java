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

import io.axoniq.axonserver.connector.AxonServerConnection;
import io.axoniq.axonserver.connector.AxonServerConnectionFactory;
import io.axoniq.axonserver.connector.impl.ServerAddress;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.jspecify.annotations.Nullable;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * A {@link TestInfrastructure} that puts the event store in a real Axon Server, reached through the published connector.
 * <p>
 * Until this class existed, every integration test in the suite ran against either the framework's in-heap components or
 * the aggregate-based JPA engine. Neither is a persistent store speaking the Dynamic Consistency Boundary protocol, so a
 * suite whose assertions are about a boundary over several tags had nothing that could exercise them anywhere the events
 * outlive the process. This is that store.
 * <p>
 * <b>The arm's exact label, because a result from it is unreadable without one.</b> Framework: this reactor. Connector:
 * {@value #CONNECTOR_COORDINATES}, pinned in {@code integrationtests/pom.xml}. Server: {@value #IMAGE}. One method is
 * supplied by the harness rather than by the connector --
 * {@code EventStorageEngine.source(SourcingCondition, ProcessingContext)}, see
 * {@link ContextCarryingAxonServerEngine} -- because no published connector implements it.
 * {@code formal/CONNECTOR-COMPATIBILITY.md} records which connector versions are usable and what each shim models.
 * <p>
 * <b>What this arm covers and what it does not.</b> It replaces the <em>event store</em> and nothing else: the
 * connector's configuration enhancer is disabled, so commands and queries stay on the framework's local buses and the
 * token store stays the framework's default. That is deliberate -- it isolates a divergence to the event store, which is
 * what a per-backend verdict is for. Distributed command and query handling through Axon Server, server-side persistent
 * streams, and snapshots are all outside it.
 * <p>
 * <b>Isolation is a purge, and that is a licence limit rather than a choice.</b> A context per suite is what a schema per
 * suite is for PostgreSQL, and the standalone edition refuses it: {@code POST /v1/context} answers
 * {@code 403 [AXONIQ-1700] Maximum number of replication groups reached}. {@link #purgeData()} therefore empties the
 * shared Dynamic Consistency Boundary context through {@code DELETE /v1/public/purge-events}, which resets its global
 * index to zero. Two consequences follow and both matter: a test that needs a clean store must call
 * {@code AbstractIT.purgeData()}, and tests on this backend must not run in parallel with each other.
 * <p>
 * Select it for a whole run with one property and no new test classes:
 * <pre>{@code
 * ./mvnw -Pintegration-test -pl integrationtests verify -Djacoco.skip=true \
 *     -Dtest=NoSuchUnitTest -Dsurefire.failIfNoSpecifiedTests=false -Dhunt.backend=axonserver
 * }</pre>
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
@Internal
public final class AxonServerTestInfrastructure implements TestInfrastructure {

    /**
     * The image every arm runs, pinned so that a differential is against one version of one store.
     */
    public static final String IMAGE = "docker.axoniq.io/axoniq/axonserver:2026.0.0";

    /**
     * The connector coordinates this arm links against, declared in this module's POM.
     */
    public static final String CONNECTOR_COORDINATES = "io.axoniq.framework:axon-server-connector";

    /**
     * Fully-qualified name of the connector's configuration enhancer, disabled by name so that the arm replaces the
     * event store and leaves the command and query buses local.
     */
    private static final String AXON_SERVER_ENHANCER =
            "io.axoniq.framework.axonserver.connector.configuration.AxonServerConfigurationEnhancer";

    private static final String CONTEXT = "default";
    private static final String DCB_CONTEXT_LINE = "Creating DCB context: " + CONTEXT;
    private static final int GRPC_PORT = 8124;
    private static final int ADMIN_PORT = 8024;
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(60);

    private @Nullable AxonServerConnectionFactory factory;
    private @Nullable AxonServerConnection connection;

    @Override
    public synchronized void start() {
        if (connection != null) {
            return;
        }
        GenericContainer<?> container = Container.INSTANCE;
        factory = AxonServerConnectionFactory.forClient("axon-integrationtests")
                                            .routingServers(new ServerAddress(container.getHost(),
                                                                              container.getMappedPort(GRPC_PORT)))
                                            // The server advertises a container-internal address for itself, which is
                                            // not reachable from the build. Forcing every reconnect back through the
                                            // address the run dialled is what keeps the connection usable.
                                            .forceReconnectViaRoutingServers(true)
                                            .connectTimeout(30, TimeUnit.SECONDS)
                                            .build();
        connection = factory.connect(CONTEXT);
    }

    @Override
    public void configureInfrastructure(ComponentRegistry registry) {
        // Disabled so that the arm substitutes the event store and nothing else. Letting the enhancer run would also
        // replace the command and query buses with distributed ones and would register the connector's own storage
        // engine, which cannot be loaded against this reactor -- see ContextCarryingAxonServerEngine.
        registry.disableEnhancer(AXON_SERVER_ENHANCER);
        AxonServerConnection live = java.util.Objects.requireNonNull(connection, "start() must run first.");
        registry.registerComponent(EventStorageEngine.class,
                                   configuration -> new ContextCarryingAxonServerEngine(
                                           live, configuration.getComponent(EventConverter.class)));
    }

    @Override
    public void purgeData() {
        // Called before the application configuration exists, so it talks to the server's administration API rather than
        // to any component.
        send(HttpRequest.newBuilder(URI.create(adminBase() + "/v1/public/purge-events?context=" + CONTEXT))
                        .timeout(CALL_TIMEOUT)
                        .DELETE()
                        .build());
    }

    @Override
    public void stop() {
        // Nothing to release. The container is static and shared by every test in the virtual machine, and this hook runs
        // after every test method rather than after the class, so closing the connection here would leave the next test
        // without a store.
    }

    private static String adminBase() {
        return "http://" + Container.INSTANCE.getHost() + ":" + Container.INSTANCE.getMappedPort(ADMIN_PORT);
    }

    private static void send(HttpRequest request) {
        try {
            HttpResponse<String> response = HttpClient.newBuilder()
                                                      .connectTimeout(Duration.ofSeconds(10))
                                                      .build()
                                                      .send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Axon Server refused [" + request.uri() + "]: " + response.statusCode()
                                                        + " " + response.body());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to reach Axon Server at [" + request.uri() + "].", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted reaching Axon Server at [" + request.uri() + "].", e);
        }
    }

    /**
     * The one container every arm shares, started the first time an arm asks for it.
     * <p>
     * A holder class rather than a field, so that constructing an infrastructure -- which the backend selector does while
     * resolving a name -- does not put Docker on the critical path of a build that runs no Axon Server test.
     * <p>
     * The readiness signal is the server's own {@value #DCB_CONTEXT_LINE} log line rather than the health endpoint. The
     * endpoint reports its sub-components as up before the context's replication group has elected a leader, so a
     * substring match on the health body returns a server that cannot yet serve.
     */
    private static final class Container {

        private static final GenericContainer<?> INSTANCE = started();

        private static GenericContainer<?> started() {
            GenericContainer<?> container = new GenericContainer<>(IMAGE)
                    .withExposedPorts(ADMIN_PORT, GRPC_PORT)
                    // Creates the built-in _admin and default contexts on first boot and makes default a Dynamic
                    // Consistency Boundary context. Without it the server comes up with no context a boundary store can
                    // append to.
                    .withEnv("AXONIQ_AXONSERVER_STANDALONE_DCB", "true")
                    .withEnv("AXONIQ_AXONSERVER_DEVMODE_ENABLED", "true")
                    .waitingFor(Wait.forLogMessage(".*" + DCB_CONTEXT_LINE + ".*\\n", 1)
                                    .withStartupTimeout(Duration.ofMinutes(3)))
                    .withReuse(true);
            container.start();
            return container;
        }

        private Container() {
            // Holder.
        }
    }
}
