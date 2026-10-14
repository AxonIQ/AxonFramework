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

package org.axonframework.update;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.axonframework.update.api.Artifact;
import org.axonframework.update.api.UpdateCheckRequest;
import org.axonframework.update.api.UpdateCheckResponse;
import org.axonframework.update.configuration.UsagePropertyProvider;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the request the client puts on the wire, using a real HTTP server rather than a stubbed client, as the
 * assembly of the URL is the behaviour under test.
 */
class UpdateCheckerHttpClientTest {

    private static final String RESPONSE_BODY = "cd=3600";

    private HttpServer server;
    private final AtomicReference<RecordedRequest> lastRequest = new AtomicReference<>();
    private UpdateCheckerHttpClient testSubject;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/updates", this::handle);
        server.start();
        String url = "http://localhost:" + server.getAddress().getPort() + "/updates";
        testSubject = new UpdateCheckerHttpClient(new FixedUsagePropertyProvider(url));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Nested
    class SendRequest {

        @Test
        void carriesIdentifiersAndRuntimeValuesAsQueryParameters() {
            // given
            UpdateCheckRequest request = request("machine-user-name");

            // when
            testSubject.sendRequest(request, true);

            // then
            Map<String, String> parameters = lastRequest.get().queryParameters();
            assertThat(parameters).containsEntry("machine-id", "machine-1234")
                                  .containsEntry("machine-user-name", "machine-user-name")
                                  .containsEntry("instance-id", "instance-5678")
                                  .containsEntry("first-run", "true")
                                  .containsEntry("os", "Linux; 6.11.0-26-generic; amd64")
                                  .containsEntry("lib-fw.axon-messaging", "5.0.1");
            assertThat(Long.parseLong(parameters.get("uptime"))).isNotNegative();
        }

        @Test
        void firstRunParameterReflectsTheGivenFlag() {
            // given
            UpdateCheckRequest request = request("machine-user-name");

            // when
            testSubject.sendRequest(request, false);

            // then
            assertThat(lastRequest.get().queryParameters()).containsEntry("first-run", "false");
        }

        @Test
        void machineUserNameSurvivesEncodingRoundTrip() {
            // given a user name that cannot travel unencoded
            UpdateCheckRequest request = request("John Doe");

            // when
            testSubject.sendRequest(request, true);

            // then
            assertThat(lastRequest.get().queryParameters()).containsEntry("machine-user-name", "John Doe");
        }

        @Test
        void sendsNoCustomHeaders() {
            // given
            UpdateCheckRequest request = request("machine-user-name");

            // when
            testSubject.sendRequest(request, true);

            // then the endpoint is fronted by a service that drops custom headers, so none may be relied upon
            RecordedRequest recorded = lastRequest.get();
            assertThat(recorded.headerNames()).noneMatch(name -> name.toLowerCase().startsWith("x-"));
            assertThat(recorded.userAgent()).startsWith("Axoniq UpdateChecker/5.0.1");
        }

        @Test
        void returnsTheParsedResponse() {
            // given
            UpdateCheckRequest request = request("machine-user-name");

            // when
            Optional<UpdateCheckResponse> response = testSubject.sendRequest(request, true);

            // then
            assertThat(response).isPresent();
            assertThat(response.get().checkInterval()).isEqualTo(3600);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        lastRequest.set(new RecordedRequest(exchange.getRequestURI().getRawQuery(),
                                            List.copyOf(exchange.getRequestHeaders().keySet()),
                                            exchange.getRequestHeaders().getFirst("User-Agent")));
        byte[] body = RESPONSE_BODY.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(body);
        }
    }

    private static UpdateCheckRequest request(String machineUserName) {
        return new UpdateCheckRequest("machine-1234",
                                      machineUserName,
                                      "instance-5678",
                                      "Linux",
                                      "6.11.0-26-generic",
                                      "amd64",
                                      "17.0.2",
                                      "AdoptOpenJDK",
                                      "1.8.22",
                                      List.of(new Artifact("org.axonframework", "axon-messaging", "5.0.1")));
    }

    private record RecordedRequest(String rawQuery, List<String> headerNames, String userAgent) {

        private Map<String, String> queryParameters() {
            Map<String, String> parameters = new HashMap<>();
            for (String parameter : rawQuery.split("&")) {
                int separatorIndex = parameter.indexOf('=');
                parameters.put(URLDecoder.decode(parameter.substring(0, separatorIndex), StandardCharsets.UTF_8),
                               URLDecoder.decode(parameter.substring(separatorIndex + 1), StandardCharsets.UTF_8));
            }
            return parameters;
        }
    }

    private record FixedUsagePropertyProvider(String url) implements UsagePropertyProvider {

        @Override
        public Boolean getDisabled() {
            return false;
        }

        @Override
        public String getUrl() {
            return url;
        }

        @Override
        public int priority() {
            return 0;
        }
    }
}
