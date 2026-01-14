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
import io.axoniq.axonserver.connector.control.ControlChannel;
import org.axonframework.axonserver.connector.AxonServerConfiguration;
import org.axonframework.axonserver.connector.AxonServerConnectionManager;
import org.axonframework.axonserver.connector.TopologyChangeListener;
import org.axonframework.extension.micronaut.util.GrpcServerStub;
import org.axonframework.extension.micronaut.util.TcpUtils;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ContextConfiguration;

import static org.mockito.Mockito.*;

/**
 * Autoconfiguration test class validating registration of the
 * {@link org.axonframework.axonserver.connector.TopologyChangeListener} with the
 * {@link io.axoniq.axonserver.connector.control.ControlChannel} for the default
 * {@link AxonServerConfiguration#getContext() context}.
 *
 * @author Steven van Beelen
 */
@Disabled("TODO #3496")
class TopologyChangeListenerAutoConfigurationTest {

    private ApplicationContextRunner testContext;

    @BeforeEach
    void setUp() {
        testContext = new ApplicationContextRunner();
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
    void topologyChangeListenersAreInvokedForCommandHandlerRegistration() {
        testContext.withUserConfiguration(TestContext.class).run(context -> {
            TopologyChangeListener listenerOne = context.getBean("listenerOne", TopologyChangeListener.class);
            TopologyChangeListener listenerTwo = context.getBean("listenerTwo", TopologyChangeListener.class);

            ControlChannel mockedControlChannel = context.getBean("mockedControlChannel", ControlChannel.class);

            verify(mockedControlChannel).registerTopologyChangeHandler(listenerOne);
            verify(mockedControlChannel).registerTopologyChangeHandler(listenerTwo);
        });
    }

    @ContextConfiguration
    @EnableAutoConfiguration
    private static class TestContext {

        @Bean
        public ControlChannel mockedControlChannel() {
            return mock(ControlChannel.class);
        }

        @Bean
        @Primary
        public AxonServerConnectionManager connectionManager(ControlChannel mockedControlChannel) {
            AxonServerConnection mockedConnection = mock(AxonServerConnection.class);
            when(mockedConnection.controlChannel()).thenReturn(mockedControlChannel);
            AxonServerConnectionManager mockedManager = mock(AxonServerConnectionManager.class);
            when(mockedManager.getConnection()).thenReturn(mockedConnection);
            when(mockedManager.getConnection(any())).thenReturn(mockedConnection);
            return mockedManager;
        }

        @Bean
        public TopologyChangeListener listenerOne() {
            return change -> {
                // Unimportant - only exists to validate the change listener registration.
            };
        }

        @Bean
        public TopologyChangeListener listenerTwo() {
            return change -> {
                // Unimportant - only exists to validate the change listener registration.
            };
        }

        @Bean(initMethod = "start", destroyMethod = "shutdown")
        public GrpcServerStub grpcServerStub(@Value("${axon.axonserver.servers}") String servers) {
            return new GrpcServerStub(Integer.parseInt(servers.split(":")[1]));
        }
    }
}