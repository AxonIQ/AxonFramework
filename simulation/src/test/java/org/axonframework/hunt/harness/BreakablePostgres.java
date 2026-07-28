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
import org.testcontainers.postgresql.PostgreSQLContainer;

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
 * A PostgreSQL a run is allowed to break: reached through a proxy that can be cut, in a container that can be killed and
 * frozen.
 * <p>
 * <b>Why this is a second deployment rather than a setting on the first.</b> Every other arm of the matrix shares one
 * container so that a run costs a schema rather than a container start. A fault that kills the process, or that refuses
 * every connection to it, cannot be aimed at a container the rest of the matrix is connected to -- so the arms that break
 * their store get a deployment of their own, started only when a scenario asks for it.
 * <p>
 * <b>The proxy is in the path from the start, and that is what makes a partition possible at all.</b> The application's
 * JDBC URL addresses the proxy; the proxy addresses the store over a private network by alias. Cutting the proxy
 * therefore takes the network away while leaving the store running, with its state, its clock and its open transactions
 * intact -- which is the only way an acknowledgement can become genuinely ambiguous. Addressing the store directly and
 * then stopping it would be a crash wearing a partition's name.
 * <p>
 * <b>Restarting the store does not move the application's address.</b> The port the application connects to belongs to
 * the proxy, which is never killed, and the proxy re-resolves the store's alias on every new connection. That is the
 * property that makes a kill-and-restart transparent to the run's wiring, and it is the usual reason a naive
 * kill-and-restart of a mapped container does not work.
 * <p>
 * The proxy is driven over its own HTTP API and the container over the Docker command line, so every observation this
 * class reports is the infrastructure's own answer: the proxy's reported enabled state, the process's exit code, the
 * paused flag, and the recovery line the store writes on its way back up with the timestamp on it. None of it is the
 * harness reporting on itself, which is the entire point of collecting it.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
final class BreakablePostgres implements StoreInfrastructure {

    /**
     * The proxy image, pinned so that the API this class speaks is one version of one API.
     */
    static final String TOXIPROXY_IMAGE = "ghcr.io/shopify/toxiproxy:2.12.0";

    private static final String STORE_ALIAS = "hunt-store";
    private static final String PROXY_NAME = "store";
    private static final int API_PORT = 8474;
    private static final int PROXY_PORT = 8666;
    private static final int STORE_PORT = 5432;
    private static final String READY_LINE = "database system is ready to accept connections";
    private static final Duration RECOVERY_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);

    private final PostgreSQLContainer store;
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

        static final BreakablePostgres INSTANCE = new BreakablePostgres();

        private Holder() {
            // Holder.
        }
    }

    private BreakablePostgres() {
        Network network = Network.newNetwork();
        this.store = new PostgreSQLContainer(PostgresJpaHuntBackend.IMAGE)
                .withNetwork(network)
                .withNetworkAliases(STORE_ALIAS)
                // Same ceiling as the shared container: a run opens a connection per writer thread, one per processor
                // worker and one per engine read outside the run's transaction.
                .withCommand("postgres", "-c", "max_connections=400");
        this.store.start();
        this.proxy = new GenericContainer<>(TOXIPROXY_IMAGE)
                .withNetwork(network)
                .withExposedPorts(API_PORT, PROXY_PORT)
                .waitingFor(Wait.forHttp("/version").forPort(API_PORT));
        this.proxy.start();
        createProxy();
    }

    /**
     * Returns the URL the application connects on, which is the proxy's address rather than the store's.
     *
     * @return the proxied JDBC URL
     */
    String jdbcUrl() {
        return "jdbc:postgresql://" + proxy.getHost() + ":" + proxy.getMappedPort(PROXY_PORT) + "/"
                + store.getDatabaseName();
    }

    /**
     * Returns the user the store was created with.
     *
     * @return the user name
     */
    String username() {
        return store.getUsername();
    }

    /**
     * Returns the password the store was created with.
     *
     * @return the password
     */
    String password() {
        return store.getPassword();
    }

    @Override
    public Evidence interruptConnections(Duration duration) {
        List<String> facts = new ArrayList<>();
        String cut = setEnabled(false);
        facts.add("proxy after cut: " + cut);
        if (!cut.contains("\"enabled\":false")) {
            return Evidence.missed("the proxy did not report itself disabled: " + cut);
        }
        sleep(duration);
        String healed = setEnabled(true);
        facts.add("proxy after heal: " + healed);
        facts.add("cut held for " + duration.toMillis() + "ms");
        return new Evidence(true, facts);
    }

    @Override
    public Evidence kill(Duration downtime) {
        List<String> facts = new ArrayList<>();
        String id = store.getContainerId();
        docker("kill", "-s", "KILL", id);
        String exitCode = docker("inspect", "-f", "{{.State.ExitCode}}", id).trim();
        String status = docker("inspect", "-f", "{{.State.Status}}", id).trim();
        facts.add("container " + shortId(id) + " status " + status + " exit code " + exitCode);
        if (!"exited".equals(status)) {
            return Evidence.missed("the container did not exit; it reported " + status);
        }
        sleep(downtime);
        docker("start", id);
        String recovery = awaitRecoveryLine();
        facts.add("recovery line: " + recovery);
        facts.add("down for " + downtime.toMillis() + "ms");
        if (recovery.isEmpty()) {
            return Evidence.missed("the store did not report itself ready within " + RECOVERY_TIMEOUT);
        }
        return new Evidence(true, facts);
    }

    @Override
    public Evidence pause(Duration duration) {
        List<String> facts = new ArrayList<>();
        String id = store.getContainerId();
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
     * Returns the last line the store logged reporting itself ready, waiting for one to appear.
     * <p>
     * This is the only observation in this class that proves a <em>restart</em> rather than a stop, and it carries the
     * store's own timestamp, so a reader can place it inside the fault window instead of taking the harness's word for
     * when it happened.
     */
    private String awaitRecoveryLine() {
        long deadline = System.nanoTime() + RECOVERY_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            String logs = docker("logs", "--tail", "40", store.getContainerId());
            for (String line : logs.split("\n")) {
                if (line.contains(READY_LINE)) {
                    return line.trim();
                }
            }
            sleep(Duration.ofMillis(250));
        }
        return "";
    }

    private void createProxy() {
        post("/proxies", "{\"name\":\"" + PROXY_NAME + "\",\"listen\":\"0.0.0.0:" + PROXY_PORT
                + "\",\"upstream\":\"" + STORE_ALIAS + ":" + STORE_PORT + "\",\"enabled\":true}");
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
