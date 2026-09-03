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

package org.axonframework.messaging.eventhandling;

import org.axonframework.common.Registration;
import org.axonframework.messaging.core.ApplicationContext;
import org.axonframework.messaging.core.EmptyApplicationContext;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.unitofwork.ProcessingLifecycle.DefaultPhases;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.SimpleUnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test class validating the {@link SimpleEventBus}.
 *
 * @author Mateusz Nowak
 * @since 5.0.0
 */
class SimpleEventBusTest {

    private EventBus testSubject;

    @BeforeEach
    void setUp() {
        testSubject = new SimpleEventBus();
    }

    @Nested
    @DisplayName("Subscription Management")
    class SubscriptionManagement {

        @Test
        void subscribingListenerAllowsItToReceiveEvents() {
            // given
            RecordingEventListener listener = new RecordingEventListener();

            // when
            testSubject.subscribe(listener);
            testSubject.publish(null, List.of(newEvent("event1")));

            // then
            assertThat(listener.getReceivedEvents())
                    .hasSize(1)
                    .extracting(msg -> msg.type().qualifiedName().toString())
                    .containsExactly("event1");
        }

        @Test
        void unsubscribingListenerPreventsItFromReceivingEvents() {
            // given
            RecordingEventListener listener = new RecordingEventListener();
            Registration registration = testSubject.subscribe(listener);

            // when
            testSubject.publish(null, List.of(newEvent("event1")));
            registration.cancel();
            testSubject.publish(null, List.of(newEvent("event2")));

            // then
            assertThat(listener.getReceivedEvents())
                    .hasSize(1)
                    .extracting(msg -> msg.type().qualifiedName().toString())
                    .containsExactly("event1");
        }

        @Test
        void duplicateSubscriptionOnlyRegistersOnce() {
            // given
            RecordingEventListener listener = new RecordingEventListener();

            // when
            testSubject.subscribe(listener);
            testSubject.subscribe(listener);
            testSubject.publish(null, List.of(newEvent("event1")));

            // then
            assertThat(listener.getInvocationCount()).isEqualTo(1);
        }

        @Test
        void multipleListenersAllReceiveEvents() {
            // given
            RecordingEventListener listener1 = new RecordingEventListener();
            RecordingEventListener listener2 = new RecordingEventListener();
            RecordingEventListener listener3 = new RecordingEventListener();

            // when
            testSubject.subscribe(listener1);
            testSubject.subscribe(listener2);
            testSubject.subscribe(listener3);
            testSubject.publish(null, List.of(newEvent("event1")));

            // then
            assertThat(listener1.getReceivedEvents()).hasSize(1);
            assertThat(listener2.getReceivedEvents()).hasSize(1);
            assertThat(listener3.getReceivedEvents()).hasSize(1);
        }

        @Test
        void unsubscribingNonSubscribedListenerDoesNotFail() {
            // given
            RecordingEventListener listener = new RecordingEventListener();
            Registration registration = testSubject.subscribe(listener);

            // when / then
            registration.cancel();
            registration.cancel(); // Second cancel should not fail
        }
    }

    @Nested
    @DisplayName("Publishing Without ProcessingContext")
    class PublishingWithoutProcessingContext {

        @Test
        void multipleEventsArePublishedInSingleBatch() {
            // given
            RecordingEventListener listener = new RecordingEventListener();
            testSubject.subscribe(listener);
            List<EventMessage> events = List.of(
                    newEvent("event1"),
                    newEvent("event2"),
                    newEvent("event3")
            );

            // when
            testSubject.publish(null, events);

            // then
            assertThat(listener.getInvocationCount()).isEqualTo(1);
            assertThat(listener.getReceivedEvents())
                    .hasSize(3)
                    .extracting(msg -> msg.type().qualifiedName().toString())
                    .containsExactly("event1", "event2", "event3");
        }

        @Test
        void contextPassedToListenersIsNull() {
            // given
            ContextCapturingEventListener listener = new ContextCapturingEventListener();
            testSubject.subscribe(listener);

            // when
            testSubject.publish(null, List.of(newEvent("event1")));

            // then
            assertThat(listener.getCapturedContexts())
                    .hasSize(1)
                    .first()
                    .isNull();
        }

        @Test
        void publishingWithoutSubscribersDoesNotFail() {
            // when / then - no exception should be thrown
            testSubject.publish(null, List.of(newEvent("event1")));
        }

        @Test
        void publishFutureOnlyCompletesWhenSubscribersComplete() {
            // given
            CompletableFuture<Void> subscriberResult = new CompletableFuture<>();
            testSubject.subscribe((events, context) -> subscriberResult);

            // when
            CompletableFuture<Void> publishResult = testSubject.publish(null, List.of(newEvent("event1")));

            // then - the publish future tracks the subscriber and does not complete before it does
            assertThat(publishResult).isNotDone();
            subscriberResult.complete(null);
            assertThat(publishResult).isCompleted();
        }

        @Test
        void publishFutureCompletesExceptionallyWhenSubscriberFails() {
            // given
            RuntimeException failure = new RuntimeException("subscriber failed");
            testSubject.subscribe((events, context) -> CompletableFuture.failedFuture(failure));

            // when
            CompletableFuture<Void> publishResult = testSubject.publish(null, List.of(newEvent("event1")));

            // then
            assertThat(publishResult)
                    .failsWithin(Duration.ZERO)
                    .withThrowableThat()
                    .havingCause()
                    .isEqualTo(failure);
        }

        @Test
        void everySubscriberIsNotifiedEvenWhenAnEarlierOneFails() {
            // given
            testSubject.subscribe((events, context) -> CompletableFuture.failedFuture(new RuntimeException("boom")));
            RecordingEventListener laterListener = new RecordingEventListener();
            testSubject.subscribe(laterListener);

            // when
            CompletableFuture<Void> publishResult = testSubject.publish(null, List.of(newEvent("event1")));

            // then - the earlier failure does not prevent the later subscriber from receiving the event,
            // and the failure is still reported to the publisher
            assertThat(laterListener.getReceivedEvents()).hasSize(1);
            assertThat(publishResult).isCompletedExceptionally();
        }

        @Test
        void publishReportsSynchronousSubscriberFailureThroughReturnedFuture() {
            // given
            RuntimeException failure = new RuntimeException("subscriber threw");
            testSubject.subscribe((events, context) -> {
                throw failure;
            });
            RecordingEventListener laterListener = new RecordingEventListener();
            testSubject.subscribe(laterListener);

            // when - a subscriber throwing must not surface at the publishing call site
            CompletableFuture<Void> publishResult = testSubject.publish(null, List.of(newEvent("event1")));

            // then - the throw is carried by the publication future and the later subscriber still received the event
            assertThat(publishResult)
                    .failsWithin(Duration.ZERO)
                    .withThrowableThat()
                    .havingCause()
                    .isEqualTo(failure);
            assertThat(laterListener.getReceivedEvents()).hasSize(1);
        }

        @Test
        void firstFailureIsReportedWhenMultipleSubscribersThrowSynchronously() {
            // given
            RuntimeException firstFailure = new RuntimeException("first subscriber threw");
            RuntimeException secondFailure = new RuntimeException("second subscriber threw");
            testSubject.subscribe((events, context) -> {
                throw firstFailure;
            });
            testSubject.subscribe((events, context) -> {
                throw secondFailure;
            });
            RecordingEventListener laterListener = new RecordingEventListener();
            testSubject.subscribe(laterListener);

            // when
            CompletableFuture<Void> publishResult = testSubject.publish(null, List.of(newEvent("event1")));

            // then - the failure of the first throwing subscriber is reported, the remaining ones still ran
            assertThat(publishResult)
                    .failsWithin(Duration.ZERO)
                    .withThrowableThat()
                    .havingCause()
                    .isEqualTo(firstFailure);
            assertThat(laterListener.getReceivedEvents()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("Publishing With Active ProcessingContext")
    class PublishingWithActiveProcessingContext {

        @Test
        void multiplePublishCallsInSameContextAreQueuedTogether() {
            // given
            StubProcessingContext context = new StubProcessingContext();
            RecordingEventListener listener = new RecordingEventListener();
            testSubject.subscribe(listener);

            // when
            testSubject.publish(context, List.of(newEvent("event1")));
            testSubject.publish(context, List.of(newEvent("event2")));
            testSubject.publish(context, List.of(newEvent("event3")));

            // then - no events published yet
            assertThat(listener.getReceivedEvents()).isEmpty();

            // when - move to prepareCommit phase
            context.moveToPhase(DefaultPhases.PREPARE_COMMIT);

            // then - all events published together
            assertThat(listener.getInvocationCount()).isEqualTo(1);
            assertThat(listener.getReceivedEvents())
                    .hasSize(3)
                    .extracting(msg -> msg.type().qualifiedName().toString())
                    .containsExactly("event1", "event2", "event3");
        }

        @Test
        void eventsPublishedDuringPrepareCommitAreAlsoProcessed() {
            // given
            StubProcessingContext context = new StubProcessingContext();
            PublishingEventListener publishingListener = new PublishingEventListener(testSubject, context);
            RecordingEventListener recordingListener = new RecordingEventListener();
            testSubject.subscribe(publishingListener);
            testSubject.subscribe(recordingListener);

            // when
            testSubject.publish(context, List.of(newEvent("event1")));
            context.moveToPhase(DefaultPhases.PREPARE_COMMIT);

            // then - both original and newly published events are received
            assertThat(recordingListener.getReceivedEvents())
                    .hasSizeGreaterThanOrEqualTo(1);
        }

        @Test
        void contextIsPassedToListeners() {
            // given
            StubProcessingContext context = StubProcessingContext.withComponent(
                    String.class,
                    "TestComponent"
            );
            ContextCapturingEventListener listener = new ContextCapturingEventListener();
            testSubject.subscribe(listener);

            // when
            testSubject.publish(context, List.of(newEvent("event1")));
            context.moveToPhase(DefaultPhases.PREPARE_COMMIT);

            // then
            assertThat(listener.getCapturedContexts())
                    .hasSize(1)
                    .first()
                    .isNotNull()
                    .satisfies(ctx -> {
                        assertThat(ctx.component(String.class, null))
                                .isEqualTo("TestComponent");
                    });
        }

        @Test
        void publishingAfterContextCommittedThrowsException() {
            // given
            StubProcessingContext context = new StubProcessingContext();
            context.moveToPhase(DefaultPhases.COMMIT);

            // when / then the bus cannot register the prepare-commit hook that would deliver the events
            assertThatThrownBy(() -> testSubject.publish(context, List.of(newEvent("event1"))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ProcessingContext is already in phase COMMIT");
        }
    }

    @Nested
    @DisplayName("Component Access from ApplicationContext")
    class ComponentAccessFromApplicationContext {

        @Test
        void listenersCanAccessComponentsFromApplicationContext() {
            // given
            String testComponent = "MyTestComponent";
            StubProcessingContext context = StubProcessingContext.withComponent(
                    String.class,
                    testComponent
            );
            ComponentAccessingListener listener = new ComponentAccessingListener();
            testSubject.subscribe(listener);

            // when
            testSubject.publish(context, List.of(newEvent("event1")));
            context.moveToPhase(DefaultPhases.PREPARE_COMMIT);

            // then
            assertThat(listener.getAccessedComponent()).isEqualTo(testComponent);
        }

    }

    @Nested
    @DisplayName("Publishing With UnitOfWork")
    class PublishingWithUnitOfWork {

        private UnitOfWorkFactory unitOfWorkFactory;

        @BeforeEach
        void setUpUnitOfWork() {
            ApplicationContext appContext = EmptyApplicationContext.INSTANCE;
            unitOfWorkFactory = new SimpleUnitOfWorkFactory(appContext);
        }

        @Test
        void errorDuringPrepareCommitPreventsCommit() {
            // given
            ExceptionThrowingListener failingListener = new ExceptionThrowingListener();
            RecordingEventListener recordingListener = new RecordingEventListener();
            testSubject.subscribe(failingListener);
            testSubject.subscribe(recordingListener);
            UnitOfWork uow = unitOfWorkFactory.create();

            // when
            uow.runOnInvocation(ctx -> testSubject.publish(ctx, List.of(newEvent("event1"))));
            CompletableFuture<Void> result = uow.execute();

            // then - the throwing subscriber fails the UnitOfWork without hiding the event from the next subscriber
            assertThat(result).isCompletedExceptionally();
            assertThat(recordingListener.getReceivedEvents()).hasSize(1);
        }

        @Test
        void publicationForbiddenDuringCommitPhase() {
            // given
            UnitOfWork uow = unitOfWorkFactory.create();

            // when - try to publish during commit phase
            uow.runOnCommit(ctx -> testSubject.publish(ctx, List.of(newEvent("event1"))));
            CompletableFuture<Void> result = uow.execute();

            // then
            assertThat(result).isDone();
            assertThat(result).isCompletedExceptionally();
        }

        @Test
        void publicationForbiddenDuringAfterCommitPhase() {
            // given
            UnitOfWork uow = unitOfWorkFactory.create();

            // when - try to publish during afterCommit phase
            uow.runOnAfterCommit(ctx -> testSubject.publish(ctx, List.of(newEvent("event1"))));
            CompletableFuture<Void> result = uow.execute();

            // then
            assertThat(result).isDone();
            assertThat(result).isCompletedExceptionally();
        }
    }

    // Helper methods

    private EventMessage newEvent(String type) {
        return new GenericEventMessage(new MessageType(type), new Object());
    }

    // Test listener implementations

    /**
     * Recording listener that tracks all invocations and events received.
     */
    private static class RecordingEventListener implements java.util.function.BiFunction<List<? extends EventMessage>, ProcessingContext, CompletableFuture<?>> {
        private final List<EventMessage> receivedEvents = new CopyOnWriteArrayList<>();
        private int invocationCount = 0;

        @Override
        public CompletableFuture<?> apply(List<? extends EventMessage> events, ProcessingContext context) {
            invocationCount++;
            receivedEvents.addAll(events);
            return CompletableFuture.completedFuture(null);
        }

        public List<EventMessage> getReceivedEvents() {
            return new ArrayList<>(receivedEvents);
        }

        public int getInvocationCount() {
            return invocationCount;
        }
    }

    /**
     * Listener that captures the ProcessingContext passed to it.
     */
    private static class ContextCapturingEventListener implements java.util.function.BiFunction<List<? extends EventMessage>, ProcessingContext, CompletableFuture<?>> {
        private final List<ProcessingContext> capturedContexts = new CopyOnWriteArrayList<>();

        @Override
        public CompletableFuture<?> apply(List<? extends EventMessage> events, ProcessingContext context) {
            capturedContexts.add(context);
            return CompletableFuture.completedFuture(null);
        }

        public List<ProcessingContext> getCapturedContexts() {
            return new ArrayList<>(capturedContexts);
        }
    }

    /**
     * Listener that publishes additional events when invoked (only once to avoid infinite loops).
     */
    private static class PublishingEventListener implements java.util.function.BiFunction<List<? extends EventMessage>, ProcessingContext, CompletableFuture<?>> {
        private final EventBus eventBus;
        private final ProcessingContext context;
        private boolean hasPublished = false;

        public PublishingEventListener(EventBus eventBus, ProcessingContext context) {
            this.eventBus = eventBus;
            this.context = context;
        }

        @Override
        public CompletableFuture<?> apply(List<? extends EventMessage> events, ProcessingContext context) {
            // Publish an additional event during processing (only once to avoid infinite loops)
            if (!hasPublished) {
                hasPublished = true;
                eventBus.publish(this.context, List.of(
                        new GenericEventMessage(new MessageType("cascaded-event"), new Object())
                ));
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Listener that accesses a component from the ApplicationContext.
     */
    private static class ComponentAccessingListener implements java.util.function.BiFunction<List<? extends EventMessage>, ProcessingContext, CompletableFuture<?>> {
        private String accessedComponent;

        @Override
        public CompletableFuture<?> apply(List<? extends EventMessage> events, ProcessingContext context) {
            if (context != null) {
                accessedComponent = context.component(String.class, null);
            }
            return CompletableFuture.completedFuture(null);
        }

        public String getAccessedComponent() {
            return accessedComponent;
        }
    }

    /**
     * Listener that throws an exception when invoked.
     */
    private static class ExceptionThrowingListener implements java.util.function.BiFunction<List<? extends EventMessage>, ProcessingContext, CompletableFuture<?>> {
        @Override
        public CompletableFuture<?> apply(List<? extends EventMessage> events, ProcessingContext context) {
            throw new RuntimeException("Simulated failure during prepare commit");
        }
    }

}

