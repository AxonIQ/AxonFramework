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

package org.axonframework.axonserver.connector.event.axon;

import org.axonframework.axonserver.connector.AxonServerConfiguration;
import org.axonframework.axonserver.connector.AxonServerConnectionManager;
import org.axonframework.common.AxonConfigurationException;
import org.axonframework.eventhandling.AbstractEventBus;
import org.axonframework.eventhandling.DefaultEventBusSpanFactory;
import org.axonframework.eventhandling.EventBusSpanFactory;
import org.axonframework.eventhandling.EventMessage;
import org.axonframework.eventsourcing.eventstore.AbstractEventStorageEngine;
import org.axonframework.eventsourcing.eventstore.AbstractEventStore;
import org.axonframework.eventsourcing.snapshotting.SnapshotFilter;
import org.axonframework.monitoring.MessageMonitor;
import org.axonframework.monitoring.NoOpMessageMonitor;
import org.axonframework.serialization.Serializer;
import org.axonframework.serialization.TestSerializer;
import org.axonframework.serialization.upcasting.event.EventUpcaster;
import org.axonframework.serialization.upcasting.event.EventUpcasterChain;
import org.axonframework.tracing.NoOpSpanFactory;
import org.junit.jupiter.api.*;

import static org.axonframework.common.ReflectionUtils.getFieldValue;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link AxonServerEventStoreFactory}.
 *
 * @author Steven van Beelen
 */
class AxonServerEventStoreFactoryTest {

    private static final String TEST_CONTEXT = "my-context";

    private AxonServerConfiguration configuration;
    private AxonServerConnectionManager connectionManager;
    private Serializer snapshotSerializer;
    private Serializer eventSerializer;
    private SnapshotFilter snapshotFilter;
    private EventUpcaster upcasterChain;
    private MessageMonitor<? super EventMessage<?>> messageMonitor;
    private EventBusSpanFactory spanFactory;

    private AxonServerEventStoreFactory testSubject;

    @BeforeEach
    void setUp() {
        configuration = new AxonServerConfiguration();
        connectionManager = AxonServerConnectionManager.builder()
                                                       .axonServerConfiguration(configuration)
                                                       .build();
        snapshotSerializer = TestSerializer.XSTREAM.getSerializer();
        eventSerializer = TestSerializer.JACKSON.getSerializer();
        snapshotFilter = SnapshotFilter.allowAll();
        upcasterChain = new EventUpcasterChain();
        messageMonitor = NoOpMessageMonitor.INSTANCE;
        spanFactory = DefaultEventBusSpanFactory.builder()
                                                .spanFactory(NoOpSpanFactory.INSTANCE)
                                                .build();

        testSubject = AxonServerEventStoreFactory.builder()
                                                 .configuration(configuration)
                                                 .connectionManager(connectionManager)
                                                 .snapshotSerializer(snapshotSerializer)
                                                 .eventSerializer(eventSerializer)
                                                 .snapshotFilter(snapshotFilter)
                                                 .upcasterChain(upcasterChain)
                                                 .messageMonitor(messageMonitor)
                                                 .spanFactory(spanFactory)
                                                 .build();
    }

    @AfterEach
    void tearDown() {
        connectionManager.shutdown();
    }

    @Test
    void constructForContextUsesFactoryComponents() throws NoSuchFieldException {
        AxonServerEventStore result = testSubject.constructFor(TEST_CONTEXT);

        AxonServerEventStore.AxonIQEventStorageEngine storageEngine =
                getFieldValue(AbstractEventStore.class.getDeclaredField("storageEngine"), result);

        String resultContext = getFieldValue(
                AxonServerEventStore.AxonIQEventStorageEngine.class.getDeclaredField("context"), storageEngine
        );
        assertEquals(TEST_CONTEXT, resultContext);
        AxonServerConfiguration resultConfiguration = getFieldValue(
                AxonServerEventStore.AxonIQEventStorageEngine.class.getDeclaredField("configuration"), storageEngine
        );
        assertEquals(configuration, resultConfiguration);
        AxonServerConnectionManager resultConnectionManager = getFieldValue(
                AxonServerEventStore.AxonIQEventStorageEngine.class.getDeclaredField("connectionManager"), storageEngine
        );
        assertEquals(connectionManager, resultConnectionManager);

        Serializer resultSnapshotSerializer =
                getFieldValue(AbstractEventStorageEngine.class.getDeclaredField("snapshotSerializer"), storageEngine);
        assertEquals(snapshotSerializer, resultSnapshotSerializer);
        Serializer resultEventSerializer =
                getFieldValue(AbstractEventStorageEngine.class.getDeclaredField("eventSerializer"), storageEngine);
        assertEquals(eventSerializer, resultEventSerializer);
        EventUpcaster resultUpcasterChain =
                getFieldValue(AbstractEventStorageEngine.class.getDeclaredField("upcasterChain"), storageEngine);
        assertEquals(upcasterChain, resultUpcasterChain);
        SnapshotFilter resultSnapshotFilter =
                getFieldValue(AbstractEventStorageEngine.class.getDeclaredField("snapshotFilter"), storageEngine);
        assertEquals(snapshotFilter, resultSnapshotFilter);

        MessageMonitor<? super EventMessage<?>> resultMessageMonitor =
                getFieldValue(AbstractEventBus.class.getDeclaredField("messageMonitor"), result);
        assertEquals(messageMonitor, resultMessageMonitor);
        EventBusSpanFactory resultSpanFactory = getFieldValue(AbstractEventBus.class.getDeclaredField("spanFactory"),
                                                              result);
        assertEquals(spanFactory, resultSpanFactory);
    }

    @Test
    void constructForContextUsesCustomizations() throws NoSuchFieldException {
        Serializer expectedSerializer = mock(Serializer.class);
        AxonServerEventStoreFactory.AxonServerEventStoreConfiguration customization =
                builder -> builder.snapshotSerializer(expectedSerializer).eventSerializer(expectedSerializer);

        AxonServerEventStore result = testSubject.constructFor(TEST_CONTEXT, customization);

        AxonServerEventStore.AxonIQEventStorageEngine storageEngine =
                getFieldValue(AbstractEventStore.class.getDeclaredField("storageEngine"), result);

        String resultContext = getFieldValue(
                AxonServerEventStore.AxonIQEventStorageEngine.class.getDeclaredField("context"), storageEngine
        );
        assertEquals(TEST_CONTEXT, resultContext);
        Serializer resultSnapshotSerializer =
                getFieldValue(AbstractEventStorageEngine.class.getDeclaredField("snapshotSerializer"), storageEngine);
        assertEquals(expectedSerializer, resultSnapshotSerializer);
        Serializer resultEventSerializer =
                getFieldValue(AbstractEventStorageEngine.class.getDeclaredField("eventSerializer"), storageEngine);
        assertEquals(expectedSerializer, resultEventSerializer);
    }

    @Test
    void buildWithoutAxonServerConfigurationThrowsAxonConfigurationException() {
        AxonServerEventStoreFactory.Builder builderTestSubject =
                AxonServerEventStoreFactory.builder()
                                           .connectionManager(connectionManager)
                                           .snapshotSerializer(snapshotSerializer)
                                           .eventSerializer(eventSerializer);

        assertThrows(AxonConfigurationException.class, builderTestSubject::build);
    }

    @Test
    void buildWithoutAxonServerConnectionManagerThrowsAxonConfigurationException() {
        AxonServerEventStoreFactory.Builder builderTestSubject =
                AxonServerEventStoreFactory.builder()
                                           .configuration(configuration)
                                           .snapshotSerializer(snapshotSerializer)
                                           .eventSerializer(eventSerializer);

        assertThrows(AxonConfigurationException.class, builderTestSubject::build);
    }

    @Test
    void buildWithoutSnapshotSerializerThrowsAxonConfigurationException() {
        AxonServerEventStoreFactory.Builder builderTestSubject =
                AxonServerEventStoreFactory.builder()
                                           .configuration(configuration)
                                           .connectionManager(connectionManager)
                                           .eventSerializer(eventSerializer);

        assertThrows(AxonConfigurationException.class, builderTestSubject::build);
    }

    @Test
    void buildWithoutEventSerializerThrowsAxonConfigurationException() {
        AxonServerEventStoreFactory.Builder builderTestSubject =
                AxonServerEventStoreFactory.builder()
                                           .configuration(configuration)
                                           .connectionManager(connectionManager)
                                           .snapshotSerializer(snapshotSerializer);

        assertThrows(AxonConfigurationException.class, builderTestSubject::build);
    }

    @Test
    void buildWithNullAxonServerConfigurationThrowsAxonConfigurationException() {
        AxonServerEventStoreFactory.Builder builderTestSubject = AxonServerEventStoreFactory.builder();

        //noinspection DataFlowIssue
        assertThrows(AxonConfigurationException.class, () -> builderTestSubject.configuration(null));
    }

    @Test
    void buildWithNullAxonServerConnectionManagerThrowsAxonConfigurationException() {
        AxonServerEventStoreFactory.Builder builderTestSubject = AxonServerEventStoreFactory.builder();

        //noinspection DataFlowIssue
        assertThrows(AxonConfigurationException.class, () -> builderTestSubject.connectionManager(null));
    }

    @Test
    void buildWithNullSnapshotSerializerThrowsAxonConfigurationException() {
        AxonServerEventStoreFactory.Builder builderTestSubject = AxonServerEventStoreFactory.builder();

        //noinspection DataFlowIssue
        assertThrows(AxonConfigurationException.class, () -> builderTestSubject.snapshotSerializer(null));
    }

    @Test
    void buildWithNullEventSerializerThrowsAxonConfigurationException() {
        AxonServerEventStoreFactory.Builder builderTestSubject = AxonServerEventStoreFactory.builder();

        //noinspection DataFlowIssue
        assertThrows(AxonConfigurationException.class, () -> builderTestSubject.eventSerializer(null));
    }

    @Test
    void buildWithNullSnapshotFilterThrowsAxonConfigurationException() {
        AxonServerEventStoreFactory.Builder builderTestSubject = AxonServerEventStoreFactory.builder();

        //noinspection DataFlowIssue
        assertThrows(AxonConfigurationException.class, () -> builderTestSubject.snapshotFilter(null));
    }

    @Test
    void buildWithNullUpcasterChainThrowsAxonConfigurationException() {
        AxonServerEventStoreFactory.Builder builderTestSubject = AxonServerEventStoreFactory.builder();

        //noinspection DataFlowIssue
        assertThrows(AxonConfigurationException.class, () -> builderTestSubject.upcasterChain(null));
    }

    @Test
    void buildWithNullMessageMonitorThrowsAxonConfigurationException() {
        AxonServerEventStoreFactory.Builder builderTestSubject = AxonServerEventStoreFactory.builder();

        //noinspection DataFlowIssue
        assertThrows(AxonConfigurationException.class, () -> builderTestSubject.messageMonitor(null));
    }

    @Test
    void buildWithNullEventBusSpanFactoryThrowsAxonConfigurationException() {
        AxonServerEventStoreFactory.Builder builderTestSubject = AxonServerEventStoreFactory.builder();

        //noinspection DataFlowIssue
        assertThrows(AxonConfigurationException.class,
                     () -> builderTestSubject.spanFactory((EventBusSpanFactory) null));
    }
}