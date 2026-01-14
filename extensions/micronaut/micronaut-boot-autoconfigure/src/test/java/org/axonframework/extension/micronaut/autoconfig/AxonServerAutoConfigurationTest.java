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

package org.axonframework.extension.micronaut.autoconfig;

import io.axoniq.axonserver.connector.AxonServerConnection;
import org.axonframework.axonserver.connector.AxonServerConfiguration;
import org.axonframework.axonserver.connector.AxonServerConfigurationEnhancer;
import org.axonframework.axonserver.connector.AxonServerConnectionManager;
import org.axonframework.axonserver.connector.ManagedChannelCustomizer;
import org.axonframework.axonserver.connector.command.AxonServerCommandBusConnector;
import org.axonframework.axonserver.connector.event.AxonServerEventStorageEngine;
import org.axonframework.messaging.commandhandling.distributed.CommandBusConnector;
import org.axonframework.messaging.commandhandling.distributed.DistributedCommandBusConfiguration;
import org.axonframework.messaging.commandhandling.distributed.PayloadConvertingCommandBusConnector;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.extension.micronaut.util.GrpcServerStub;
import org.axonframework.extension.micronaut.util.TcpUtils;
import org.axonframework.messaging.queryhandling.distributed.DistributedQueryBusConfiguration;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Test class validating that the {@link AxonServerConfigurationEnhancer} are registered and customizable when using
 * Spring Boot.
 *
 * @author Steven van Beelen
 */
class AxonServerAutoConfigurationTest {

    private ApplicationContextRunner testContext;

    @BeforeEach
    void setUp() {
        testContext = new ApplicationContextRunner()
                .withUserConfiguration(TestContext.class)
                .withPropertyValues("axon.axonserver.enabled=true");
    }

    @BeforeAll
    static void beforeAll() {
        System.setProperty("axon.axonserver.servers", GrpcServerStub.DEFAULT_HOST + ":" + TcpUtils.findFreePort());
    }

    @AfterAll
    static void afterAll() {
        System.clearProperty("axon.axonserver.servers");
    }

    @Test
    void disablingAxonServerDisabledAllAxonServerComponents() {
        testContext.withPropertyValues("axon.axonserver.enabled=false", "axon.eventstorage.jpa.polling-interval=0").run(context -> {
            assertThat(context).doesNotHaveBean(AxonServerConnectionManager.class);
            assertThat(context).doesNotHaveBean(ManagedChannelCustomizer.class);
            assertThat(context).doesNotHaveBean(AxonServerEventStorageEngine.class);
            assertThat(context).doesNotHaveBean(PayloadConvertingCommandBusConnector.class);
        });
    }

    @Test
    void axonServerConfigurationContainsApplicationIdAsComponentName() {
        String expectedComponentName = "my-awesome-app";
        testContext.withInitializer(context -> context.setId(expectedComponentName)).run(context -> {
            assertThat(context).hasSingleBean(AxonServerConfiguration.class);
            // Default name for beans from an @EnableConfigurationProperties contain their prefix.
            assertThat(context).hasBean("axon.axonserver-" + AxonServerConfiguration.class.getName());

            assertThat(context.getBean(AxonServerConfiguration.class).getComponentName())
                    .isEqualTo(expectedComponentName);
        });
    }

    @Test
    void distributedCommandBusThreadCountIsAdjustableThroughAxonServerConfiguration() {
        testContext.withPropertyValues("axon.server.command-threads=42").run(context -> {
            assertThat(context).hasSingleBean(AxonServerConfiguration.class);
            assertThat(context).hasSingleBean(DistributedCommandBusConfiguration.class);

            int numberOfThreads = context.getBean(DistributedCommandBusConfiguration.class).commandThreads();
            int commandThreads = context.getBean(AxonServerConfiguration.class).getCommandThreads();
            assertThat(numberOfThreads).isEqualTo(commandThreads);
        });
    }

    @Test
    void distributedQueryBusThreadCountIsAdjustableThroughAxonServerConfiguration() {
        testContext.withPropertyValues("axon.server.query-threads=42").run(context -> {
            assertThat(context).hasSingleBean(AxonServerConfiguration.class);
            assertThat(context).hasSingleBean(DistributedQueryBusConfiguration.class);

            int numberOfThreads = context.getBean(DistributedQueryBusConfiguration.class).queryThreads();
            int queryThreads = context.getBean(AxonServerConfiguration.class).getQueryThreads();
            assertThat(numberOfThreads).isEqualTo(queryThreads);
        });
    }

    @Test
    void defaultAxonServerComponentsArePresent() {
        testContext.run(context -> {
            assertThat(context).hasSingleBean(AxonServerConfiguration.class);
            // Default name for beans from an @EnableConfigurationProperties contain their prefix.
            assertThat(context).hasBean("axon.axonserver-" + AxonServerConfiguration.class.getName());
            assertThat(context).hasSingleBean(AxonServerConnectionManager.class);
            assertThat(context).hasBean(AxonServerConnectionManager.class.getName());
            assertThat(context).hasSingleBean(ManagedChannelCustomizer.class);
            assertThat(context).hasBean(ManagedChannelCustomizer.class.getName());
            assertThat(context).hasSingleBean(AxonServerEventStorageEngine.class);
            assertThat(context).hasBean(EventStorageEngine.class.getName());
            assertThat(context).hasSingleBean(PayloadConvertingCommandBusConnector.class);
            assertThat(context).hasBean(CommandBusConnector.class.getName());
        });
    }

    @Test
    void overrideDefaultAxonServerComponents() {
        testContext.withUserConfiguration(CustomContext.class).run(context -> {
            assertThat(context).hasSingleBean(AxonServerConnectionManager.class);
            assertThat(context).hasBean("customAxonServerConnectionManager");
            assertThat(context).hasSingleBean(ManagedChannelCustomizer.class);
            assertThat(context).hasBean("customManagedChannelCustomizer");
            assertThat(context).hasSingleBean(EventStorageEngine.class);
            assertThat(context).hasSingleBean(AxonServerEventStorageEngine.class);
            assertThat(context).hasBean("customAxonServerEventStorageEngine");
            assertThat(context).hasSingleBean(CommandBusConnector.class);
            assertThat(context).hasSingleBean(PayloadConvertingCommandBusConnector.class);
            assertThat(context).hasBean("customAxonServerCommandBusConnector");
        });
    }

    @Configuration
    @EnableAutoConfiguration
    public static class TestContext {

        @Bean(initMethod = "start", destroyMethod = "shutdown")
        public GrpcServerStub grpcServerStub(@Value("${axon.axonserver.servers}") String servers) {
            return new GrpcServerStub(Integer.parseInt(servers.split(":")[1]));
        }
    }

    @Configuration
    @EnableAutoConfiguration
    public static class CustomContext {

        @Bean
        public AxonServerConnectionManager customAxonServerConnectionManager() {
            AxonServerConnectionManager mock = mock(AxonServerConnectionManager.class);
            AxonServerConnection connectionMock = mock(AxonServerConnection.class);
            when(connectionMock.controlChannel()).thenReturn(mock());
            when(connectionMock.adminChannel()).thenReturn(mock());
            when(mock.getConnection()).thenReturn(connectionMock);
            when(mock.getConnection(anyString())).thenReturn(connectionMock);
            return mock;
        }

        @Bean
        public ManagedChannelCustomizer customManagedChannelCustomizer() {
            return mock(ManagedChannelCustomizer.class);
        }

        @Bean
        public EventStorageEngine customAxonServerEventStorageEngine() {
            return mock(AxonServerEventStorageEngine.class);
        }

        @Bean
        public CommandBusConnector customAxonServerCommandBusConnector() {
            return mock(AxonServerCommandBusConnector.class);
        }
    }
}
