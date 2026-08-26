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

package org.axonframework.integrationtests.deadline;

import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.deadline.DeadlineManager;
import org.axonframework.deadline.DeadlineMessage;
import org.axonframework.deadline.GenericDeadlineMessage;
import org.axonframework.deadline.annotation.DeadlineHandler;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventSink;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.SimpleEventBus;
import org.axonframework.messaging.eventhandling.annotation.Timestamp;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.eventstore.AnnotationBasedTagResolver;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.eventsourcing.eventstore.StorageEngineBackedEventStore;
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine;
import org.axonframework.messaging.core.GenericMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageDispatchInterceptor;
import org.axonframework.messaging.core.MessageDispatchInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.correlation.CorrelationDataProvider;
import org.axonframework.messaging.core.correlation.MessageOriginProvider;
import org.axonframework.messaging.core.correlation.SimpleCorrelationDataProvider;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.modelling.saga.AssociationValue;
import org.axonframework.modelling.saga.EndSaga;
import org.axonframework.modelling.saga.SagaEventHandler;
import org.axonframework.modelling.saga.StartSaga;
import org.axonframework.modelling.saga.repository.SagaStore;
import org.axonframework.messaging.tracing.support.TestSpanFactory;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.*;
import org.mockito.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;
import static org.awaitility.Awaitility.await;
import static org.axonframework.messaging.eventhandling.EventTestUtils.asEventMessage;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests whether a {@link DeadlineManager} implementations functions as expected. This is an abstract (integration) test
 * class, whose tests cases should work for any DeadlineManager implementation.
 *
 * @author Milan Savic
 * @author Steven van Beelen
 * @since 3.3
 */
// TODO #3065 Fix as part of referred to issue.
public abstract class AbstractDeadlineManagerTestSuite {

    private static final int DEADLINE_TIMEOUT = 100;
    private static final int DEADLINE_WAIT_THRESHOLD = 15 * DEADLINE_TIMEOUT;
    private static final int CHILD_ENTITY_DEADLINE_TIMEOUT = 250;
    private static final UUID IDENTIFIER = UUID.randomUUID();
    private static final UUID FAKE_IDENTIFIER = UUID.randomUUID();
    private static final boolean CANCEL_BEFORE_DEADLINE = true;
    private static final boolean DO_NOT_CANCEL_BEFORE_DEADLINE = false;
    private static final String END_SAGA = "end-saga";
    private static final UUID SAGA_ENDED = UUID.randomUUID();
    private static final boolean LIVE = false;
    private static final boolean CLOSED = true;
    private static final String CUSTOM_CORRELATION_DATA_KEY = "custom-correlation-data";

    protected AxonConfiguration configuration;
    protected TestSpanFactory spanFactory;
    private List<Message> publishedMessages;
    private DeadlineManager deadlineManager;
    private String managerName;

    @BeforeEach
    void setUp() {
        spanFactory = new TestSpanFactory();
        EventStore eventStore =
                spy(new StorageEngineBackedEventStore(new InMemoryEventStorageEngine(), new SimpleEventBus(), new AnnotationBasedTagResolver()));
        List<CorrelationDataProvider> correlationDataProviders = new ArrayList<>();
        correlationDataProviders.add(new MessageOriginProvider());
        correlationDataProviders.add(new SimpleCorrelationDataProvider(CUSTOM_CORRELATION_DATA_KEY));

        MessagingConfigurer configurer =
                MessagingConfigurer.create()
                                   .componentRegistry(ComponentRegistry::disableEnhancerScanning);
//        configurer.eventProcessing()
//                  .usingSubscribingEventProcessors()
//                  .registerSaga(MySaga.class);
//        configuration = configurer.configureEventStore(c -> eventStore)
//                                  .configureCorrelationDataProviders(config -> {
//                                      return correlationDataProviders;
//                                  })
//                                  .configureAggregate(MyAggregate.class)
//                                  .registerComponent(DeadlineManager.class, this::buildAndSpyDeadlineManager)
//                                  .configureSpanFactory(c -> spanFactory)
//                                  .start();

        publishedMessages = new CopyOnWriteArrayList<>();
        //noinspection resource
//        configuration.eventBus().subscribe(events -> publishedMessages.addAll(events));
    }

    private DeadlineManager buildAndSpyDeadlineManager(Configuration configuration) {
        DeadlineManager builtManager = buildDeadlineManager(configuration);
//        builtManager.registerHandlerInterceptor(
//                new CorrelationDataInterceptor<>(configuration.getComponent(CorrelationDataProvider.class))
//        );
        managerName = builtManager.getClass().getSimpleName();
        this.deadlineManager = spy(builtManager);
        return this.deadlineManager;
    }

    @AfterEach
    void tearDown() {
        configuration.shutdown();
    }

    /**
     * Build the {@link DeadlineManager} to be tested.
     *
     * @param configuration the {@link Configuration} used to set up this test class
     * @return a {@link DeadlineManager} to be tested
     */
    public abstract DeadlineManager buildDeadlineManager(Configuration configuration);

    // The aggregate-based scenarios below are retained for the deadline revival, but cannot be compiled
    // yet: the AF4 aggregate API they build on (org.axonframework.modelling.command) lives in a module that
    // has not been folded into axon-legacy. Uncomment once those types are available here.
    // TODO #3065

//    @Test
//    void deadlineOnAggregate() {
//        configuration.getComponent(CommandGateway.class)
//                     .sendAndWait(new CreateMyAggregateCommand(IDENTIFIER, DEADLINE_TIMEOUT));
//        // Set time a bit over the current time, as the CommandBus' process may just stall the deadline scheduling.
//        Instant afterDeadlineWasScheduled = Instant.now().plusMillis(50);

//        assertPublishedEvents(new MyAggregateCreatedEvent(IDENTIFIER),
//                              new DeadlineOccurredEvent(new DeadlinePayload(IDENTIFIER)));

//        Message aggregateCreatedEvent = publishedMessages.getFirst();
//        assertInstanceOf(GenericEventMessage.class, aggregateCreatedEvent);
//        assertTrue(afterDeadlineWasScheduled.isAfter(((GenericEventMessage) aggregateCreatedEvent).timestamp()));
//        Message deadLineEvent = publishedMessages.get(1);
//        assertInstanceOf(GenericEventMessage.class, deadLineEvent);
//        assertTrue(afterDeadlineWasScheduled.isBefore(((GenericEventMessage) deadLineEvent).timestamp()));
//    }

//    @Test
//    void deadlineScheduleAndExecutionIsTraced() {
//        String scheduledDeadlineId = configuration.getComponent(CommandGateway.class)
//                                                  .sendAndWait(new CreateMyAggregateCommand(
//                                                          IDENTIFIER, DEADLINE_TIMEOUT
//                                                  ), String.class);

//        assertPublishedEvents(new MyAggregateCreatedEvent(IDENTIFIER),
//                              new DeadlineOccurredEvent(new DeadlinePayload(IDENTIFIER)));
//        spanFactory.verifySpanCompleted("DeadlineManager.scheduleDeadline(deadlineName)");
//        spanFactory.verifySpanHasAttributeValue("DeadlineManager.scheduleDeadline(deadlineName)",
//                                                "axon.deadlineId",
//                                                scheduledDeadlineId);
//        await().pollDelay(Duration.ofMillis(50)).atMost(Duration.ofMillis(100))
//               .untilAsserted(() -> spanFactory.verifySpanCompleted("DeadlineManager.executeDeadline(deadlineName)"));
//        spanFactory.verifySpanHasAttributeValue("DeadlineManager.executeDeadline(deadlineName)",
//                                                "axon.deadlineId",
//                                                scheduledDeadlineId);
//    }


//    @Test
//    public void deadlineCancellationWithinScopeOnAggregate() {
//        configuration.getComponent(CommandGateway.class)
//                     .sendAndWait(new CreateMyAggregateCommand(IDENTIFIER));
//        configuration.getComponent(CommandGateway.class)
//                     .sendAndWait(new ScheduleSpecificDeadline(IDENTIFIER, "some-payload"));
//        configuration.getComponent(CommandGateway.class)
//                     .sendAndWait(new ScheduleSpecificDeadline(IDENTIFIER, "some-payload"));
//        configuration.getComponent(CommandGateway.class)
//                     .sendAndWait(new ScheduleSpecificDeadline(IDENTIFIER, null));
//        configuration.getComponent(CommandGateway.class)
//                     .sendAndWait(new CancelDeadlineWithinScope(IDENTIFIER));

//        spanFactory.verifySpanCompleted("DeadlineManager.cancelAllWithinScope(deadlineName)");
//        spanFactory.verifySpanCompleted("DeadlineManager.cancelAllWithinScope(specificDeadlineName)");
//        spanFactory.verifySpanCompleted("DeadlineManager.cancelAllWithinScope(payloadlessDeadline)");

//        assertPublishedEvents(new MyAggregateCreatedEvent(IDENTIFIER));
//    }

//    @Test
//    void deadlineCancellationOnAggregate() {
//        configuration.getComponent(CommandGateway.class)
//                     .sendAndWait(new CreateMyAggregateCommand(IDENTIFIER,
//                                                               DEADLINE_TIMEOUT,
//                                                               CANCEL_BEFORE_DEADLINE));

//        assertPublishedEvents(new MyAggregateCreatedEvent(IDENTIFIER));
//    }

//    @Test
//    void deadlineOnChildEntity() {
//        configuration.getComponent(CommandGateway.class)
//                     .sendAndWait(new CreateMyAggregateCommand(IDENTIFIER, DEADLINE_TIMEOUT));
//        configuration.getComponent(CommandGateway.class)
//                     .sendAndWait(new TriggerDeadlineInChildEntityCommand(IDENTIFIER));

//        assertPublishedEvents(new MyAggregateCreatedEvent(IDENTIFIER),
//                              new DeadlineOccurredInChildEvent(new EntityDeadlinePayload("entity" + IDENTIFIER)),
//                              new DeadlineOccurredEvent(new DeadlinePayload(IDENTIFIER)));
//    }

//    @Test
//    void deadlineWithSpecifiedDeadlineName() {
//        String expectedDeadlinePayload = "deadlinePayload";

//        configuration.getComponent(CommandGateway.class)
//                     .sendAndWait(new CreateMyAggregateCommand(IDENTIFIER,
//                                                               DEADLINE_TIMEOUT,
//                                                               CANCEL_BEFORE_DEADLINE));
//        configuration.getComponent(CommandGateway.class)
//                     .sendAndWait(new ScheduleSpecificDeadline(IDENTIFIER, expectedDeadlinePayload));

//        assertPublishedEvents(new MyAggregateCreatedEvent(IDENTIFIER),
//                              new SpecificDeadlineOccurredEvent(expectedDeadlinePayload));
//    }

//    @Test
//    void deadlineCancellationOnAggregateIsTracedCorrectly() {
//        configuration.getComponent(CommandGateway.class)
//                     .sendAndWait(new CreateMyAggregateCommand(IDENTIFIER,
//                                                               DEADLINE_TIMEOUT,
//                                                               CANCEL_BEFORE_DEADLINE));

//        assertPublishedEvents(new MyAggregateCreatedEvent(IDENTIFIER));

//        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
//        verify(deadlineManager).cancelSchedule(any(), captor.capture());
//        spanFactory.verifySpanCompleted("DeadlineManager.cancelDeadline(deadlineName)");
//    }

//    @Test
//    public void deadlineCancelAllOnAggregateIsTracedCorrectly() {
//        configuration.getComponent(CommandGateway.class)
//                     .sendAndWait(new CreateMyAggregateCommand(IDENTIFIER,
//                                                               DEADLINE_TIMEOUT,
//                                                               DO_NOT_CANCEL_BEFORE_DEADLINE));
//        configuration.getComponent(CommandGateway.class)
//                     .sendAndWait(new CancelAllDeadlinesWithName(IDENTIFIER));

//        assertPublishedEvents(new MyAggregateCreatedEvent(IDENTIFIER));

//        spanFactory.verifySpanCompleted("DeadlineManager.cancelAllDeadlines(deadlineName)");
//    }


//    @Test
//    void deadlineWithoutPayload() {
//        configuration.getComponent(CommandGateway.class)
//                     .sendAndWait(new CreateMyAggregateCommand(IDENTIFIER,
//                                                               DEADLINE_TIMEOUT,
//                                                               CANCEL_BEFORE_DEADLINE));
//        configuration.getComponent(CommandGateway.class)
//                     .sendAndWait(new ScheduleSpecificDeadline(IDENTIFIER, null));

//        assertPublishedEvents(new MyAggregateCreatedEvent(IDENTIFIER),
//                              new SpecificDeadlineOccurredEvent(null));
//    }

//    @Test
//    @Disabled("TODO #3065")
//    void handlerInterceptorOnAggregate() {
////        configuration.getComponent(DeadlineManager.class).registerHandlerInterceptor((message, context, chain)
////                                                                                             -> chain.proceed(
////                AbstractDeadlineManagerTestSuite.asDeadlineMessage(message),
////                context));
//        configuration.getComponent(CommandGateway.class)
//                     .sendAndWait(new CreateMyAggregateCommand(IDENTIFIER, DEADLINE_TIMEOUT));

//        assertPublishedEvents(new MyAggregateCreatedEvent(IDENTIFIER),
//                              new DeadlineOccurredEvent(new DeadlinePayload(FAKE_IDENTIFIER)));
//    }

//    @Test
//    @Disabled("TODO #3065")
//    void dispatchInterceptorOnAggregate() {
////        configuration.getComponent(DeadlineManager.class)
////                     .registerDispatchInterceptor((message, context, chain)
////                                                          -> chain.proceed(AbstractDeadlineManagerTestSuite.asDeadlineMessage(
////                             message), context));
//        configuration.getComponent(CommandGateway.class)
//                     .sendAndWait(new CreateMyAggregateCommand(IDENTIFIER));

//        assertPublishedEvents(new MyAggregateCreatedEvent(IDENTIFIER),
//                              new DeadlineOccurredEvent(new DeadlinePayload(FAKE_IDENTIFIER)));
//    }

//    @Test
//    void deadlineMessagesReceiveCorrelationDataThroughAggregate() {
//        String expectedCorrelationData = "customValue";
//        MessageDispatchInterceptor<Message> correlationDataDispatchInterceptor =
//                new CustomCorrelationDataDispatchInterceptor(expectedCorrelationData);

////        TODO - Verify test
////        configuration.getComponent(org.axonframework.messaging.gateway.commandhandling.CommandGateway.class)
////        .registerDispatchInterceptor(correlationDataDispatchInterceptor);

//        configuration.getComponent(CommandGateway.class)
//                     .sendAndWait(new CreateMyAggregateCommand(IDENTIFIER));

//        assertPublishedEvents(new MyAggregateCreatedEvent(IDENTIFIER),
//                              new DeadlineOccurredEvent(new DeadlinePayload(IDENTIFIER)));

//        Message aggregateCreatedEvent = publishedMessages.getFirst();
//        assertTrue(aggregateCreatedEvent.metadata().containsKey(CUSTOM_CORRELATION_DATA_KEY));
//        assertEquals(expectedCorrelationData, aggregateCreatedEvent.metadata().get(CUSTOM_CORRELATION_DATA_KEY));
//        Message deadLineEvent = publishedMessages.get(1);
//        assertTrue(deadLineEvent.metadata().containsKey(CUSTOM_CORRELATION_DATA_KEY));
//        assertEquals(expectedCorrelationData, deadLineEvent.metadata().get(CUSTOM_CORRELATION_DATA_KEY));
//    }

//    @Test
//    void scheduleInPastTriggersDeadline() {
//        configuration.getComponent(CommandGateway.class)
//                     .sendAndWait(new CreateMyAggregateCommand(IDENTIFIER, -10000));

//        assertPublishedEvents(new MyAggregateCreatedEvent(IDENTIFIER),
//                              new DeadlineOccurredEvent(new DeadlinePayload(IDENTIFIER)));
//    }

//    @Test
//    @Disabled("TODO #3065")
//    void failedExecution() {
////        configuration.getComponent(DeadlineManager.class)
////                     .registerHandlerInterceptor((message, context, chain) ->
////                                                         MessageStream.failed(new AxonNonTransientException(
////                                                                 "Simulating handling error") {
////                                                         })
////
////                     );
//        configuration.getComponent(CommandGateway.class)
//                     .sendAndWait(new CreateMyAggregateCommand(IDENTIFIER));
//        assertPublishedEvents(new MyAggregateCreatedEvent(IDENTIFIER));
//    }

    @Test
    void deadlineOnSaga() {
        EventMessage testEventMessage =
                asEventMessage(new SagaStartingEvent(IDENTIFIER, DO_NOT_CANCEL_BEFORE_DEADLINE));
        configuration.getComponent(EventSink.class)
                     .publish(null, testEventMessage);

        assertPublishedEvents(new SagaStartingEvent(IDENTIFIER, DO_NOT_CANCEL_BEFORE_DEADLINE),
                              new DeadlineOccurredEvent(new DeadlinePayload(IDENTIFIER)));
        assertSagaIs(LIVE);
    }

    @Test
    public void deadlineCancellationWithinScopeOnSaga() {
        SagaStartingEvent sagaStartingEvent = new SagaStartingEvent(IDENTIFIER, DO_NOT_CANCEL_BEFORE_DEADLINE);
        ScheduleSpecificDeadline firstSchedule = new ScheduleSpecificDeadline(IDENTIFIER, "some-payload");
        ScheduleSpecificDeadline secondSchedule = new ScheduleSpecificDeadline(IDENTIFIER, "some-payload");
        ScheduleSpecificDeadline thirdSchedule = new ScheduleSpecificDeadline(IDENTIFIER, null);
        CancelDeadlineWithinScope scheduleCancellation = new CancelDeadlineWithinScope(IDENTIFIER);

        configuration.getComponent(EventSink.class)
                     .publish(null, asEventMessage(sagaStartingEvent));
        configuration.getComponent(EventSink.class)
                     .publish(null, asEventMessage(firstSchedule));
        configuration.getComponent(EventSink.class)
                     .publish(null, asEventMessage(secondSchedule));
        configuration.getComponent(EventSink.class)
                     .publish(null, asEventMessage(thirdSchedule));
        configuration.getComponent(EventSink.class)
                     .publish(null, asEventMessage(scheduleCancellation));


        assertPublishedEvents(sagaStartingEvent, firstSchedule, secondSchedule, thirdSchedule, scheduleCancellation);
        assertSagaIs(LIVE);
        spanFactory.verifySpanCompleted("DeadlineManager.cancelAllWithinScope(deadlineName)");
        spanFactory.verifySpanCompleted("DeadlineManager.cancelAllWithinScope(specificDeadlineName)");
        spanFactory.verifySpanCompleted("DeadlineManager.cancelAllWithinScope(payloadlessDeadline)");
    }

    @Test
    void deadlineCancellationOnSaga() {
        configuration.getComponent(EventSink.class)
                     .publish(null, asEventMessage(new SagaStartingEvent(IDENTIFIER, CANCEL_BEFORE_DEADLINE)));

        assertPublishedEvents(new SagaStartingEvent(IDENTIFIER, CANCEL_BEFORE_DEADLINE));
        assertSagaIs(LIVE);
    }

    @Test
    void deadlineCancellationOnCancelledDeadlineOnSaga() {
        configuration.getComponent(EventSink.class)
                     .publish(null, asEventMessage(new SagaStartingEvent(IDENTIFIER, CANCEL_BEFORE_DEADLINE)));
        configuration.getComponent(EventSink.class)
                     .publish(null, asEventMessage(new CancelCancelledDeadline(IDENTIFIER)));

        assertPublishedEvents(new SagaStartingEvent(IDENTIFIER, CANCEL_BEFORE_DEADLINE),
                new CancelCancelledDeadline(IDENTIFIER));
        assertSagaIs(LIVE);
    }

    @Test
    void deadlineCancellationOnSagaIsCorrectlyTraced() {
        configuration.getComponent(EventSink.class)
                     .publish(null, asEventMessage(new SagaStartingEvent(IDENTIFIER, CANCEL_BEFORE_DEADLINE)));

        assertPublishedEvents(new SagaStartingEvent(IDENTIFIER, CANCEL_BEFORE_DEADLINE));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(deadlineManager).cancelSchedule(any(), captor.capture());
        spanFactory.verifySpanCompleted("DeadlineManager.cancelDeadline(deadlineName)");
    }

    @Test
    public void deadlineCancelAllOnSagaIsCorrectlyTraced() {
        configuration.getComponent(EventSink.class)
                     .publish(null, asEventMessage(new SagaStartingEvent(IDENTIFIER, CANCEL_BEFORE_DEADLINE)));
        configuration.getComponent(EventSink.class)
                     .publish(null, asEventMessage(new CancelAllDeadlinesWithName(IDENTIFIER)));

        assertPublishedEvents(new SagaStartingEvent(IDENTIFIER, CANCEL_BEFORE_DEADLINE),
                              new CancelAllDeadlinesWithName(IDENTIFIER));

        spanFactory.verifySpanCompleted(("DeadlineManager.cancelAllDeadlines(deadlineName)"));
    }

    @Test
    void deadlineWithSpecifiedDeadlineNameOnSaga() {
        String expectedDeadlinePayload = "deadlinePayload";

        configuration.getComponent(EventSink.class)
                     .publish(null, asEventMessage(new SagaStartingEvent(IDENTIFIER, CANCEL_BEFORE_DEADLINE)));
        configuration.getComponent(EventSink.class)
                     .publish(null, asEventMessage(
                             new ScheduleSpecificDeadline(IDENTIFIER, expectedDeadlinePayload))
                     );

        assertPublishedEvents(new SagaStartingEvent(IDENTIFIER, CANCEL_BEFORE_DEADLINE),
                              new ScheduleSpecificDeadline(IDENTIFIER, expectedDeadlinePayload),
                              new SpecificDeadlineOccurredEvent(expectedDeadlinePayload));
        assertSagaIs(LIVE);
    }

    @Test
    void deadlineWithoutPayloadOnSaga() {
        configuration.getComponent(EventSink.class)
                     .publish(null, asEventMessage(new SagaStartingEvent(IDENTIFIER, CANCEL_BEFORE_DEADLINE)));
        configuration.getComponent(EventSink.class)
                     .publish(null, asEventMessage(new ScheduleSpecificDeadline(IDENTIFIER, null)));

        assertPublishedEvents(new SagaStartingEvent(IDENTIFIER, CANCEL_BEFORE_DEADLINE),
                              new ScheduleSpecificDeadline(IDENTIFIER, null),
                              new SpecificDeadlineOccurredEvent(null));
        assertSagaIs(LIVE);
    }

    @Test
    void sagaEndingDeadlineEndsTheSaga() {
        EventSink eventStore = configuration.getComponent(EventSink.class);
        eventStore.publish(null, asEventMessage(new SagaStartingEvent(IDENTIFIER, CANCEL_BEFORE_DEADLINE)));
        eventStore.publish(null, asEventMessage(new ScheduleSpecificDeadline(IDENTIFIER, END_SAGA)));

        assertPublishedEvents(new SagaStartingEvent(IDENTIFIER, CANCEL_BEFORE_DEADLINE),
                              new ScheduleSpecificDeadline(IDENTIFIER, END_SAGA),
                              new DeadlineOccurredEvent(new DeadlinePayload(SAGA_ENDED)));
        assertSagaIs(CLOSED);
    }

    @Test
    @Disabled("TODO #3065")
    void handlerInterceptorOnSaga() {
        EventMessage testEventMessage =
                asEventMessage(new SagaStartingEvent(IDENTIFIER, DO_NOT_CANCEL_BEFORE_DEADLINE));
//        configuration.getComponent(DeadlineManager.class)
//                     .registerHandlerInterceptor((message, context, chain) ->
//                                                         chain.proceed(
//                                                                 asDeadlineMessage(
//                                                                         message.getDeadlineName(),
//                                                                         new DeadlinePayload(
//                                                                                 FAKE_IDENTIFIER),
//                                                                         message.timestamp()
//                                                                 ),
//                                                                 context)
//                     );
        configuration.getComponent(EventSink.class)
                     .publish(null, testEventMessage);

        assertPublishedEvents(new SagaStartingEvent(IDENTIFIER, DO_NOT_CANCEL_BEFORE_DEADLINE),
                              new DeadlineOccurredEvent(new DeadlinePayload(FAKE_IDENTIFIER)));
        assertSagaIs(LIVE);
    }

    @Test
    @Disabled("TODO #3065")
    void dispatchInterceptorOnSaga() {
        EventMessage testEventMessage =
                asEventMessage(new SagaStartingEvent(IDENTIFIER, DO_NOT_CANCEL_BEFORE_DEADLINE));
//        configuration.getComponent(DeadlineManager.class)
//                     .registerHandlerInterceptor((message, context, chain) ->
//                                                         chain.proceed(
//                                                                 asDeadlineMessage(
//                                                                         message.getDeadlineName(),
//                                                                         new DeadlinePayload(
//                                                                                 FAKE_IDENTIFIER),
//                                                                         message.timestamp()
//                                                                 ),
//                                                                 context)
//                     );
        configuration.getComponent(EventSink.class)
                     .publish(null, testEventMessage);

        assertPublishedEvents(new SagaStartingEvent(IDENTIFIER, DO_NOT_CANCEL_BEFORE_DEADLINE),
                              new DeadlineOccurredEvent(new DeadlinePayload(FAKE_IDENTIFIER)));
        assertSagaIs(LIVE);
    }

    @Test
    void deadlineMessagesReceiveCorrelationDataThroughSaga() {
        String expectedCorrelationData = "customValue";
        MessageDispatchInterceptor<Message> correlationDataDispatchInterceptor =
                new CustomCorrelationDataDispatchInterceptor(expectedCorrelationData);

        configuration.getComponent(EventSink.class);
// TODO #3065                     .registerDispatchInterceptor(correlationDataDispatchInterceptor);

        EventMessage testEventMessage =
                asEventMessage(new SagaStartingEvent(IDENTIFIER, DO_NOT_CANCEL_BEFORE_DEADLINE));
        configuration.getComponent(EventSink.class)
                     .publish(null, testEventMessage);

        assertPublishedEvents(new SagaStartingEvent(IDENTIFIER, DO_NOT_CANCEL_BEFORE_DEADLINE),
                              new DeadlineOccurredEvent(new DeadlinePayload(IDENTIFIER)));

        assertSagaIs(LIVE);

        Message sagaStartingEvent = publishedMessages.get(0);
        assertTrue(sagaStartingEvent.metadata().containsKey(CUSTOM_CORRELATION_DATA_KEY));
        assertEquals(expectedCorrelationData, sagaStartingEvent.metadata().get(CUSTOM_CORRELATION_DATA_KEY));
        Message deadLineOccurredEvent = publishedMessages.get(1);
        assertTrue(deadLineOccurredEvent.metadata().containsKey(CUSTOM_CORRELATION_DATA_KEY));
        assertEquals(expectedCorrelationData, deadLineOccurredEvent.metadata().get(CUSTOM_CORRELATION_DATA_KEY));
    }

    private void assertPublishedEvents(Object... expectedEvents) {
        await().atMost(Duration.ofMillis(DEADLINE_TIMEOUT + DEADLINE_WAIT_THRESHOLD))
               .until(() -> sameElements(asList(expectedEvents)));
    }

    private boolean sameElements(List<Object> expected) {
        if (expected.size() != publishedMessages.size()) {
            return false;
        }
        List<Object> published = publishedMessages.stream().map(Message::payload).collect(Collectors.toList());
        return expected.containsAll(published) && published.containsAll(expected);
    }

    private void assertSagaIs(boolean live) {
        //noinspection unchecked
        SagaStore<MySaga> sagaStore = null; // TODO #3065 configuration.eventProcessingConfiguration().sagaStore();
        Set<String> sagaIds = sagaStore.findSagas(MySaga.class, new AssociationValue("id", IDENTIFIER.toString()));
        assertEquals(live, sagaIds.isEmpty());
    }

    private static DeadlineMessage asDeadlineMessage(DeadlineMessage deadlineMessage) {
        var payload = new DeadlinePayload(FAKE_IDENTIFIER);
        return new GenericDeadlineMessage(
                deadlineMessage.getDeadlineName(),
                new GenericMessage(new MessageType(payload.getClass()), payload),
                deadlineMessage::timestamp
        );
    }

    private static DeadlineMessage asDeadlineMessage(String deadlineName,
                                                     Object payload,
                                                     Instant expiryTime) {
        var name = new MessageType(payload.getClass());
        return new GenericDeadlineMessage(
                deadlineName, new GenericMessage(name, payload), () -> expiryTime
        );
    }

    private static class CreateMyAggregateCommand {

        private final UUID id;
        private final boolean cancelBeforeDeadline;
        private final int deadlineMillis;

        private CreateMyAggregateCommand(UUID id) {
            this(id, false);
        }

        private CreateMyAggregateCommand(UUID id, int deadlineMillis) {
            this(id, deadlineMillis, false);
        }

        private CreateMyAggregateCommand(UUID id, boolean cancelBeforeDeadline) {
            this.id = id;
            this.cancelBeforeDeadline = cancelBeforeDeadline;
            this.deadlineMillis = DEADLINE_TIMEOUT;
        }

        private CreateMyAggregateCommand(UUID id, int deadlineMillis, boolean cancelBeforeDeadline) {
            this.id = id;
            this.cancelBeforeDeadline = cancelBeforeDeadline;
            this.deadlineMillis = deadlineMillis;
        }
    }

    private static class CancelDeadlineWithinScope {

        private final UUID id;

        private CancelDeadlineWithinScope(UUID id) {
            this.id = id;
        }

        public UUID getId() {
            return id;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            CancelDeadlineWithinScope that = (CancelDeadlineWithinScope) o;
            return Objects.equals(id, that.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id);
        }
    }

    private static class CancelCancelledDeadline {

        private final UUID id;

        private CancelCancelledDeadline(UUID id) {
            this.id = id;
        }

        public UUID getId() {
            return id;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            CancelCancelledDeadline that = (CancelCancelledDeadline) o;
            return Objects.equals(id, that.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id);
        }
    }

    private static class CancelAllDeadlinesWithName {

        private final UUID id;

        private CancelAllDeadlinesWithName(UUID id) {
            this.id = id;
        }

        public UUID getId() {
            return id;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            CancelAllDeadlinesWithName that = (CancelAllDeadlinesWithName) o;
            return Objects.equals(id, that.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id);
        }
    }

    private static class TriggerDeadlineInChildEntityCommand {

        private final UUID id;

        private TriggerDeadlineInChildEntityCommand(UUID id) {
            this.id = id;
        }
    }

    private static class ScheduleSpecificDeadline {

        private final UUID id;
        private final String payload;

        private ScheduleSpecificDeadline(UUID id, String payload) {
            this.id = id;
            this.payload = payload;
        }

        public UUID getId() {
            return id;
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, payload);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            final ScheduleSpecificDeadline other = (ScheduleSpecificDeadline) obj;
            return Objects.equals(this.id, other.id)
                    && Objects.equals(this.payload, other.payload);
        }
    }

    private static class MyAggregateCreatedEvent {

        private final UUID id;

        private MyAggregateCreatedEvent(UUID id) {
            this.id = id;
        }

        public UUID getId() {
            return id;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            MyAggregateCreatedEvent that = (MyAggregateCreatedEvent) o;
            return Objects.equals(id, that.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id);
        }

        @Override
        public String toString() {
            return "MyAggregateCreatedEvent{" +
                    "id='" + id + '\'' +
                    '}';
        }
    }

    private static class SagaStartingEvent {

        private final UUID id;
        private final boolean cancelBeforeDeadline;

        private SagaStartingEvent(UUID id, boolean cancelBeforeDeadline) {
            this.id = id;
            this.cancelBeforeDeadline = cancelBeforeDeadline;
        }

        public UUID getId() {
            return id;
        }

        public boolean isCancelBeforeDeadline() {
            return cancelBeforeDeadline;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            SagaStartingEvent that = (SagaStartingEvent) o;
            return cancelBeforeDeadline == that.cancelBeforeDeadline && Objects.equals(id, that.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, cancelBeforeDeadline);
        }
    }

    private static class DeadlinePayload {

        private final UUID id;

        // No-arg constructor used for Jackson Serialization
        @SuppressWarnings("unused")
        private DeadlinePayload() {
            this(UUID.randomUUID());
        }

        private DeadlinePayload(UUID id) {
            this.id = id;
        }

        public UUID getId() {
            return id;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            DeadlinePayload that = (DeadlinePayload) o;
            return Objects.equals(id, that.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id);
        }
    }

    private static class EntityDeadlinePayload {

        private final String id;

        // No-arg constructor used for Jackson Serialization
        @SuppressWarnings("unused")
        private EntityDeadlinePayload() {
            this("some-id");
        }

        private EntityDeadlinePayload(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            EntityDeadlinePayload that = (EntityDeadlinePayload) o;
            return Objects.equals(id, that.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id);
        }
    }

    private static class DeadlineOccurredEvent {

        private final DeadlinePayload deadlinePayload;

        private DeadlineOccurredEvent(DeadlinePayload deadlinePayload) {
            this.deadlinePayload = deadlinePayload;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            DeadlineOccurredEvent that = (DeadlineOccurredEvent) o;
            return Objects.equals(deadlinePayload, that.deadlinePayload);
        }

        @Override
        public int hashCode() {
            return Objects.hash(deadlinePayload);
        }
    }

    private static class DeadlineOccurredInChildEvent {

        private final EntityDeadlinePayload deadlineInfo;

        private DeadlineOccurredInChildEvent(EntityDeadlinePayload deadlineInfo) {
            this.deadlineInfo = deadlineInfo;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            DeadlineOccurredInChildEvent that = (DeadlineOccurredInChildEvent) o;
            return Objects.equals(deadlineInfo, that.deadlineInfo);
        }

        @Override
        public int hashCode() {
            return Objects.hash(deadlineInfo);
        }
    }

    private static class SpecificDeadlineOccurredEvent {

        private final Object payload;

        private SpecificDeadlineOccurredEvent(Object payload) {
            this.payload = payload;
        }

        @Override
        public int hashCode() {
            return Objects.hash(payload);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            final SpecificDeadlineOccurredEvent other = (SpecificDeadlineOccurredEvent) obj;
            return Objects.equals(this.payload, other.payload);
        }
    }

    @SuppressWarnings("unused")
    public static class MySaga {

        private transient EventStore eventStore;
        private String deadlineId;

        @StartSaga
        @SagaEventHandler(associationProperty = "id")
        public void on(SagaStartingEvent sagaStartingEvent, DeadlineManager deadlineManager) {
            String deadlineName = "deadlineName";
            this.deadlineId = deadlineManager.schedule(
                    Duration.ofMillis(DEADLINE_TIMEOUT), deadlineName, new DeadlinePayload(sagaStartingEvent.id)
            );

            if (sagaStartingEvent.isCancelBeforeDeadline()) {
                deadlineManager.cancelSchedule(deadlineName, deadlineId);
            }
        }

        @SagaEventHandler(associationProperty = "id")
        public void on(CancelCancelledDeadline event, DeadlineManager deadlineManager) {
            deadlineManager.cancelSchedule("deadlineName", this.deadlineId);
        }

        @SagaEventHandler(associationProperty = "id")
        public void on(ScheduleSpecificDeadline message, DeadlineManager deadlineManager) {
            String deadlinePayload = message.payload;
            if (END_SAGA.equals(deadlinePayload)) {
                deadlineManager.schedule(Duration.ofMillis(DEADLINE_TIMEOUT), "sagaEndingDeadline");
            } else if (deadlinePayload != null) {
                deadlineManager.schedule(Duration.ofMillis(DEADLINE_TIMEOUT), "specificDeadlineName", deadlinePayload);
            } else {
                deadlineManager.schedule(Duration.ofMillis(DEADLINE_TIMEOUT), "payloadlessDeadline");
            }
        }

        @SagaEventHandler(associationProperty = "id")
        public void on(CancelDeadlineWithinScope evt, DeadlineManager deadlineManager) {
            deadlineManager.cancelAllWithinScope("deadlineName");
            deadlineManager.cancelAllWithinScope("specificDeadlineName");
            deadlineManager.cancelAllWithinScope("payloadlessDeadline");
        }

        @SagaEventHandler(associationProperty = "id")
        public void on(CancelAllDeadlinesWithName evt, DeadlineManager deadlineManager) {
            deadlineManager.cancelAll("deadlineName");
        }

        @DeadlineHandler
        public void on(DeadlinePayload deadlinePayload, @Timestamp Instant timestamp) {
            assertNotNull(timestamp);
            eventStore.publish(null, asEventMessage(new DeadlineOccurredEvent(deadlinePayload)));
        }

        @DeadlineHandler(deadlineName = "specificDeadlineName")
        public void on(Object deadlinePayload, @Timestamp Instant timestamp) {
            assertNotNull(timestamp);
            eventStore.publish(null, asEventMessage(new SpecificDeadlineOccurredEvent(deadlinePayload)));
        }

        @DeadlineHandler(deadlineName = "payloadlessDeadline")
        public void on() {
            eventStore.publish(null, asEventMessage(new SpecificDeadlineOccurredEvent(null)));
        }

        @EndSaga
        @DeadlineHandler(deadlineName = "sagaEndingDeadline")
        public void sagaEndingDeadline() {
            eventStore.publish(null, asEventMessage(new DeadlineOccurredEvent(new DeadlinePayload(SAGA_ENDED))));
        }

        @Autowired
        public void setEventStore(EventStore eventStore) {
            this.eventStore = eventStore;
        }
    }

    // The MyAggregate and MyEntity definitions below are retained for the deadline revival, but cannot be
    // compiled yet: the AF4 aggregate API they build on (org.axonframework.modelling.command) lives in a
    // module that has not been folded into axon-legacy. Uncomment once those types are available here.
    // TODO #3065

//    @SuppressWarnings({"unused", "FieldCanBeLocal"})
//    public static class MyAggregate {

//        @AggregateIdentifier
//        private UUID id;
//        @AggregateMember
//        private MyEntity myEntity;

//        public MyAggregate() {
//            // empty constructor
//        }

//        @CommandHandler
//        @CreationPolicy(AggregateCreationPolicy.ALWAYS)
//        public String on(CreateMyAggregateCommand command, DeadlineManager deadlineManager) {
//            apply(new MyAggregateCreatedEvent(command.id));

//            String deadlineName = "deadlineName";
//            Instant trigger = Instant.now().plus(Duration.ofMillis(command.deadlineMillis));
//            String deadlineId = deadlineManager.schedule(trigger, deadlineName, new DeadlinePayload(command.id));

//            if (command.cancelBeforeDeadline) {
//                deadlineManager.cancelSchedule(deadlineName, deadlineId);
//            }
//            return deadlineId;
//        }

//        @CommandHandler
//        public void on(ScheduleSpecificDeadline message, DeadlineManager deadlineManager) {
//            Object deadlinePayload = message.payload;
//            if (deadlinePayload != null) {
//                deadlineManager.schedule(Duration.ofMillis(DEADLINE_TIMEOUT), "specificDeadlineName", deadlinePayload);
//            } else {
//                deadlineManager.schedule(Duration.ofMillis(DEADLINE_TIMEOUT), "payloadlessDeadline");
//            }
//        }

//        @CommandHandler
//        public void on(CancelDeadlineWithinScope command, DeadlineManager deadlineManager) {
//            deadlineManager.cancelAllWithinScope("deadlineName");
//            deadlineManager.cancelAllWithinScope("specificDeadlineName");
//            deadlineManager.cancelAllWithinScope("payloadlessDeadline");
//        }

//        @CommandHandler
//        public void on(CancelAllDeadlinesWithName command, DeadlineManager deadlineManager) {
//            deadlineManager.cancelAll("deadlineName");
//        }

//        @EventSourcingHandler
//        public void on(MyAggregateCreatedEvent event) {
//            this.id = event.id;
//            this.myEntity = new MyEntity(id);
//        }

//        @DeadlineHandler
//        public void on(DeadlinePayload deadlinePayload, @Timestamp Instant timestamp) {
//            assertNotNull(timestamp);
//            apply(new DeadlineOccurredEvent(deadlinePayload));
//        }

//        @DeadlineHandler(deadlineName = "specificDeadlineName")
//        public void on(Object deadlinePayload) {
//            apply(new SpecificDeadlineOccurredEvent(deadlinePayload));
//        }

//        @DeadlineHandler(deadlineName = "payloadlessDeadline")
//        public void on() {
//            apply(new SpecificDeadlineOccurredEvent(null));
//        }

//        @CommandHandler
//        public void handle(TriggerDeadlineInChildEntityCommand command, DeadlineManager deadlineManager) {
//            deadlineManager.schedule(
//                    Duration.ofMillis(CHILD_ENTITY_DEADLINE_TIMEOUT),
//                    "deadlineName",
//                    new EntityDeadlinePayload("entity" + command.id)
//            );
//        }
//    }

//    public static class MyEntity {

//        @SuppressWarnings({"FieldCanBeLocal", "unused"})
//        @EntityId
//        private final UUID id;

//        private MyEntity(UUID id) {
//            this.id = id;
//        }

//        @DeadlineHandler
//        public void on(EntityDeadlinePayload deadlineInfo, @Timestamp Instant timestamp) {
//            assertNotNull(timestamp);
//            apply(new DeadlineOccurredInChildEvent(deadlineInfo));
//        }
//    }

    private static class CustomCorrelationDataDispatchInterceptor implements MessageDispatchInterceptor<Message> {

        private final String correlationData;

        private CustomCorrelationDataDispatchInterceptor(String correlationData) {
            this.correlationData = correlationData;
        }

        @Override
        public @NonNull MessageStream<?> interceptOnDispatch(@NonNull Message message,
                                                             @Nullable ProcessingContext context,
                                                             @NonNull MessageDispatchInterceptorChain<Message> interceptorChain) {
            return interceptorChain.proceed(message.andMetadata(Metadata.with(CUSTOM_CORRELATION_DATA_KEY,
                                                                              correlationData)), context);
        }
    }
}
