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

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.axonframework.axonserver.connector.AxonServerConfiguration;
import org.axonframework.axonserver.connector.AxonServerConnectionManager;
import org.axonframework.axonserver.connector.event.EventStoreImpl;
import org.axonframework.axonserver.connector.event.StubServer;
import org.axonframework.axonserver.connector.util.TcpUtil;
import org.axonframework.axonserver.connector.utils.PlatformService;
import org.axonframework.axonserver.connector.utils.TestSerializer;
import org.axonframework.common.AxonConfigurationException;
import org.axonframework.eventhandling.DefaultEventBusSpanFactory;
import org.axonframework.eventhandling.DomainEventMessage;
import org.axonframework.eventhandling.EventMessage;
import org.axonframework.eventhandling.GenericDomainEventMessage;
import org.axonframework.eventhandling.GenericEventMessage;
import org.axonframework.eventhandling.TrackingEventStream;
import org.axonframework.eventsourcing.CachingEventSourcingRepository;
import org.axonframework.eventsourcing.eventstore.DomainEventStream;
import org.axonframework.eventsourcing.eventstore.EventStoreException;
import org.axonframework.eventsourcing.snapshotting.SnapshotFilter;
import org.axonframework.messaging.Message;
import org.axonframework.messaging.unitofwork.CurrentUnitOfWork;
import org.axonframework.messaging.unitofwork.DefaultUnitOfWork;
import org.axonframework.messaging.unitofwork.UnitOfWork;
import org.axonframework.serialization.json.JacksonSerializer;
import org.axonframework.serialization.upcasting.event.ContextAwareSingleEventUpcaster;
import org.axonframework.serialization.upcasting.event.EventUpcaster;
import org.axonframework.serialization.upcasting.event.IntermediateEventRepresentation;
import org.axonframework.serialization.xml.XStreamSerializer;
import org.axonframework.tracing.NoOpSpanFactory;
import org.axonframework.tracing.TestSpanFactory;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.axonframework.axonserver.connector.utils.AssertUtils.assertWithin;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link AxonServerEventStore}
 *
 * @author Marc Gathier
 */
class AxonServerEventStoreTest {

    private static final String AGGREGATE_TYPE = "aggregateType";
    private static final String AGGREGATE_ID = "aggregateId";

    private EventStoreImpl eventStore;
    private StubServer server;

    private AxonServerConfiguration config;
    private AxonServerConnectionManager axonServerConnectionManager;
    private EventUpcaster upcasterChain;
    private TestSpanFactory testSpanFactory;

    private AxonServerEventStore testSubject;

    @BeforeEach
    void setUp() throws Exception {
        testSpanFactory = new TestSpanFactory();
        int freePort = TcpUtil.findFreePort();
        eventStore = spy(new EventStoreImpl());
        server = new StubServer(freePort, new PlatformService(freePort), eventStore);
        server.start();

        config = AxonServerConfiguration.builder()
                                        .forceReadFromLeader(false)
                                        .servers("localhost:" + server.getPort())
                                        .componentName("JUNIT")
                                        .flowControl(2, 1, 1)
                                        .build();
        axonServerConnectionManager = AxonServerConnectionManager.builder()
                                                                 .axonServerConfiguration(config)
                                                                 .build();
        upcasterChain = mock(EventUpcaster.class);
        when(upcasterChain.upcast(any())).thenAnswer(i -> i.getArgument(0));

        testSubject = AxonServerEventStore.builder()
                                          .configuration(config)
                                          .platformConnectionManager(axonServerConnectionManager)
                                          .upcasterChain(upcasterChain)
                                          .eventSerializer(JacksonSerializer.defaultSerializer())
                                          .snapshotSerializer(TestSerializer.xStreamSerializer())
                                          .snapshotFilter(SnapshotFilter.allowAll())
                                          .spanFactory(DefaultEventBusSpanFactory.builder()
                                                                                 .spanFactory(testSpanFactory)
                                                                                 .build())
                                          .build();
    }

    @AfterEach
    void tearDown() throws Exception {
        axonServerConnectionManager.shutdown();
        server.shutdown();
    }

    @Test
    void publishAndConsumeEvents() throws Exception {
        UnitOfWork<Message<?>> uow = DefaultUnitOfWork.startAndGet(null);
        EventMessage<?>[] eventMessages = {GenericEventMessage.asEventMessage("Test1"),
                GenericEventMessage.asEventMessage("Test2"),
                GenericEventMessage.asEventMessage("Test3")};
        testSubject.publish(eventMessages);
        Arrays.stream(eventMessages).forEach(e -> {
            testSpanFactory.verifySpanCompleted("EventBus.publishEvent", e);
        });
        testSpanFactory.verifyNotStarted("EventBus.commitEvents");
        uow.commit();
        testSpanFactory.verifySpanCompleted("EventBus.commitEvents");

        TrackingEventStream stream = testSubject.openStream(null);

        List<String> received = new ArrayList<>();
        while (stream.hasNextAvailable(100, TimeUnit.MILLISECONDS)) {
            received.add(stream.nextAvailable().getPayload().toString());
        }
        stream.close();

        assertEquals(Arrays.asList("Test1", "Test2", "Test3"), received);
    }

    @Test
    void queryEvents() throws Exception {
        String queryAll = "";
        boolean noLiveUpdates = false;

        UnitOfWork<Message<?>> uow = DefaultUnitOfWork.startAndGet(null);
        testSubject.publish(GenericEventMessage.asEventMessage("Test1"),
                            GenericEventMessage.asEventMessage("Test2"),
                            GenericEventMessage.asEventMessage("Test3"));
        uow.commit();

        //noinspection ConstantConditions
        QueryResultStream stream = testSubject.query(queryAll, noLiveUpdates);
        assertWithin(100, TimeUnit.MILLISECONDS, () -> assertEquals(1, eventStore.getQueryEventsRequests().size()));
        stream.close();
    }

    @Test
    void loadEventsWithMultiUpcaster() {
        reset(upcasterChain);
        when(upcasterChain.upcast(any())).thenAnswer(invocation -> {
            Stream<IntermediateEventRepresentation> si = invocation.getArgument(0);
            return si.flatMap(i -> Stream.of(i, i));
        });
        testSubject.publish(new GenericDomainEventMessage<>(AGGREGATE_TYPE, AGGREGATE_ID, 0, "Test1"),
                            new GenericDomainEventMessage<>(AGGREGATE_TYPE, AGGREGATE_ID, 1, "Test2"),
                            new GenericDomainEventMessage<>(AGGREGATE_TYPE, AGGREGATE_ID, 2, "Test3"));

        DomainEventStream actual = testSubject.readEvents(AGGREGATE_ID);
        assertTrue(actual.hasNext());
        assertEquals("Test1", actual.next().getPayload());
        assertEquals("Test1", actual.next().getPayload());
        assertEquals("Test2", actual.next().getPayload());
        assertEquals("Test2", actual.next().getPayload());
        assertEquals("Test3", actual.next().getPayload());
        assertEquals("Test3", actual.next().getPayload());
        assertFalse(actual.hasNext());
    }

    @Test
    void loadSnapshotAndEventsWithMultiUpcaster() {
        reset(upcasterChain);
        when(upcasterChain.upcast(any())).thenAnswer(invocation -> {
            Stream<IntermediateEventRepresentation> si = invocation.getArgument(0);
            return si.flatMap(i -> Stream.of(i, i));
        });
        testSubject.publish(new GenericDomainEventMessage<>(AGGREGATE_TYPE, AGGREGATE_ID, 0, "Test1"),
                            new GenericDomainEventMessage<>(AGGREGATE_TYPE, AGGREGATE_ID, 1, "Test2"),
                            new GenericDomainEventMessage<>(AGGREGATE_TYPE, AGGREGATE_ID, 2, "Test3"));
        testSubject.storeSnapshot(new GenericDomainEventMessage<>(AGGREGATE_TYPE, AGGREGATE_ID, 1, "Snapshot1"));

        // snapshot storage is async, so we need to make sure the first event is the snapshot
        assertWithin(2, TimeUnit.SECONDS, () -> {
            DomainEventStream events = testSubject.readEvents(AGGREGATE_ID);
            assertTrue(events.hasNext());
            assertEquals("Snapshot1", events.next().getPayload());
        });

        DomainEventStream actual = testSubject.readEvents(AGGREGATE_ID);
        assertTrue(actual.hasNext());
        assertEquals("Snapshot1", actual.next().getPayload());
        assertEquals("Test3", actual.next().getPayload());
        assertEquals("Test3", actual.next().getPayload());
        assertFalse(actual.hasNext());
    }

    @Test
    void loadEventsWithContextAwareUpcaster() {
        reset(upcasterChain);
        ContextAwareSingleEventUpcaster<AtomicInteger> upcaster = new ContextAwareSingleEventUpcaster<AtomicInteger>() {
            @Override
            protected boolean canUpcast(IntermediateEventRepresentation intermediateRepresentation, AtomicInteger context) {
                return true;
            }

            @Override
            protected IntermediateEventRepresentation doUpcast(IntermediateEventRepresentation intermediateRepresentation, AtomicInteger context) {
                return intermediateRepresentation.upcast(intermediateRepresentation.getType(), String.class, Function.identity(), m -> m.and("counter", context.getAndIncrement()));
            }

            @Override
            protected AtomicInteger buildContext() {
                return new AtomicInteger();
            }
        };
        when(upcasterChain.upcast(any())).thenAnswer(i -> upcaster.upcast(i.getArgument(0)));
        testSubject.publish(new GenericDomainEventMessage<>(AGGREGATE_TYPE, AGGREGATE_ID, 0, "Test1"),
                            new GenericDomainEventMessage<>(AGGREGATE_TYPE, AGGREGATE_ID, 1, "Test2"),
                            new GenericDomainEventMessage<>(AGGREGATE_TYPE, AGGREGATE_ID, 2, "Test3"));

        DomainEventStream actual = testSubject.readEvents(AGGREGATE_ID);
        assertTrue(actual.hasNext());
        assertEquals(0, actual.next().getMetaData().get("counter"));
        assertEquals(1, actual.next().getMetaData().get("counter"));
        assertEquals(2, actual.next().getMetaData().get("counter"));
        assertFalse(actual.hasNext());
    }

    @Test
    void lastSequenceNumberFor() {
        assertFalse(testSubject.lastSequenceNumberFor("Agg1").isPresent());
    }

    @Test
    void createStreamableMessageSourceForContext() {
        assertNotNull(testSubject.createStreamableMessageSourceForContext("some-context"));
    }

    @Test
    void usingLocalEventStoreOnOpeningStream() {
        testSubject.publish(new GenericDomainEventMessage<>(AGGREGATE_TYPE, AGGREGATE_ID, 0, "Test1"));
        testSubject.openStream(null);
        assertWithin(1, TimeUnit.SECONDS, () -> assertEquals(1, eventStore.getEventsRequests().size()));
        assertFalse(eventStore.getEventsRequests().get(0).getForceReadFromLeader());
    }

    @Test
    void usingLocalEventStoreOnQueryingEvents() {
        testSubject.publish(new GenericDomainEventMessage<>(AGGREGATE_TYPE, AGGREGATE_ID, 0, "Test1"));
        testSubject.query("", true);
        assertWithin(1, TimeUnit.SECONDS,
                     () -> assertEquals(1, eventStore.getQueryEventsRequests().size()));
        assertFalse(eventStore.getQueryEventsRequests().get(0).getForceReadFromLeader());
    }

    @Test
    void readEventsReturnsSnapshotsAndEventsWithMetaData() {
        Map<String, String> testMetaData = Collections.singletonMap("key", "value");
        testSubject.storeSnapshot(
                new GenericDomainEventMessage<>(AGGREGATE_TYPE, AGGREGATE_ID, 1, "Snapshot1", testMetaData)
        );
        testSubject.publish(new GenericDomainEventMessage<>(AGGREGATE_TYPE, AGGREGATE_ID, 0, "Test1", testMetaData),
                            new GenericDomainEventMessage<>(AGGREGATE_TYPE, AGGREGATE_ID, 1, "Test2", testMetaData),
                            new GenericDomainEventMessage<>(AGGREGATE_TYPE, AGGREGATE_ID, 2, "Test3", testMetaData));

        // Snapshot storage is async, so we need to make sure the first event is the snapshot
        assertWithin(2, TimeUnit.SECONDS, () -> {
            DomainEventStream snapshotValidationStream = testSubject.readEvents(AGGREGATE_ID);
            assertTrue(snapshotValidationStream.hasNext());
            assertEquals("Snapshot1", snapshotValidationStream.next().getPayload());
        });

        DomainEventStream resultStream = testSubject.readEvents(AGGREGATE_ID);

        assertTrue(resultStream.hasNext());
        DomainEventMessage<?> resultSnapshot = resultStream.next();
        assertEquals("Snapshot1", resultSnapshot.getPayload());
        assertTrue(resultSnapshot.getMetaData().containsKey("key"));
        assertTrue(resultSnapshot.getMetaData().containsValue("value"));

        assertTrue(resultStream.hasNext());
        DomainEventMessage<?> resultEvent = resultStream.next();
        assertEquals("Test3", resultEvent.getPayload());
        assertTrue(resultEvent.getMetaData().containsKey("key"));
        assertTrue(resultEvent.getMetaData().containsValue("value"));

        assertFalse(resultStream.hasNext());
    }

    @Test
    void readEventsWithSequenceNumberIgnoresSnapshots() {
        Map<String, String> testMetaData = Collections.singletonMap("key", "value");
        testSubject.storeSnapshot(
                new GenericDomainEventMessage<>(AGGREGATE_TYPE, AGGREGATE_ID, 1, "Snapshot1", testMetaData)
        );
        testSubject.publish(new GenericDomainEventMessage<>(AGGREGATE_TYPE, AGGREGATE_ID, 0, "Test1", testMetaData),
                            new GenericDomainEventMessage<>(AGGREGATE_TYPE, AGGREGATE_ID, 1, "Test2", testMetaData),
                            new GenericDomainEventMessage<>(AGGREGATE_TYPE, AGGREGATE_ID, 2, "Test3", testMetaData));

        // Snapshot storage is async, so we need to make sure the first event through "readEvents" is the snapshot
        assertWithin(2, TimeUnit.SECONDS, () -> {
            DomainEventStream snapshotValidationStream = testSubject.readEvents(AGGREGATE_ID);
            assertTrue(snapshotValidationStream.hasNext());
            assertEquals("Snapshot1", snapshotValidationStream.next().getPayload());
        });

        DomainEventStream resultStream = testSubject.readEvents(AGGREGATE_ID, 0);

        assertTrue(resultStream.hasNext());
        DomainEventMessage<?> firstResultEvent = resultStream.next();
        assertEquals("Test1", firstResultEvent.getPayload());
        assertTrue(firstResultEvent.getMetaData().containsKey("key"));
        assertTrue(firstResultEvent.getMetaData().containsValue("value"));

        assertTrue(resultStream.hasNext());
        DomainEventMessage<?> secondResultEvent = resultStream.next();
        assertEquals("Test2", secondResultEvent.getPayload());
        assertTrue(secondResultEvent.getMetaData().containsKey("key"));
        assertTrue(secondResultEvent.getMetaData().containsValue("value"));

        assertTrue(resultStream.hasNext());
        DomainEventMessage<?> thirdResultEvent = resultStream.next();
        assertEquals("Test3", thirdResultEvent.getPayload());
        assertTrue(thirdResultEvent.getMetaData().containsKey("key"));
        assertTrue(thirdResultEvent.getMetaData().containsValue("value"));

        assertFalse(resultStream.hasNext());
    }

    @Test
    void eventStoreConfigurationThrowsAxonConfigurationExceptionIfNoSnapshotFilterIsProvided() {
        JacksonSerializer eventSerializer = JacksonSerializer.defaultSerializer();
        AxonServerEventStore.Builder testSubjectWithoutSnapshotFilterBuilder =
                AxonServerEventStore.builder()
                                    .configuration(config)
                                    .platformConnectionManager(axonServerConnectionManager)
                                    .upcasterChain(upcasterChain)
                                    .eventSerializer(eventSerializer)
                                    .snapshotSerializer(eventSerializer);
        assertThrows(AxonConfigurationException.class, testSubjectWithoutSnapshotFilterBuilder::build);
    }

    @Test
    void readEventsWithMagicSequenceNumberAndSnapshotFilterSetIgnoresSnapshots() {
        Map<String, String> testMetaData = Collections.singletonMap("key", "value");
        testSubject.storeSnapshot(
                new GenericDomainEventMessage<>(AGGREGATE_TYPE, AGGREGATE_ID, 1, "Snapshot1", testMetaData)
        );
        testSubject.publish(new GenericDomainEventMessage<>(AGGREGATE_TYPE, AGGREGATE_ID, 0, "Test1", testMetaData),
                            new GenericDomainEventMessage<>(AGGREGATE_TYPE, AGGREGATE_ID, 1, "Test2", testMetaData),
                            new GenericDomainEventMessage<>(AGGREGATE_TYPE, AGGREGATE_ID, 2, "Test3", testMetaData));

        // Snapshot storage is async, so we need to make sure the first event through "readEvents" is the snapshot
        assertWithin(2, TimeUnit.SECONDS, () -> {
            DomainEventStream snapshotValidationStream = testSubject.readEvents(AGGREGATE_ID);
            assertTrue(snapshotValidationStream.hasNext());
            assertEquals("Snapshot1", snapshotValidationStream.next().getPayload());
        });

        DomainEventStream resultStream = testSubject.readEvents(AGGREGATE_ID, -42);

        assertTrue(resultStream.hasNext());
        DomainEventMessage<?> firstResultEvent = resultStream.next();
        assertEquals("Test1", firstResultEvent.getPayload());
        assertTrue(firstResultEvent.getMetaData().containsKey("key"));
        assertTrue(firstResultEvent.getMetaData().containsValue("value"));

        assertTrue(resultStream.hasNext());
        DomainEventMessage<?> secondResultEvent = resultStream.next();
        assertEquals("Test2", secondResultEvent.getPayload());
        assertTrue(secondResultEvent.getMetaData().containsKey("key"));
        assertTrue(secondResultEvent.getMetaData().containsValue("value"));

        assertTrue(resultStream.hasNext());
        DomainEventMessage<?> thirdResultEvent = resultStream.next();
        assertEquals("Test3", thirdResultEvent.getPayload());
        assertTrue(thirdResultEvent.getMetaData().containsKey("key"));
        assertTrue(thirdResultEvent.getMetaData().containsValue("value"));

        assertFalse(resultStream.hasNext());
    }

    @Test
    void readEventsWithSnapshotFilterAndSameSerializerSetReadsSnapshot() {
        XStreamSerializer serializer = TestSerializer.xStreamSerializer();
        testSubject = AxonServerEventStore.builder()
                                          .configuration(config)
                                          .platformConnectionManager(axonServerConnectionManager)
                                          .upcasterChain(upcasterChain)
                                          .eventSerializer(serializer)
                                          .snapshotSerializer(serializer)
                                          .snapshotFilter(SnapshotFilter.allowAll())
                                          .build();

        Map<String, String> testMetaData = Collections.singletonMap("key", "value");
        testSubject.storeSnapshot(
                new GenericDomainEventMessage<>(AGGREGATE_TYPE, AGGREGATE_ID, 1, "Snapshot1", testMetaData)
        );
        testSubject.publish(new GenericDomainEventMessage<>(AGGREGATE_TYPE, AGGREGATE_ID, 0, "Test1", testMetaData),
                            new GenericDomainEventMessage<>(AGGREGATE_TYPE, AGGREGATE_ID, 1, "Test2", testMetaData),
                            new GenericDomainEventMessage<>(AGGREGATE_TYPE, AGGREGATE_ID, 2, "Test3", testMetaData));

        // Snapshot storage is async, so we need to make sure the first event through "readEvents" is the snapshot
        assertWithin(2, TimeUnit.SECONDS, () -> {
            DomainEventStream snapshotValidationStream = testSubject.readEvents(AGGREGATE_ID);
            assertTrue(snapshotValidationStream.hasNext());
            assertEquals("Snapshot1", snapshotValidationStream.next().getPayload());
        });

        DomainEventStream resultStream = testSubject.readEvents(AGGREGATE_ID);

        assertTrue(resultStream.hasNext());
        DomainEventMessage<?> firstResultEvent = resultStream.next();
        assertEquals("Snapshot1", firstResultEvent.getPayload());
        assertTrue(firstResultEvent.getMetaData().containsKey("key"));
        assertTrue(firstResultEvent.getMetaData().containsValue("value"));

        assertTrue(resultStream.hasNext());
        DomainEventMessage<?> thirdResultEvent = resultStream.next();
        assertEquals("Test3", thirdResultEvent.getPayload());
        assertTrue(thirdResultEvent.getMetaData().containsKey("key"));
        assertTrue(thirdResultEvent.getMetaData().containsValue("value"));

        assertFalse(resultStream.hasNext());
    }
    @Test
    void rethrowStatusRuntimeExceptionAsEventStoreExceptionIfNotOfTypeUnknown() {
        String testAggregateId = AGGREGATE_ID;
        Status expectedCode = Status.ABORTED;

        eventStore.setSnapshotFailure(snapshotRequest -> snapshotRequest.getAggregateId().equals(testAggregateId));
        eventStore.setSnapshotFailureException(() -> new StatusRuntimeException(expectedCode));

        EventStoreException result =
                assertThrows(EventStoreException.class, () -> testSubject.readEvents(testAggregateId));

        assertTrue(result.getMessage().contains("communicating with Axon Server"));
        assertEquals(expectedCode.getCode(), Status.fromThrowable(result).getCode());
    }

    @Test
    void snapReadingExceptionProceedsWithReadingEvents() {
        String testPayloadOne = "Test1";
        String testPayloadTwo = "Test2";
        String testPayloadThree = "Test3";
        //noinspection unchecked
        Map<String, Object> testMetaData = Collections.EMPTY_MAP;

        testSubject.storeSnapshot(
                new GenericDomainEventMessage<>(AGGREGATE_TYPE, AGGREGATE_ID, 1, "Snapshot1", testMetaData)
        );
        testSubject.publish(
                new GenericDomainEventMessage<>(AGGREGATE_TYPE, AGGREGATE_ID, 0, testPayloadOne, testMetaData),
                new GenericDomainEventMessage<>(AGGREGATE_TYPE, AGGREGATE_ID, 1, testPayloadTwo, testMetaData),
                new GenericDomainEventMessage<>(AGGREGATE_TYPE, AGGREGATE_ID, 2, testPayloadThree, testMetaData)
        );
        // Throw an exception if the snapshot is read,
        //  but stick with the default IllegalStateException to proceed with events.
        eventStore.setSnapshotFailure(snapshotRequest -> snapshotRequest.getAggregateId().equals(AGGREGATE_ID));

        DomainEventStream resultStream = testSubject.readEvents(AGGREGATE_ID);

        assertTrue(resultStream.hasNext());
        DomainEventMessage<?> firstResultEvent = resultStream.next();
        assertEquals(testPayloadOne, firstResultEvent.getPayload());

        assertTrue(resultStream.hasNext());
        DomainEventMessage<?> secondResultEvent = resultStream.next();
        assertEquals(testPayloadTwo, secondResultEvent.getPayload());

        assertTrue(resultStream.hasNext());
        DomainEventMessage<?> thirdResultEvent = resultStream.next();
        assertEquals(testPayloadThree, thirdResultEvent.getPayload());

        assertFalse(resultStream.hasNext());
    }

    @Test
    void lastSequenceNumberForReturnsSequenceAfterPublishingDomainEvent() throws Exception {
        GenericDomainEventMessage<String> event =
                new GenericDomainEventMessage<>(AGGREGATE_TYPE, AGGREGATE_ID, 0, "Test1");

        UnitOfWork<Message<?>> uow = DefaultUnitOfWork.startAndGet(null);
        testSubject.publish(event);
        uow.commit();

        DefaultUnitOfWork.startAndGet(null);
        Optional<Long> lastSeq = testSubject.lastSequenceNumberFor(AGGREGATE_ID);
        CurrentUnitOfWork.commit();

        assertTrue(lastSeq.isPresent());
        assertEquals(0L, lastSeq.get());
    }

    @Test
    void committedSequenceSupplierReturnsTrueVersionWhenLastEventIsOurs() throws Exception {
        GenericDomainEventMessage<String> event =
                new GenericDomainEventMessage<>(AGGREGATE_TYPE, AGGREGATE_ID, 0, "Test1");

        // Supplier registration happens during onPrepareCommit (inside commit()), not during publish().
        UnitOfWork<Message<?>> uow = DefaultUnitOfWork.startAndGet(null);
        testSubject.publish(event);
        uow.commit();

        @SuppressWarnings("unchecked")
        Supplier<Optional<Long>> supplier =
                (Supplier<Optional<Long>>) uow.root()
                        .getResource(CachingEventSourcingRepository.COMMITTED_SEQUENCE_SUPPLIER_KEY_PREFIX
                                             + AGGREGATE_ID);
        assertNotNull(supplier, "Supplier should be registered after commit");

        Optional<Long> result = supplier.get();
        assertTrue(result.isPresent());
        assertEquals(0L, result.get());
    }
}

