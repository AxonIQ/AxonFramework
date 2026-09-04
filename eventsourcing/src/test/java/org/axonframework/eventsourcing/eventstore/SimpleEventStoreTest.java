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

package org.axonframework.eventsourcing.eventstore;

import org.jspecify.annotations.NonNull;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventTestUtils;
import org.axonframework.messaging.eventhandling.SimpleEventBus;
import org.axonframework.messaging.eventhandling.processing.streaming.token.GlobalSequenceTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.StreamingCondition;
import org.axonframework.messaging.eventstreaming.Tag;
import org.axonframework.messaging.core.Context;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opentest4j.AssertionFailedError;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.axonframework.messaging.eventhandling.EventTestUtils.createEvent;
import static org.axonframework.messaging.core.unitofwork.UnitOfWorkTestUtils.aUnitOfWork;
import static org.axonframework.common.util.AssertUtils.awaitSuccessfulCompletion;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link StorageEngineBackedEventStore} supports just one context and delegates operations to
 * {@link EventStorageEngine}.
 *
 * @author Mateusz Nowak
 * @since 5.0.0
 */
@ExtendWith(MockitoExtension.class)
class SimpleEventStoreTest {

    private EventStorageEngine mockStorageEngine = mock(EventStorageEngine.class);
    private TagResolver tagResolver = mock(TagResolver.class);
    private ProcessingContext processingContext = mock(ProcessingContext.class);

    private StorageEngineBackedEventStore testSubject = new StorageEngineBackedEventStore(mockStorageEngine, new SimpleEventBus(), tagResolver);

    private static GlobalSequenceTrackingToken aGlobalSequenceToken() {
        return new GlobalSequenceTrackingToken(999);
    }

    private static StreamingCondition aStreamingCondition() {
        return StreamingCondition.startingFrom(new GlobalSequenceTrackingToken(999));
    }

    @Nested
    class DelegatingToStorageEngine {

        @Test
        void openStreamDelegatesConditionToStorageEngine(@Mock MessageStream<EventMessage> expectedStream) {
            // given
            StreamingCondition condition = aStreamingCondition();
            when(mockStorageEngine.stream(condition)).thenReturn(expectedStream);

            // when
            MessageStream<EventMessage> result = testSubject.open(condition, processingContext);

            // then
            assertSame(expectedStream, result);
            verify(mockStorageEngine).stream(condition);
        }

        @Test
        void firstTokenDelegatesToStorageEngine() {
            // given
            CompletableFuture<TrackingToken> expectedFuture = completedFuture(aGlobalSequenceToken());
            when(mockStorageEngine.firstToken()).thenReturn(expectedFuture);

            // when
            CompletableFuture<TrackingToken> result = testSubject.firstToken(processingContext);

            // then
            assertSame(expectedFuture, result);
            verify(mockStorageEngine).firstToken();
        }

        @Test
        void latestTokenDelegatesToStorageEngine() {
            // given
            CompletableFuture<TrackingToken> expectedFuture = completedFuture(aGlobalSequenceToken());
            when(mockStorageEngine.latestToken()).thenReturn(expectedFuture);

            // when
            CompletableFuture<TrackingToken> result = testSubject.latestToken(processingContext);

            // then
            assertSame(expectedFuture, result);
            verify(mockStorageEngine).latestToken();
        }

        @Test
        void tokenAtDelegatesToStorageEngine() {
            // given
            Instant timestamp = Instant.now();
            CompletableFuture<TrackingToken> expectedFuture = completedFuture(aGlobalSequenceToken());
            when(mockStorageEngine.tokenAt(timestamp)).thenReturn(expectedFuture);

            // when
            CompletableFuture<TrackingToken> result = testSubject.tokenAt(timestamp, processingContext);

            // then
            assertSame(expectedFuture, result);
            verify(mockStorageEngine).tokenAt(timestamp);
        }
    }

    @Nested
    class TransactionalAppend {

        @Test
        void appendingWithoutReadMustUseInfinityConsistencyMarker() throws Exception {
            EventStorageEngine.AppendTransaction<?> mockAppendTransaction = mock();
            GlobalIndexConsistencyMarker markerAfterCommit = new GlobalIndexConsistencyMarker(42);

            UnitOfWork unitOfWork = aUnitOfWork();
            when(mockStorageEngine.appendEvents(any(), any(ProcessingContext.class), anyList())).thenReturn(completedFuture(mockAppendTransaction));
            when(mockAppendTransaction.commit()).thenReturn(completedFuture(null));
            when(mockAppendTransaction.afterCommit(any())).thenReturn(completedFuture(markerAfterCommit));
            var result = unitOfWork.executeWithResult(pc -> {
                EventStoreTransaction transaction = testSubject.transaction(pc);
                transaction.appendEvent(createEvent(0));
                return completedFuture(transaction);
            });
            var newAppendPosition = result.get(5, TimeUnit.SECONDS).appendPosition();

            assertSame(newAppendPosition, markerAfterCommit);
            verify(mockStorageEngine).appendEvents(argThat(c -> ConsistencyMarker.INFINITY.equals(c.consistencyMarker())),
                                                   any(ProcessingContext.class),
                                                   anyList());
        }

        @Test
        void appendingAfterReadsUpdatesTheAppendCondition() throws Exception {
            EventStorageEngine.AppendTransaction<?> mockAppendTransaction = mock();
            GlobalIndexConsistencyMarker markerAfterCommit = new GlobalIndexConsistencyMarker(42);

            UnitOfWork unitOfWork = aUnitOfWork();
            when(mockStorageEngine.source(any(), any())).thenReturn(messageStreamOf(10));
            when(mockStorageEngine.appendEvents(any(), any(ProcessingContext.class), anyList())).thenReturn(completedFuture(mockAppendTransaction));
            when(mockAppendTransaction.commit()).thenReturn(completedFuture(null));
            when(mockAppendTransaction.afterCommit(any())).thenReturn(completedFuture(markerAfterCommit));

            var result = unitOfWork.executeWithResult(pc -> {
                EventStoreTransaction transaction = testSubject.transaction(pc);
                doConsumeAll(transaction.source(SourcingCondition.conditionFor(EventCriteria.havingAnyTag())));
                transaction.appendEvent(createEvent(0));
                return completedFuture(transaction);
            });

            var newAppendPosition = result.get(5, TimeUnit.SECONDS).appendPosition();

            assertSame(newAppendPosition, markerAfterCommit);
            verify(mockStorageEngine).appendEvents(argThat(c -> c.consistencyMarker()
                                                                 .equals(new GlobalIndexConsistencyMarker(9))),
                                                   any(ProcessingContext.class),
                                                   anyList());
        }

        static Stream<Arguments> generateRandomNumbers() {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            return Stream.iterate(0, i -> i < 5, i -> i + 1)
                         .map(i -> Arguments.arguments(rnd.nextInt(1, 10),
                                                       rnd.nextInt(1, 10),
                                                       rnd.nextInt(1, 10)));
        }

        @ParameterizedTest
        @MethodSource("generateRandomNumbers")
        void readingMultipleTimesShouldKeepTheConsistencyMarkerAtTheSmallestPosition(int size1, int size2, int size3)
                throws Exception {
            EventStorageEngine.AppendTransaction<?> mockAppendTransaction = mock();
            GlobalIndexConsistencyMarker markerAfterCommit = new GlobalIndexConsistencyMarker(101);

            UnitOfWork unitOfWork = aUnitOfWork();
            when(mockStorageEngine.source(any(), any())).thenReturn(messageStreamOf(size1))
                                                        .thenReturn(messageStreamOf(size2))
                                                        .thenReturn(messageStreamOf(size3));
            when(mockStorageEngine.appendEvents(any(), any(ProcessingContext.class), anyList())).thenReturn(completedFuture(mockAppendTransaction));
            when(mockAppendTransaction.commit()).thenReturn(completedFuture(null));
            when(mockAppendTransaction.afterCommit(any())).thenReturn(completedFuture(markerAfterCommit));
            var result = unitOfWork.executeWithResult(pc -> {
                EventStoreTransaction transaction = testSubject.transaction(pc);
                var firstStream = transaction.source(SourcingCondition.conditionFor(EventCriteria.havingAnyTag()));

                var secondStream = transaction.source(SourcingCondition.conditionFor(EventCriteria.havingAnyTag()));
                var thirdStream = transaction.source(SourcingCondition.conditionFor(EventCriteria.havingAnyTag()));
                doConsumeAll(firstStream, secondStream, thirdStream);
                transaction.appendEvent(createEvent(0));
                return completedFuture(transaction);
            });
            var newAppendPosition = result.get(5, TimeUnit.SECONDS).appendPosition();

            assertSame(newAppendPosition, markerAfterCommit);
            verify(mockStorageEngine).appendEvents(argThat(c -> c.consistencyMarker()
                                                                 .equals(new GlobalIndexConsistencyMarker(
                                                                         Math.min(Math.min(size1, size2), size3) - 1))),
                                                   any(ProcessingContext.class),
                                                   anyList());
        }
    }

    @Nested
    class Publish {

        @Test
        void publishUsesTheGivenContextToInvokeTheTransactionInCompletingTheReturnedFutureImmediately() {
            EventStorageEngine.AppendTransaction<?> mockAppendTransaction = mock();
            when(mockAppendTransaction.commit()).thenReturn(completedFuture(null));
            when(mockAppendTransaction.afterCommit(any())).thenReturn(completedFuture(mock(ConsistencyMarker.class)));
            when(mockStorageEngine.appendEvents(any(), any(ProcessingContext.class), anyList())).thenReturn(completedFuture(mockAppendTransaction));

            EventMessage testEventZero = createEvent(0);
            EventMessage testEventOne = createEvent(1);

            UnitOfWork uow = aUnitOfWork();
            uow.onPreInvocation(context -> {
                   CompletableFuture<Void> result = testSubject.publish(context, testEventZero, testEventOne);
                   assertTrue(result.isDone());
                   assertFalse(result.isCompletedExceptionally());
                   return result;
               })
               .runOnInvocation(context -> verifyNoInteractions(mockStorageEngine))
               .runOnCommit(context -> verify(mockStorageEngine).appendEvents(any(), any(ProcessingContext.class), anyList()));

            awaitSuccessfulCompletion(uow.execute());
        }

        @Test
        void publishInvokeEventStorageEngineRightAwayWithAppendConditionNone(@Captor ArgumentCaptor<List<TaggedEventMessage<?>>> eventCaptor) {
            // given...
            EventStorageEngine.AppendTransaction<String> mockAppendTransaction = mock();
            when(mockAppendTransaction.commit()).thenReturn(completedFuture("anything"));
            when(mockAppendTransaction.afterCommit(any())).thenReturn(completedFuture(mock(ConsistencyMarker.class)));
            when(mockStorageEngine.appendEvents(any(), isNull(), anyList())).thenReturn(completedFuture(mockAppendTransaction));
            Tag testTag = new Tag("id", "value");
            when(tagResolver.resolve(any())).thenReturn(Set.of(testTag));
            EventMessage testEvent = createEvent(0);

            // when...
            CompletableFuture<Void> result = testSubject.publish(null, testEvent);

            // then...
            awaitSuccessfulCompletion(result);
            verify(mockStorageEngine).appendEvents(eq(AppendCondition.none()), isNull(), eventCaptor.capture());
            List<TaggedEventMessage<?>> capturedEvents = eventCaptor.getValue();
            assertEquals(1, capturedEvents.size());
            TaggedEventMessage<?> resultEvent = capturedEvents.getFirst();
            assertEquals(testEvent, resultEvent.event());
            List<Tag> resultTags = resultEvent.tags().stream().toList();
            assertEquals(1, resultTags.size());
            assertEquals(testTag, resultTags.getFirst());
        }

    }


    @Nested
    class Subscription {

        @Test
        void subscribeAddsConsumerAndReturnsRegistration() {
            // given
            RecordingEventListener listener = new RecordingEventListener();

            // when
            var registration = testSubject.subscribe(listener);

            // then
            assertNotNull(registration);
            assertTrue(registration.cancel());
            assertFalse(registration.cancel()); // Second cancellation should return false
        }

        @Test
        void subscribingSameConsumerTwiceOnlyAddsItOnce() {
            // given
            RecordingEventListener listener = new RecordingEventListener();

            // when
            var registration1 = testSubject.subscribe(listener);
            var registration2 = testSubject.subscribe(listener);

            // then
            assertNotNull(registration1);
            assertNotNull(registration2);
            assertTrue(registration1.cancel());
            assertFalse(registration2.cancel()); // Already removed by registration1
        }

        @Test
        void subscribersAreNotifiedWhenEventsArePublishedWithoutContext() {
            // given
            EventStorageEngine.AppendTransaction<String> mockAppendTransaction = mock();
            when(mockAppendTransaction.commit()).thenReturn(completedFuture("anything"));
            when(mockAppendTransaction.afterCommit(any()))
                    .thenReturn(completedFuture(mock(ConsistencyMarker.class)));
            when(mockStorageEngine.appendEvents(any(), isNull(), anyList()))
                    .thenReturn(completedFuture(mockAppendTransaction));
            when(tagResolver.resolve(any())).thenReturn(Set.of());

            RecordingEventListener listener = new RecordingEventListener();
            testSubject.subscribe(listener);
            EventMessage testEvent = createEvent(0);

            // when
            CompletableFuture<Void> result = testSubject.publish(null, testEvent);

            // then
            awaitSuccessfulCompletion(result);
            assertThat(listener.getReceivedEvents())
                    .hasSize(1)
                    .containsExactly(testEvent);
            assertThat(listener.getInvocationCount()).isEqualTo(1);
            assertThat(listener.getCapturedContexts()).containsExactly((ProcessingContext) null);
        }

        @Test
        void subscribersAreNotifiedWhenEventsArePublishedWithContext() {
            // given
            EventStorageEngine.AppendTransaction<?> mockAppendTransaction = mock();
            when(mockAppendTransaction.commit()).thenReturn(completedFuture(null));
            when(mockAppendTransaction.afterCommit(any()))
                    .thenReturn(completedFuture(mock(ConsistencyMarker.class)));
            when(mockStorageEngine.appendEvents(any(), any(ProcessingContext.class), anyList()))
                    .thenReturn(completedFuture(mockAppendTransaction));
            when(tagResolver.resolve(any())).thenReturn(Set.of());

            RecordingEventListener listener = new RecordingEventListener();
            testSubject.subscribe(listener);
            EventMessage testEventZero = createEvent(0);
            EventMessage testEventOne = createEvent(1);

            // when
            UnitOfWork uow = aUnitOfWork();
            uow.onPreInvocation(context -> testSubject.publish(context, testEventZero, testEventOne));

            awaitSuccessfulCompletion(uow.execute());

            // then
            assertThat(listener.getReceivedEvents())
                    .hasSize(2)
                    .containsExactly(testEventZero, testEventOne);
            assertThat(listener.getInvocationCount()).isEqualTo(1);
            assertThat(listener.getCapturedContexts())
                    .hasSize(1)
                    .first()
                    .isNotNull();
        }

        @Test
        void unsubscribedConsumersAreNotNotified() {
            // given
            EventStorageEngine.AppendTransaction<String> mockAppendTransaction = mock();
            when(mockAppendTransaction.commit()).thenReturn(completedFuture("anything"));
            when(mockAppendTransaction.afterCommit(any()))
                    .thenReturn(completedFuture(mock(ConsistencyMarker.class)));
            when(mockStorageEngine.appendEvents(any(), isNull(), anyList()))
                    .thenReturn(completedFuture(mockAppendTransaction));
            when(tagResolver.resolve(any())).thenReturn(Set.of());

            RecordingEventListener listener = new RecordingEventListener();
            var registration = testSubject.subscribe(listener);
            EventMessage testEvent = createEvent(0);

            // when
            registration.cancel();
            CompletableFuture<Void> result = testSubject.publish(null, testEvent);

            // then
            awaitSuccessfulCompletion(result);
            assertThat(listener.getReceivedEvents()).isEmpty();
            assertThat(listener.getInvocationCount()).isEqualTo(0);
        }

        @Test
        void subscriberExceptionPreventsTheEventsFromReachingTheStorageEngine() {
            // given
            EventStorageEngine.AppendTransaction<?> mockAppendTransaction = mock();
            when(mockStorageEngine.appendEvents(any(), any(ProcessingContext.class), anyList()))
                    .thenReturn(completedFuture(mockAppendTransaction));
            when(tagResolver.resolve(any())).thenReturn(Set.of());

            RuntimeException subscriberException = new RuntimeException("Subscriber failed");
            FailingEventListener listener = new FailingEventListener(subscriberException);
            testSubject.subscribe(listener);
            EventMessage testEvent = createEvent(0);

            // when
            UnitOfWork uow = aUnitOfWork();
            uow.onPreInvocation(context -> testSubject.publish(context, testEvent));
            CompletableFuture<Void> result = uow.execute();

            // then subscribers are notified during prepare-commit, and the batch is handed over only after that
            // phase, so a failing subscriber means the storage engine is never asked for a transaction at all
            ExecutionException exception = assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
            assertSame(subscriberException, exception.getCause());
            assertThat(listener.getInvocationCount()).isEqualTo(1);
            verify(mockStorageEngine, never()).appendEvents(any(), any(ProcessingContext.class), anyList());
            verifyNoInteractions(mockAppendTransaction);
        }

        @Test
        void subscribersAreNotifiedWhenEventsAreAppendedViaTransaction() {
            // given
            EventStorageEngine.AppendTransaction<?> mockAppendTransaction = mock();
            when(mockAppendTransaction.commit()).thenReturn(completedFuture(null));
            when(mockAppendTransaction.afterCommit(any()))
                    .thenReturn(completedFuture(mock(ConsistencyMarker.class)));
            when(mockStorageEngine.appendEvents(any(), any(ProcessingContext.class), anyList()))
                    .thenReturn(completedFuture(mockAppendTransaction));
            when(tagResolver.resolve(any())).thenReturn(Set.of());

            RecordingEventListener listener = new RecordingEventListener();
            testSubject.subscribe(listener);
            EventMessage testEventZero = createEvent(0);
            EventMessage testEventOne = createEvent(1);

            // when
            UnitOfWork uow = aUnitOfWork();
            uow.onPreInvocation(context -> {
                EventStoreTransaction transaction = testSubject.transaction(context);
                transaction.appendEvent(testEventZero);
                transaction.appendEvent(testEventOne);
                return completedFuture(null);
            });

            awaitSuccessfulCompletion(uow.execute());

            // then
            assertThat(listener.getReceivedEvents())
                    .hasSize(2)
                    .containsExactly(testEventZero, testEventOne);
            assertThat(listener.getInvocationCount()).isEqualTo(1);
            assertThat(listener.getCapturedContexts())
                    .hasSize(1)
                    .first()
                    .isNotNull();
        }

        // Test listener implementations

        /**
         * Recording listener that tracks all invocations, events, and contexts received.
         */
        private static class RecordingEventListener implements java.util.function.BiFunction<List<? extends EventMessage>, ProcessingContext, CompletableFuture<?>> {
            private final List<EventMessage> receivedEvents = new CopyOnWriteArrayList<>();
            private final List<ProcessingContext> capturedContexts = new CopyOnWriteArrayList<>();
            private int invocationCount = 0;

            @Override
            public CompletableFuture<?> apply(List<? extends EventMessage> events, ProcessingContext context) {
                invocationCount++;
                receivedEvents.addAll(events);
                capturedContexts.add(context);
                return CompletableFuture.completedFuture(null);
            }

            public List<EventMessage> getReceivedEvents() {
                return new ArrayList<>(receivedEvents);
            }

            public List<ProcessingContext> getCapturedContexts() {
                return new ArrayList<>(capturedContexts);
            }

            public int getInvocationCount() {
                return invocationCount;
            }
        }

        /**
         * Listener that throws an exception when invoked.
         */
        private static class FailingEventListener implements java.util.function.BiFunction<List<? extends EventMessage>, ProcessingContext, CompletableFuture<?>> {
            private final RuntimeException exceptionToThrow;
            private int invocationCount = 0;

            public FailingEventListener(RuntimeException exceptionToThrow) {
                this.exceptionToThrow = exceptionToThrow;
            }

            @Override
            public CompletableFuture<?> apply(List<? extends EventMessage> events, ProcessingContext context) {
                invocationCount++;
                throw exceptionToThrow;
            }

            public int getInvocationCount() {
                return invocationCount;
            }
        }
    }

    @Test
    void describeToDescribesPropertiesForEventStorageEngineAndTheContext() {
        // given
        ComponentDescriptor descriptor = mock(ComponentDescriptor.class);

        // when
        testSubject.describeTo(descriptor);

        // then
        verify(descriptor).describeProperty("eventStorageEngine", mockStorageEngine);
    }

    private static @NonNull MessageStream<EventMessage> messageStreamOf(int messageCount) {
        return MessageStream.fromStream(
                IntStream.range(0, messageCount).boxed(),
                EventTestUtils::createEvent,
                i -> ConsistencyMarker.addToContext(Context.empty(), new GlobalIndexConsistencyMarker(i))
        );
    }

    @SafeVarargs
    private void doConsumeAll(MessageStream<? extends EventMessage>... sources) {
        try {
            for (MessageStream<? extends EventMessage> source : sources) {
                source.reduce(new Object(), (o, m) -> m)
                      .get(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            AssertionFailedError assertionFailedError = new AssertionFailedError(
                    "Expected to be able to read from message stream");
            assertionFailedError.addSuppressed(e);
            throw assertionFailedError;
        }
    }

}