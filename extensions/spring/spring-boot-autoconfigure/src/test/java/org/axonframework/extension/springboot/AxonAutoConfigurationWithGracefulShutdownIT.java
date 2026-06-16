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

package org.axonframework.extension.springboot;

import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.queryhandling.GenericQueryMessage;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests whether {@link AxonConfiguration} is only shut down after processing active
 * requests if it is in graceful shutdown mode.
 *
 * @author Mateusz Nowak
 */
@EnableAutoConfiguration
@SpringBootConfiguration
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.endpoint.shutdown.access=unrestricted",
                "management.endpoints.web.exposure.include=*",
                "server.shutdown=graceful",
                "spring.lifecycle.timeout-per-shutdown-phase=5s",
                "management.endpoints.migrate-legacy-ids=true"
        }
)
class AxonAutoConfigurationWithGracefulShutdownIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private CountDownLatch requestHandlerStarted;

    @LocalServerPort
    private int port;

    @Test
    @DirtiesContext
    void whenPostForActuatorShutdownThenShuttingDownIsStarted() {
        // when
        ResponseEntity<Map<String, Object>> entity = asMapEntity(
                this.restTemplate.postForEntity("http://localhost:" + port + "/actuator/shutdown", null, Map.class));

        // then
        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(entity.getBody()).isNotNull();
        assertThat((String) entity.getBody().get("message")).contains("Shutting down");
    }

    @Test
    @DirtiesContext
    void givenActiveRequestWhenTriggerShutdownThenWaitingForRequestsToComplete() throws Exception {
        // given
        CompletableFuture<ResponseEntity<DummyQueryResponse>> requestActiveDuringShutdown = CompletableFuture.supplyAsync(
                () -> restTemplate.getForEntity("http://localhost:" + port + "/dummy", DummyQueryResponse.class));
        assertThat(requestHandlerStarted.await(2, TimeUnit.SECONDS)).isTrue();

        // when
        ResponseEntity<Void> shutdownResponse = this.restTemplate.postForEntity(
                "http://localhost:" + port + "/actuator/shutdown", null, Void.class);
        assertThat(shutdownResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // then
        ResponseEntity<DummyQueryResponse> requestStartedBeforeShutdownResponse = requestActiveDuringShutdown.get(2,
                                                                                                                  TimeUnit.SECONDS);
        assertThat(requestStartedBeforeShutdownResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(requestStartedBeforeShutdownResponse.getBody()).isNotNull();
        assertThat(requestStartedBeforeShutdownResponse.getBody().getValue()).isEqualTo("Successful response!");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static <K, V> ResponseEntity<Map<K, V>> asMapEntity(ResponseEntity<Map> entity) {
        return (ResponseEntity) entity;
    }

    @Configuration
    static class TestConfig {

        @Bean
        public CountDownLatch requestHandlerStarted() {
            return new CountDownLatch(1);
        }

        @Bean
        public TestRestTemplate testRestTemplate() {
            return new TestRestTemplate(new RestTemplateBuilder()
                                                .connectTimeout(Duration.ofSeconds(5))
                                                .readTimeout(Duration.ofSeconds(5)));
        }

        @Component
        static class DummyQueryHandler {

            @QueryHandler(queryName = "dummy")
            DummyQueryResponse handle(DummyQuery query) {
                return new DummyQueryResponse("Successful response!");
            }
        }

        @RestController
        static class DummyController {

            private static final Logger logger = LoggerFactory.getLogger(DummyController.class);

            private final QueryGateway queryGateway;
            private final CountDownLatch requestHandlerStarted;

            public DummyController(QueryGateway queryGateway, CountDownLatch requestHandlerStarted) {
                this.queryGateway = queryGateway;
                this.requestHandlerStarted = requestHandlerStarted;
            }

            @GetMapping("/dummy")
            ResponseEntity<?> dummyQuery() throws InterruptedException {
                requestHandlerStarted.countDown();
                logger.info("GRACEFUL SHUTDOWN TEST | Before sleep...");
                Thread.sleep(1000);
                logger.info("GRACEFUL SHUTDOWN TEST | After sleep...");
                var queryMessage = new GenericQueryMessage(new MessageType(new QualifiedName("dummy")),
                                                           new DummyQuery());
                try {
                    var resultOpt = queryGateway.query(queryMessage, DummyQueryResponse.class, null);
                    var result = resultOpt.get(1, TimeUnit.SECONDS);
                    logger.info("GRACEFUL SHUTDOWN TEST | Query executed!");
                    return ResponseEntity.ok(result);
                } catch (Exception e) {
                    logger.error("GRACEFUL SHUTDOWN TEST | error", e);
                    return ResponseEntity.internalServerError().build();
                }
            }
        }
    }

    record DummyQuery() {

    }

    static class DummyQueryResponse {

        private String value;

        public DummyQueryResponse() {
        }

        public DummyQueryResponse(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }
}
