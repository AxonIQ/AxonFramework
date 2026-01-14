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

package org.axonframework.extension.micronaut.connection;

import io.axoniq.axonserver.connector.AxonServerConnection;
import org.axonframework.axonserver.connector.AxonServerConnectionManager;
import org.axonframework.extension.micronaut.service.connection.AxonServerConnectionDetails;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

class SpringBootDockerComposeIntegrationTest {

    private ConfigurableApplicationContext application;

    @BeforeEach
    void setUp() {
        application = SpringApplication.run(SpringBootApplication.class,
                                            "--spring.docker.compose.file=test-docker-compose.yml",
                                            "--spring.docker.compose.skip.in-tests=false");
    }

    @AfterEach
    void tearDown() {
        application.stop();
    }

    @Test
    void verifyApplicationRunsAndConnectsToAxonServerDefinedInDockerComposeFile() {
        assertTrue(application.isRunning());

        assertNotNull(application.getBean(AxonServerConnectionDetails.class),
                                 "Expected an AxonServerConnectionDetails bean pointing to Axon Server in Docker");

        AxonServerConnectionManager connectionFactory = application.getBean(AxonServerConnectionManager.class);
        AxonServerConnection connection = connectionFactory.getConnection();

        await().atMost(Duration.ofSeconds(5))
               .untilAsserted(() -> assertTrue(connection.isConnected()));
    }
}
