/*
 * Copyright (c) 2010-2023. Axon Framework
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

package org.axonframework.axonserver.connector;

import org.axonframework.axonserver.connector.command.AxonServerCommandBus;
import org.axonframework.axonserver.connector.command.CommandLoadFactorProvider;
import org.axonframework.axonserver.connector.event.axon.AxonServerEventStore;
import org.axonframework.axonserver.connector.event.axon.AxonServerEventStoreFactory;
import org.axonframework.axonserver.connector.event.axon.EventProcessorInfoConfiguration;
import org.axonframework.axonserver.connector.query.AxonServerQueryBus;
import org.axonframework.axonserver.connector.utils.TestSerializer;
import org.axonframework.commandhandling.CommandBus;
import org.axonframework.common.ReflectionUtils;
import org.axonframework.config.Configuration;
import org.axonframework.config.DefaultConfigurer;
import org.axonframework.eventhandling.GenericEventMessage;
import org.axonframework.messaging.Message;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link ServerConnectorConfigurerModule}.
 *
 * @author Allard Buijze
 */
class ServerConnectorConfigurerModuleTest {

    private Configuration testSubject;

    @AfterEach
    void tearDown() {
        if (testSubject != null) {
            testSubject.shutdown();
        }
    }

    @Test
    void axonServerConfiguredInDefaultConfiguration() {
        testSubject = DefaultConfigurer.defaultConfiguration()
                                       .configureSerializer(c -> TestSerializer.xStreamSerializer())
                                       .buildConfiguration();

        AxonServerConfiguration resultAxonServerConfig = testSubject.getComponent(AxonServerConfiguration.class);

        assertEquals("localhost", resultAxonServerConfig.getServers());
        assertNotNull(resultAxonServerConfig.getClientId());
        assertNotNull(resultAxonServerConfig.getComponentName());
        assertTrue(resultAxonServerConfig.getComponentName().contains(resultAxonServerConfig.getClientId()));

        assertNotNull(testSubject.getComponent(AxonServerConnectionManager.class));
        assertTrue(
                testSubject.getModules().stream()
                           .anyMatch(moduleConfig -> moduleConfig.isType(EventProcessorInfoConfiguration.class))
        );
        assertTrue(testSubject.eventStore() instanceof AxonServerEventStore);
        assertTrue(testSubject.commandBus() instanceof AxonServerCommandBus);
        assertTrue(testSubject.queryBus() instanceof AxonServerQueryBus);
        assertNotNull(testSubject.getComponent(AxonServerEventStoreFactory.class));

        //noinspection unchecked
        TargetContextResolver<Message<?>> resultTargetContextResolver =
                testSubject.getComponent(TargetContextResolver.class);
        assertNotNull(resultTargetContextResolver);
        // The default TargetContextResolver is a no-op which returns null
        assertNull(resultTargetContextResolver.resolveContext(GenericEventMessage.asEventMessage("some-event")));
    }

    @Test
    void queryUpdateEmitterIsTakenFromConfiguration() {
        testSubject = DefaultConfigurer.defaultConfiguration()
                                       .configureSerializer(c -> TestSerializer.xStreamSerializer())
                                       .buildConfiguration();

        assertTrue(testSubject.queryBus() instanceof AxonServerQueryBus);
        assertSame(testSubject.queryBus().queryUpdateEmitter(), testSubject.queryUpdateEmitter());
        assertSame(((AxonServerQueryBus) testSubject.queryBus()).localSegment().queryUpdateEmitter(),
                   testSubject.queryUpdateEmitter());
    }

    @Test
    void customCommandLoadFactorProvider() throws NoSuchFieldException {
        CommandLoadFactorProvider expected = command -> 5000;
        testSubject = DefaultConfigurer.defaultConfiguration()
                                       .configureSerializer(c -> TestSerializer.xStreamSerializer())
                                       .registerComponent(CommandLoadFactorProvider.class, c -> expected)
                                       .buildConfiguration();

        CommandBus commandBus = testSubject.commandBus();
        assertTrue(commandBus instanceof AxonServerCommandBus);
        CommandLoadFactorProvider result = ReflectionUtils.getFieldValue(
                AxonServerCommandBus.class.getDeclaredField("loadFactorProvider"), commandBus
        );
        assertEquals(expected, result);
    }

    @Test
    void noRegisteredTopologyChangeListenerInvokesConnectionManagerGetConnectionOnce() {
        Configuration config =
                DefaultConfigurer.defaultConfiguration()
                                 .configureSerializer(c -> TestSerializer.xStreamSerializer())
                                 .configureLifecyclePhaseTimeout(25, TimeUnit.MILLISECONDS)
                                 .registerComponent(AxonServerConnectionManager.class, c -> {
                                     AxonServerConnectionManager connectionManager =
                                             AxonServerConnectionManager.builder()
                                                                        .axonServerConfiguration(c.getComponent(
                                                                                AxonServerConfiguration.class
                                                                        ))
                                                                        .build();
                                     return spy(connectionManager);
                                 })
                                 .buildConfiguration();

        // Retrieving the CommandBus ensure the AxonServerCommandBus triggers "a" start of the AxonServerConnectionManager.
        config.commandBus();
        config.start();

        AxonServerConnectionManager connectionManager = config.getComponent(AxonServerConnectionManager.class);
        assertNotNull(connectionManager);
        await().pollDelay(Duration.ofMillis(50))
               .atMost(Duration.ofMillis(500))
               .untilAsserted(() -> verify(connectionManager).getConnection());

        config.shutdown();
    }

    /**
     * This test would ideally verify a {@link TopologyChangeListener} got registered with the
     * {@link io.axoniq.axonserver.connector.control.ControlChannel} of the default context of the
     * {@link AxonServerConnectionManager}. However, this test class is not set up to spin an actual Axon Server
     * instance. Hence, I have decided to only verify if the {@link AxonServerConnectionManager#getConnection()} was
     * invoked twice, where the first occurrence comes from the {@link AxonServerCommandBus} and the second from
     * registering the {@code TopologyChangeListener}.
     */
    @Test
    void registeredTopologyChangeListenerInvokesConnectionManagerGetConnectionTwice() {
        Configuration config =
                DefaultConfigurer.defaultConfiguration()
                                 .configureSerializer(c -> TestSerializer.xStreamSerializer())
                                 .configureLifecyclePhaseTimeout(25, TimeUnit.MILLISECONDS)
                                 .registerComponent(TopologyChangeListener.class,
                                                    c -> mock(TopologyChangeListener.class))
                                 .registerComponent(AxonServerConnectionManager.class, c -> {
                                     AxonServerConnectionManager connectionManager =
                                             AxonServerConnectionManager.builder()
                                                                        .axonServerConfiguration(c.getComponent(
                                                                                AxonServerConfiguration.class
                                                                        ))
                                                                        .build();
                                     return spy(connectionManager);
                                 })
                                 .buildConfiguration();

        // Retrieving the CommandBus ensure the AxonServerCommandBus triggers "a" start of the AxonServerConnectionManager.
        config.commandBus();
        config.start();

        AxonServerConnectionManager connectionManager = config.getComponent(AxonServerConnectionManager.class);
        assertNotNull(connectionManager);
        await().pollDelay(Duration.ofMillis(50))
               .atMost(Duration.ofMillis(500))
               .untilAsserted(() -> verify(connectionManager, times(2)).getConnection());

        config.shutdown();
    }
}
