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
import java.util.concurrent.TimeUnit;

/**
 * An Axon Server a run is allowed to break: reached through a proxy that can be cut, in a container that can be killed
 * and frozen.
 * <p>
 * <b>Why a second deployment rather than a setting on the first.</b> Every other arm of the matrix shares one server so
 * that a run costs a purge rather than a container start. A fault that kills the process, or refuses every connection to
 * it, cannot be aimed at a container the rest of the matrix is connected to -- so the arms that break their store get a
 * deployment of their own, started only when a scenario asks for it.
 * <p>
 * <b>The proxy carries the gRPC traffic and not the administration traffic, and that split is load-bearing.</b> The
 * connector dials the proxy; the proxy addresses the server by network alias on a private network. Cutting the proxy
 * therefore takes the application's network away while the server keeps its state, its clock and its open streams, which
 * is the only shape in which an acknowledgement becomes genuinely ambiguous. The administration API is addressed
 * directly, so a run can still empty the context and read the server's own reported state while the application cannot
 * reach it at all -- which is what makes a durability question answerable at the moment it matters.
 * <p>
 * <b>Restarting the server does not move the application's address.</b> The port the connector dials belongs to the
 * proxy, which is never killed, and the proxy re-resolves the server's alias on every new connection. That plus
 * {@code forceReconnectViaRoutingServers} on the connector -- Axon Server otherwise hands a client the address it
 * advertises for itself, which is a container-internal name -- is what makes a kill-and-restart transparent to the run's
 * wiring, and it is the usual reason a naive kill-and-restart of a mapped container does not work.
 * <p>
 * The proxy is driven over its own HTTP API and the container over the Docker command line, so every observation this
 * class reports is the infrastructure's own answer: the proxy's reported enabled state, the process's exit code, the
 * paused flag, and the line the server writes on its way back up with its own timestamp on it. None of it is the harness
 * reporting on itself, which is the entire point of collecting it.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
final class BreakableAxonServer implements StoreInfrastructure {

    /**
     * The proxy image, pinned so that the API this class speaks is one version of one API.
     */
    static final String TOXIPROXY_IMAGE = "ghcr.io/shopify/toxiproxy:2.12.0";

    private static final String SERVER_ALIAS = "hunt-axonserver";
    private static final String PROXY_NAME = "axonserver";
    private static final int API_PORT = 8474;
    private static final int PROXY_PORT = 8666;
    private static final String READY_LINE = "Started AxonServer in";
    private static final String CONTEXT_READY_LINE = "context " + AxonServerHuntBackend.CONTEXT + " created";
    private static final Duration RECOVERY_TIMEOUT = Duration.ofMinutes
            (3);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);

    private final GenericContainer<?> server;
    private final GenericContainer<?> proxy;
    private final HttpClient http = HttpClient.newBuilder()
                                              .connectTimeout(Duration.ofSeconds(5))
                                              .build();

    /**
     * The single breakable deployment, started the first time a scenario asks for it.
     * <p>
     * A holder class rather than a field, because every build of this module instantiates every registered backend and
     * starting two containers just because a class was loaded would put Docker on the critical path of a run that never
     * touches it.
     */
    static final class Holder {

        static final BreakableAxonServer INSTANCE = new BreakableAxonServer();

        private Holder() {
            // Holder.
        }
    }

    private BreakableAxonServer() {
        Network network = Network.newNetwork();
        this.server = AxonServerHuntBackend.started(network, SERVER_ALIAS);
        this.proxy = new GenericContainer<>(TOXIPROXY_IMAGE)
                .withNetwork(network)
                .withExposedPorts(API_PORT, PROXY_PORT)
                .waitingFor(Wait.forHttp("/version").forPort(API_PORT));
        this.proxy.start();
        createProxy();
    }

    /**
     * Returns the host the connector dials, which is the proxy's rather than the server's.
     *
     * @return the proxy's host
     */
    String grpcHost() {
        return proxy.getHost();
    }

    /**
     * Returns the port the connector dials, which is the proxy's rather than the server's.
     *
     * @return the proxy's mapped port
     */
    int grpcPort() {
        return proxy.getMappedPort(PROXY_PORT);
    }

    /**
     * Returns the base URL of the server's administration API, which is never proxied.
     *
     * @return the administration base URL
     */
    /**
     * Returns the base URL of the server's administration API, asking Docker for the mapping every time.
     * <p>
     * <b>A killed and restarted container does not keep its published port, and Testcontainers does not notice.</b>
     * {@code getMappedPort} answers from the binding Testcontainers cached when it started the container; Docker assigns a
     * fresh ephemeral port on the next {@code start}. Measured on this arm: the kill arm passed, and then every later arm
     * failed with {@code ConnectException} on its first administration call, from a port that had been correct twenty
     * seconds earlier. The gRPC side is immune because it addresses the proxy, which is never killed -- which is exactly
     * why this one had to be found the hard way.
     *
     * @return the administration base URL, resolved now rather than at start-up
     */
    String adminBase() {
        return "http://" + server.getHost() + ":" + currentAdminPort();
    }

    private int currentAdminPort() {
        String mapping = docker("port", server.getContainerId(), AxonServerHuntBackend.ADMIN_PORT + "/tcp").trim();
        for (String line : mapping.split("\n")) {
            int colon = line.lastIndexOf(':');
            if (colon > 0) {
                try {
                    return Integer.parseInt(line.substring(colon + 1).trim());
                } catch (NumberFormatException ignored) {
                    // Try the next line; docker prints one per address family.
                }
            }
        }
        // Nothing published yet, or the command failed. The cached binding is the best remaining answer and its failure
        // is self-describing.
        return server.getMappedPort(AxonServerHuntBackend.ADMIN_PORT);
    }

    /**
     * Returns the identifier of the container the server runs in, which is what a fault's landing evidence names.
     *
     * @return the server's container identifier
     */
    String containerId() {
        return server.getContainerId();
    }

    @Override
    public Evidence cutConnections() {
        String cut = setEnabled(false);
        if (!cut.contains("\"enabled\":false")) {
            return Evidence.missed("the proxy did not report itself disabled: " + cut);
        }
        return new Evidence(true, List.of("proxy after cut: " + cut));
    }

    @Override
    public Evidence healConnections() {
        return new Evidence(true, List.of("proxy after heal: " + setEnabled(true)));
    }

    @Override
    public Evidence kill(Duration downtime) {
        List<String> facts = new ArrayList<>();
        String id = server.getContainerId();
        long linesBefore = readyLines();
        docker("kill", "-s", "KILL", id);
        String exitCode = docker("inspect", "-f", "{{.State.ExitCode}}", id).trim();
        String status = docker("inspect", "-f", "{{.State.Status}}", id).trim();
        facts.add("container " + shortId(id) + " status " + status + " exit code " + exitCode);
        if (!"exited".equals(status)) {
            return Evidence.missed("the container did not exit; it reported " + status);
        }
        sleep(downtime);
        docker("start", id);
        String recovery = awaitRecoveryLine(linesBefore);
        facts.add("recovery line: " + recovery);
        facts.add("down for " + downtime.toMillis() + "ms");
        if (recovery.isEmpty()) {
            return Evidence.missed("the server did not report itself ready within " + RECOVERY_TIMEOUT);
        }
        return new Evidence(true, facts);
    }

    @Override
    public Evidence pause(Duration duration) {
        List<String> facts = new ArrayList<>();
        String id = server.getContainerId();
        docker("pause", id);
        String paused = docker("inspect", "-f", "{{.State.Status}} paused={{.State.Paused}}", id).trim();
        facts.add("container " + shortId(id) + " " + paused);
        if (!paused.contains("paused=true")) {
            docker("unpause", id);
            return Evidence.missed("the container did not report itself paused; it reported " + paused);
        }
        sleep(duration);
        docker("unpause", id);
        facts.add("frozen for " + duration.toMillis() + "ms, then "
                          + docker("inspect", "-f", "{{.State.Status}} paused={{.State.Paused}}", id).trim());
        return new Evidence(true, facts);
    }

    /**
     * Counts how many times the server has already reported itself ready, so a restart is proven by a <em>new</em>
     * line rather than by the one the original boot wrote.
     * <p>
     * The kill arm on PostgreSQL could match a fresh recovery line off the tail of the log because that store writes a
     * new one within seconds. Axon Server takes tens of seconds to come back and its boot banner stays in the log, so
     * matching the tail without counting would report the pre-kill boot as the recovery and the fault would land
     * without ever having been shown to.
     */
    private long readyLines() {
        return countReadyLines(docker("logs", server.getContainerId()));
    }

    private static long countReadyLines(String logs) {
        return logs.lines().filter(line -> line.contains(READY_LINE) || line.contains(CONTEXT_READY_LINE)).count();
    }

    /**
     * Returns the line the server logged reporting itself ready after the restart, waiting for a new one to appear.
     * <p>
     * This is the only observation in this class that proves a <em>restart</em> rather than a stop, and it carries the
     * server's own timestamp, so a reader can place it inside the fault window instead of taking the harness's word for
     * when it happened.
     */
    private String awaitRecoveryLine(long linesBefore) {
        long deadline = System.nanoTime() + RECOVERY_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            String logs = docker("logs", server.getContainerId());
            if (countReadyLines(logs) > linesBefore) {
                return logs.lines()
                           .filter(line -> line.contains(READY_LINE) || line.contains(CONTEXT_READY_LINE))
                           .reduce((first, second) -> second)
                           .orElse("")
                           .trim();
            }
            sleep(Duration.ofMillis(500));
        }
        return "";
    }

    private void createProxy() {
        post("/proxies", "{\"name\":\"" + PROXY_NAME + "\",\"listen\":\"0.0.0.0:" + PROXY_PORT
                + "\",\"upstream\":\"" + SERVER_ALIAS + ":" + AxonServerHuntBackend.GRPC_PORT
                + "\",\"enabled\":true}");
    }

    private String setEnabled(boolean enabled) {
        return post("/proxies/" + PROXY_NAME, "{\"enabled\":" + enabled + "}");
    }

    private String post(String path, String body) {
        URI uri = URI.create("http://" + proxy.getHost() + ":" + proxy.getMappedPort(API_PORT) + path);
        HttpRequest request = HttpRequest.newBuilder(uri)
                                        .header("Content-Type", "application/json")
                                        .timeout(COMMAND_TIMEOUT)
                                        .POST(HttpRequest.BodyPublishers.ofString(body))
                                        .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() + " " + response.body().replace("\n", " ").trim();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to reach the proxy API at [" + uri + "].", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reaching the proxy API at [" + uri + "].", e);
        }
    }

    /**
     * Runs one Docker command and returns everything it printed.
     * <p>
     * The command line rather than the Docker API client, because what is wanted here is exactly what an operator would
     * type and exactly what they would read back. The output is the evidence, so a failure to run the command is a
     * failure of the fault rather than something to swallow.
     */
    private static String docker(String... arguments) {
        List<String> command = new ArrayList<>(List.of("docker"));
        command.addAll(List.of(arguments));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            if (!process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("The command " + command + " did not finish within "
                                                        + COMMAND_TIMEOUT + ".");
            }
            return output;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to run " + command + ".", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running " + command + ".", e);
        }
    }

    private static String shortId(String id) {
        return id.length() > 12 ? id.substring(0, 12) : id;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(Math.max(1L, duration.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
