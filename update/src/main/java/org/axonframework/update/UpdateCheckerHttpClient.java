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

import org.axonframework.common.annotation.Internal;
import org.axonframework.update.api.UpdateCheckRequest;
import org.axonframework.update.api.UpdateCheckResponse;
import org.axonframework.update.configuration.UsagePropertyProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;


/**
 * Client for checking for update and sending anonymous usage data to the Axoniq servers. This client uses the
 * {@link UsagePropertyProvider} to determine the URL to send the data to.
 *
 * @author Mitchell Herrijgers
 * @since 5.0.0
 */
@Internal
public class UpdateCheckerHttpClient {

    private final static Logger logger = LoggerFactory.getLogger(UpdateCheckerHttpClient.class);

    private final HttpClient client;
    private final UsagePropertyProvider userProperties;

    /**
     * Creates a new {@code UpdateCheckerHttpClient} with the given {@link UsagePropertyProvider}. The client will use
     * the properties to determine the URL to send the usage data to.
     *
     * @param userProperties The {@link UsagePropertyProvider} to use for retrieving the URL and other properties.
     */
    public UpdateCheckerHttpClient(UsagePropertyProvider userProperties) {
        this.userProperties = Objects.requireNonNull(userProperties, "The userProperties must not be null.");
        this.client = HttpClient.newBuilder()
                                .followRedirects(HttpClient.Redirect.ALWAYS)
                                .build();
    }

    /**
     * Sends a usage request to the Axoniq servers. If {@code firstRequest} is true, it will send a POST request,
     * otherwise it will send a PUT request.
     *
     * @param updateCheckRequest The {@link UpdateCheckRequest} to send.
     * @param firstRequest Whether this is the first request or not.
     * @return An {@link Optional} containing the {@link UpdateCheckResponse} if the request was successful, or empty if it
     * failed.
     */
    public Optional<UpdateCheckResponse> sendRequest(UpdateCheckRequest updateCheckRequest, boolean firstRequest) {
        String url = userProperties.getUrl() + "?" + updateCheckRequest.toQueryString();

        try {
            logger.debug("Reporting anonymous usage data to Axoniq servers at: {}", url);
            HttpRequest request = HttpRequest
                    .newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .headers("User-Agent", updateCheckRequest.toUserAgent())
                    .headers("X-Machine-Id", updateCheckRequest.machineId())
                    .headers("X-Instance-Id", updateCheckRequest.instanceId())
                    .headers("X-Uptime", String.valueOf(ManagementFactory.getRuntimeMXBean().getUptime()))
                    .headers("X-First-Run", firstRequest ? "true" : "false")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                logger.info("Failed to report anonymous usage data, received status code: {}", response.statusCode());
                return Optional.empty();
            }
            logger.debug("Reported anonymous usage data successfully, received response: {}", response.body());
            return Optional.of(UpdateCheckResponse.fromRequest(response.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Failed to report anonymous usage data. Thread interrupted.", e);
            return Optional.empty();
        } catch (Exception e) {
            logger.warn("Failed to report anonymous usage data", e);
            return Optional.empty();
        }
    }
}
