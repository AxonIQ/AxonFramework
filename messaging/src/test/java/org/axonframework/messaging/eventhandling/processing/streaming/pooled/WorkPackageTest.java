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

package org.axonframework.messaging.eventhandling.processing.streaming.pooled;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventTestUtils;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.TrackerStatus;
import org.axonframework.messaging.eventhandling.processing.streaming.token.GlobalSequenceTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.ReplayToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.inmemory.InMemoryTokenStore;
import org.axonframework.messaging.core.Context;
import org.axonframework.messaging.core.LegacyResources;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.SimpleEntry;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkTestUtils;
import org.axonframework.common.util.DelegateScheduledExecutorService;
import org.junit.jupiter.api.*;
import org.mockito.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link WorkPackage}.
 *
 * @author Steven van Beelen
 */
class WorkPackageTest {

    private static final String PROCESSOR_NAME = "test";
    private static final Duration TIMEOUT = Duration.ofMillis(500);

    private TokenStore tokenStore;
    private ScheduledExecutorService executorService;
    private TestEventFilter eventFilter;
    private TestBatchProcessor batchProcessor;
    private Segment segment;
    private TrackingToken initialTrackingToken;

    private WorkPackage.Builder testSubjectBuilder;
    private WorkPackage testSubject;

    private TrackerStatus trackerStatus;
    private List<TrackerStatus> trackerStatusUpdates;
    private Predicate<EventMessage> eventFilterPredicate;
    private BiPredicate<List<? extends EventMessage>, TrackingToken> batchProcessorPredicate;

    @BeforeEach
    void setUp() {
        tokenStore = spy(new InMemoryTokenStore());
        executorService = spy(new DelegateScheduledExecutorService(Executors.newScheduledThreadPool(1)));
        eventFilter = spy(new TestEventFilter());
        batchProcessor = new TestBatchProcessor();
        segment = Segment.ROOT_SEGMENT;
        initialTrackingToken = new GlobalSequenceTrackingToken(0L);
        tokenStore.initializeSegment(initialTrackingToken, PROCESSOR_NAME, segment, null);

        trackerStatus = new TrackerStatus(segment, initialTrackingToken);
        trackerStatusUpdates = new ArrayList<>();
        eventFilterPredicate = event -> true;
        batchProcessorPredicate = (event, token) -> true;

        testSubjectBuilder = WorkPackage.builder()
                                        .name(PROCESSOR_NAME)
                                        .tokenStore(tokenStore)
                                        .unitOfWorkFactory(UnitOfWorkTestUtils.SIMPLE_FACTORY)
                                        .executorService(executorService)
                                        .eventFilter(eventFilter)
                                        .batchProcessor(batchProcessor)
                                        .segment(segment)
                                        .initialToken(initialTrackingToken)
                                        .batchSize(1)
                                        .claimExtensionThreshold(5000)
                                        .segmentStatusUpdater(op -> {
                                            TrackerStatus update = op.apply(trackerStatus);
                                            trackerStatusUpdates.add(update);
                                            trackerStatus = update;
                                        });
        testSubject = testSubjectBuilder.build();
    }

    @AfterEach
    void tearDown() {
        executorService.shutdown();
    }

    /**
     * The "last delivered token" is configured as the initialToken for a fresh WorkPackage.
     */
    @Test
    void scheduleEventDoesNotScheduleIfTheLastDeliveredTokenCoversTheEventsToken() {
        var testEvent = new SimpleEntry<>(EventTestUtils.asEventMessage("some-event"), globalTrackingTokenContext(1L));

        WorkPackage testSubjectWithCustomInitialToken =
                testSubjectBuilder.initialToken(new GlobalSequenceTrackingToken(2L))
                                  .build();

        testSubjectWithCustomInitialToken.scheduleEvent(testEvent);

        verifyNoInteractions(executorService);
    }

    @Test
    void scheduleEventUpdatesLastDeliveredToken() {
        TrackingToken expectedToken = new GlobalSequenceTrackingToken(1L);
        var testEvent = new SimpleEntry<>(EventTestUtils.asEventMessage("some-event"), globalTrackingTokenContext(1L));


        testSubject.scheduleEvent(testEvent);

        assertEquals(expectedToken, testSubject.lastDeliveredToken());
    }

    @Test
    void scheduleEventIsFilteredWithContextResourcesFromTheEventEntry() throws Exception {
        // given
        TrackingToken expectedToken = new GlobalSequenceTrackingToken(1L);
        var aggregateIdentifier = "aggregate-1";
        Context context = globalTrackingTokenContext(1L).withResource(LegacyResources.AGGREGATE_IDENTIFIER_KEY, aggregateIdentifier);
        var testEvent = new SimpleEntry<>(EventTestUtils.asEventMessage("some-event"), context);

        // when
        testSubject.scheduleEvent(testEvent);

        // then
        verify(eventFilter).canHandle(any(EventMessage.class), argThat(processingContext -> {
            // Verify ProcessingContext contains the tracking token from the event entry
            var trackingTokenAsExpected = TrackingToken.fromContext(processingContext)
                    .map(expectedToken::equals)
                    .orElse(false);
            var aggregateIdentifierAsExpected = aggregateIdentifier.equals(
                    processingContext.getResource(LegacyResources.AGGREGATE_IDENTIFIER_KEY)
            );
            return trackingTokenAsExpected && aggregateIdentifierAsExpected;
        }), any(Segment.class));
    }

    @Test
    void scheduleEventFailsOnEventValidator() throws ExecutionException, InterruptedException {
        TrackingToken testToken = new GlobalSequenceTrackingToken(1L);
        var testMessage = EventTestUtils.asEventMessage("some-event");
        var testEvent = new SimpleEntry<>(testMessage, trackingTokenContext(testToken));

        eventFilterPredicate = event -> {
            if (event.equals(testMessage)) {
                throw new IllegalStateException("Some exception");
            }
            return true;
        };

        testSubject.scheduleEvent(testEvent);

        await().atMost(TIMEOUT).untilAsserted(() -> assertNull(trackerStatus));
        assertEquals(2, trackerStatusUpdates.size());
        assertTrue(trackerStatusUpdates.get(0).isErrorState());
        assertNull(trackerStatusUpdates.get(1));

        CompletableFuture<Throwable> abortResult = testSubject.abort(null);
        assertTrue(abortResult.isDone());
        assertTrue(abortResult.get().getClass().isAssignableFrom(IllegalStateException.class));
    }

    @Test
    void scheduleEventFailsOnBatchProcessor() throws ExecutionException, InterruptedException {
        TrackingToken testToken = new GlobalSequenceTrackingToken(1L);
        var testMessage = EventTestUtils.asEventMessage("some-event");
        var testEvent = new SimpleEntry<>(testMessage, trackingTokenContext(testToken));
        batchProcessorPredicate = (event, token) -> {
            if (event.stream().anyMatch(e -> token.equals(testToken))) {
                throw new IllegalStateException("Some exception");
            }
            return true;
        };

        testSubject.scheduleEvent(testEvent);

        await().atMost(TIMEOUT).untilAsserted(() -> assertNull(trackerStatus));
        assertEquals(2, trackerStatusUpdates.size());
        assertTrue(trackerStatusUpdates.get(0).isErrorState());
        assertNull(trackerStatusUpdates.get(1));

        CompletableFuture<Throwable> abortResult = testSubject.abort(null);
        assertTrue(abortResult.isDone());
        assertTrue(abortResult.get().getClass().isAssignableFrom(IllegalStateException.class));
    }

    /**
     * This means an event was scheduled, was validated to be handled by the EventValidator, processed by the
     * BatchProcessor and the updated token stored.
     */
    @Test
    void scheduleEventRunsSuccessfully() {
        TrackingToken expectedToken = new GlobalSequenceTrackingToken(1L);
        var testMessage = EventTestUtils.asEventMessage("some-event");
        var expectedEvent = new SimpleEntry<>(testMessage, trackingTokenContext(expectedToken));

        testSubject.scheduleEvent(expectedEvent);

        List<EventMessage> validatedEvents = eventFilter.getValidatedEvents();
        await().atMost(TIMEOUT).untilAsserted(() -> assertEquals(1, validatedEvents.size()));
        assertEquals(testMessage, validatedEvents.getFirst());

        var processedEvents = batchProcessor.getProcessedEvents();
        await().atMost(TIMEOUT).untilAsserted(() -> assertEquals(1, processedEvents.size()));
        assertEquals(expectedToken, TrackingToken.fromContext(processedEvents.getFirst().context()).get());

        await().atMost(TIMEOUT).untilAsserted(() -> {
            ArgumentCaptor<TrackingToken> tokenCaptor = ArgumentCaptor.forClass(TrackingToken.class);
            verify(tokenStore).storeToken(
                    tokenCaptor.capture(),
                    eq(PROCESSOR_NAME),
                    eq(segment.getSegmentId()),
                    any(ProcessingContext.class)
            );
            assertEquals(expectedToken, tokenCaptor.getValue());
            // status update are sent temporarily, so we can't guarantee when they are sent. But at least one must have been sent.
            assertThat(trackerStatusUpdates.size()).isGreaterThanOrEqualTo(1);
        });
        OptionalLong resultPosition = trackerStatusUpdates.getFirst().getCurrentPosition();
        assertTrue(resultPosition.isPresent());
        assertEquals(1L, resultPosition.getAsLong());
    }

    @Test
    void replayTokenIsPropagatedAndAdvancedWithoutCurrent() {
        testSubjectBuilder.initialToken(ReplayToken.createReplayToken(new GlobalSequenceTrackingToken(1L)));
        testSubject = testSubjectBuilder.build();
        TrackingToken expectedToken = new GlobalSequenceTrackingToken(1L);
        var expectedPayload = EventTestUtils.asEventMessage("some-event");
        var expectedEvent = new SimpleEntry<>(expectedPayload, trackingTokenContext(expectedToken));

        testSubject.scheduleEvent(expectedEvent);

        var processedEvents = batchProcessor.getProcessedEvents();
        await().atMost(TIMEOUT).untilAsserted(() -> assertEquals(1, processedEvents.size()));

        TrackingToken resultAdvancedToken = TrackingToken.fromContext(processedEvents.getFirst().context()).get();
        assertInstanceOf(ReplayToken.class, resultAdvancedToken);
        assertEquals(expectedToken, ((ReplayToken) resultAdvancedToken).getCurrentToken());
        assertEquals(expectedToken, ((ReplayToken) resultAdvancedToken).getTokenAtReset());
    }


    @Test
    void replayTokenIsPropagatedAndAdvancedWithCurrent() {
        testSubjectBuilder.initialToken(ReplayToken.createReplayToken(new GlobalSequenceTrackingToken(1L),
                                                                      new GlobalSequenceTrackingToken(0L)));
        testSubject = testSubjectBuilder.build();
        TrackingToken expectedToken = new GlobalSequenceTrackingToken(1L);
        var expectedPayload = EventTestUtils.asEventMessage("some-event");
        var expectedEvent = new SimpleEntry<>(expectedPayload, trackingTokenContext(expectedToken));

        testSubject.scheduleEvent(expectedEvent);

        var processedEvents = batchProcessor.getProcessedEvents();
        await().atMost(TIMEOUT).untilAsserted(() -> assertEquals(1, processedEvents.size()));

        TrackingToken resultAdvancedToken = TrackingToken.fromContext(processedEvents.getFirst().context()).get();
        assertInstanceOf(ReplayToken.class, resultAdvancedToken);
        assertEquals(expectedToken, ((ReplayToken) resultAdvancedToken).getCurrentToken());
        assertEquals(expectedToken, ((ReplayToken) resultAdvancedToken).getTokenAtReset());
    }

    @Test
    void scheduleEventExtendsTokenClaimAfterClaimThresholdExtension() {
        // The short threshold ensures the packages assume the token should be reclaimed.
        int extremelyShortClaimExtensionThreshold = 1;
        WorkPackage testSubjectWithShortThreshold =
                testSubjectBuilder.claimExtensionThreshold(extremelyShortClaimExtensionThreshold)
                                  .build();

        TrackingToken expectedToken = new GlobalSequenceTrackingToken(1L);
        var expectedPayload = EventTestUtils.asEventMessage("some-event");
        var expectedEvent = new SimpleEntry<>(expectedPayload, trackingTokenContext(expectedToken));
        testSubjectWithShortThreshold.scheduleEvent(expectedEvent);

        // Should have handled one event, so a subsequent run of WorkPackage#processEvents will extend the claim.
        var processedEvents = batchProcessor.getProcessedEvents();
        await().atMost(TIMEOUT).untilAsserted(() -> assertEquals(1, processedEvents.size()));
        assertEquals(expectedToken, TrackingToken.fromContext(processedEvents.getFirst().context()).get());
        // We need to verify the TokenStore#storeToken operation, otherwise the extendClaim verify will not succeed.
        ArgumentCaptor<TrackingToken> tokenCaptor = ArgumentCaptor.forClass(TrackingToken.class);
        await().atMost(TIMEOUT).untilAsserted(() -> {
            verify(tokenStore).storeToken(
                    tokenCaptor.capture(),
                    eq(PROCESSOR_NAME),
                    eq(segment.getSegmentId()),
                    any(ProcessingContext.class)
            );
            assertEquals(expectedToken, tokenCaptor.getValue());
        });

        await().atMost(TIMEOUT).untilAsserted(() -> {
            // Consciously trigger the WorkPackage again to force it through WorkPackage#processEvents.
            // This should be done inside the await, as the WorkPackage does not re-trigger itself.
            // Furthermore, the test could be too fast to incorporate the extremelyShortClaimExtensionThreshold as a reason to extend the claim too.
            testSubjectWithShortThreshold.scheduleWorker();
            verify(tokenStore, atLeastOnce())
                    .extendClaim(eq(PROCESSOR_NAME), eq(segment.getSegmentId()), any());
        });
    }

    /**
     * This requires the WorkPackage to have received events which it should not handle.
     */
    @Test
    void scheduleEventUpdatesTokenAfterClaimThresholdExtension() {
        // The short threshold ensures the packages assume the token should be reclaimed.
        int extremelyShortClaimExtensionThreshold = 1;
        WorkPackage testSubjectWithShortThreshold =
                testSubjectBuilder.claimExtensionThreshold(extremelyShortClaimExtensionThreshold)
                                  .build();
        // Adjust the EventValidator to reject all events
        eventFilterPredicate = event -> false;

        TrackingToken expectedToken = new GlobalSequenceTrackingToken(1L);
        var expectedPayload = EventTestUtils.asEventMessage("some-event");
        var expectedEvent = new SimpleEntry<>(expectedPayload, trackingTokenContext(expectedToken));
        testSubjectWithShortThreshold.scheduleEvent(expectedEvent);

        var validatedEvents = eventFilter.getValidatedEvents();
        await().atMost(TIMEOUT).untilAsserted(() -> assertEquals(1, validatedEvents.size()));
        assertEquals(expectedPayload, validatedEvents.getFirst());

        ArgumentCaptor<TrackingToken> tokenCaptor = ArgumentCaptor.forClass(TrackingToken.class);

        await().atMost(TIMEOUT).untilAsserted(() -> {
            // Consciously trigger the WorkPackage again, to force it through WorkPackage#processEvents.
            // This should be done inside the await, as the WorkPackage does not re-trigger itself.
            // Furthermore, the test could be to fast to incorporate the extremelyShortClaimExtensionThreshold as a reason to extend the claim too.
            testSubjectWithShortThreshold.scheduleWorker();
            verify(tokenStore, atLeastOnce())
                    .storeToken(
                            tokenCaptor.capture(),
                            eq(PROCESSOR_NAME),
                            eq(segment.getSegmentId()),
                            any(ProcessingContext.class));
        });
        assertEquals(expectedToken, tokenCaptor.getValue());
    }

    @Test
    void scheduleWorkerForAbortedPackage() throws ExecutionException, InterruptedException {
        CompletableFuture<Throwable> result = testSubject.abort(null);

        testSubject.scheduleWorker();

        await().atMost(TIMEOUT).untilAsserted(() -> assertNull(trackerStatus));
        await().atMost(TIMEOUT).untilAsserted(() -> assertTrue(result.isDone()));
        assertNull(result.get());
    }

    @Test
    void hasRemainingCapacityReturnsTrueForWorkPackageWithoutScheduledEvents() {
        assertTrue(testSubject.hasRemainingCapacity());
    }

    @Test
    void segment() {
        assertEquals(segment, testSubject.segment());
    }

    @Test
    void lastDeliveredTokenEqualsInitialTokenWhenNoEventsHaveBeenScheduled() {
        assertEquals(initialTrackingToken, testSubject.lastDeliveredToken());
    }

    @Test
    void isAbortTriggeredReturnsFalseInAbsenceOfAbort() {
        assertFalse(testSubject.isAbortTriggered());
    }

    @Test
    void isAbortTriggeredReturnsTrueAfterAbortInvocation() {
        testSubject.abort(null);
        assertTrue(testSubject.isAbortTriggered());
    }

    @Test
    void abortReturnsAbortReason() throws ExecutionException, InterruptedException {
        Exception expectedResult = new IllegalStateException();

        CompletableFuture<Throwable> result = testSubject.abort(expectedResult);

        await().atMost(TIMEOUT).untilAsserted(() -> assertTrue(result.isDone()));
        assertEquals(expectedResult, result.get());
    }

    @Test
    void abortReturnsOriginalAbortReason() throws ExecutionException, InterruptedException {
        Exception originalAbortReason = new IllegalStateException();
        Exception otherAbortReason = new IllegalArgumentException();
        testSubject.abort(originalAbortReason);

        CompletableFuture<Throwable> result = testSubject.abort(otherAbortReason);

        await().atMost(TIMEOUT).untilAsserted(() -> assertTrue(result.isDone()));
        assertEquals(originalAbortReason, result.get());
    }

    @Test
    void scheduleEventsReturnsFalseForEmptyList() {
        assertFalse(testSubject.scheduleEvents(Collections.emptyList()));
    }

    @Test
    void scheduleEventsThrowsIllegalArgumentExceptionForNoneMatchingTokens() {
        TrackingToken testTokenOne = new GlobalSequenceTrackingToken(1L);
        var testEventOne = new SimpleEntry<>(EventTestUtils.asEventMessage("some-event"),
                                             trackingTokenContext(testTokenOne));
        TrackingToken testTokenTwo = new GlobalSequenceTrackingToken(2L);
        var testEventTwo = new SimpleEntry<>(EventTestUtils.asEventMessage("some-event"),
                                             trackingTokenContext(testTokenTwo));
        List<MessageStream.Entry<? extends EventMessage>> testEvents = new ArrayList<>();
        testEvents.add(testEventOne);
        testEvents.add(testEventTwo);

        assertThrows(IllegalArgumentException.class, () -> testSubject.scheduleEvents(testEvents));
    }

    /**
     * The "last delivered token" is configured as the initialToken for a fresh WorkPackage.
     */
    @Test
    void scheduleEventsDoesNotScheduleIfTheLastDeliveredTokensCoversTheEventsToken() {
        TrackingToken testToken = new GlobalSequenceTrackingToken(1L);
        var testEventOne = new SimpleEntry<>(EventTestUtils.asEventMessage("some-event"),
                                             trackingTokenContext(testToken));
        var testEventTwo = new SimpleEntry<>(EventTestUtils.asEventMessage("some-event"),
                                             trackingTokenContext(testToken));
        List<MessageStream.Entry<? extends EventMessage>> testEvents = new ArrayList<>();
        testEvents.add(testEventOne);
        testEvents.add(testEventTwo);

        WorkPackage testSubjectWithCustomInitialToken =
                testSubjectBuilder.initialToken(new GlobalSequenceTrackingToken(2L))
                                  .build();

        boolean result = testSubjectWithCustomInitialToken.scheduleEvents(testEvents);

        assertFalse(result);
        verifyNoInteractions(executorService);
    }

    @Test
    void scheduleEventsReturnsTrueIfOnlyOneEventIsAcceptedByTheEventValidator() {
        TrackingToken expectedToken = new GlobalSequenceTrackingToken(1L);
        var filteredEvent = new SimpleEntry<>(EventTestUtils.asEventMessage("some-event"),
                                              trackingTokenContext(expectedToken));
        var expectedEvent = new SimpleEntry<>(EventTestUtils.asEventMessage("some-event"),
                                              trackingTokenContext(expectedToken));
        List<MessageStream.Entry<? extends EventMessage>> testEvents = new ArrayList<>();
        testEvents.add(filteredEvent);
        testEvents.add(expectedEvent);

        eventFilterPredicate = event -> !event.equals(filteredEvent.message());

        boolean result = testSubject.scheduleEvents(testEvents);

        assertTrue(result);

        List<EventMessage> validatedEvents = eventFilter.getValidatedEvents();
        await().atMost(TIMEOUT).untilAsserted(() -> assertEquals(2, validatedEvents.size()));
        assertTrue(validatedEvents.containsAll(testEvents.stream().map(MessageStream.Entry::message).toList()));

        var processedEvents = batchProcessor.getProcessedEvents();
        await().atMost(TIMEOUT).untilAsserted(() -> assertEquals(1, processedEvents.size()));
        assertEquals(expectedToken, TrackingToken.fromContext(processedEvents.getFirst().context).get());

        await().atMost(TIMEOUT).untilAsserted(() -> {
            ArgumentCaptor<TrackingToken> tokenCaptor = ArgumentCaptor.forClass(TrackingToken.class);
            verify(tokenStore).storeToken(
                    tokenCaptor.capture(),
                    eq(PROCESSOR_NAME),
                    eq(segment.getSegmentId()),
                    any(ProcessingContext.class));
            assertEquals(expectedToken, tokenCaptor.getValue());

            assertEquals(1, trackerStatusUpdates.size());
            OptionalLong resultPosition = trackerStatusUpdates.get(0).getCurrentPosition();
            assertTrue(resultPosition.isPresent());
            assertEquals(1L, resultPosition.getAsLong());
        });
    }

    @Test
    void scheduleEventsHandlesAllEventsInOneTransactionWhenAllEventsCanBeHandled() {
        TrackingToken expectedToken = new GlobalSequenceTrackingToken(1L);
        var expectedEventOne = new SimpleEntry<>(EventTestUtils.asEventMessage("some-event"),
                                                 trackingTokenContext(expectedToken));
        var expectedEventTwo = new SimpleEntry<>(EventTestUtils.asEventMessage("some-event"),
                                                 trackingTokenContext(expectedToken));
        List<MessageStream.Entry<? extends EventMessage>> expectedEvents = new ArrayList<>();
        expectedEvents.add(expectedEventOne);
        expectedEvents.add(expectedEventTwo);

        boolean result = testSubject.scheduleEvents(expectedEvents);

        assertTrue(result);

        List<EventMessage> validatedEvents = eventFilter.getValidatedEvents();
        await().atMost(TIMEOUT).untilAsserted(() -> assertEquals(2, validatedEvents.size()));
        assertTrue(validatedEvents.containsAll(expectedEvents.stream().map(MessageStream.Entry::message).toList()));

        var processedEvents = batchProcessor.getProcessedEvents();
        await().atMost(TIMEOUT).untilAsserted(() -> assertEquals(2, processedEvents.size()));
        assertEquals(expectedToken,
                     TrackingToken.fromContext(processedEvents.get(0).context).get());
        assertEquals(expectedToken,
                     TrackingToken.fromContext(processedEvents.get(1).context).get());

        await().atMost(TIMEOUT).untilAsserted(() -> {
            ArgumentCaptor<TrackingToken> tokenCaptor = ArgumentCaptor.forClass(TrackingToken.class);
            verify(tokenStore).storeToken(
                    tokenCaptor.capture(),
                    eq(PROCESSOR_NAME),
                    eq(segment.getSegmentId()),
                    any(ProcessingContext.class));
            assertEquals(expectedToken, tokenCaptor.getValue());

            assertFalse(trackerStatusUpdates.isEmpty());
            OptionalLong resultPosition = trackerStatusUpdates.get(0).getCurrentPosition();
            assertTrue(resultPosition.isPresent());
            assertEquals(1L, resultPosition.getAsLong());
        });
    }


    @Nested
    class WhenProcessingBatch {

        @Test
        void processBatchSurfacesPerEventTokenInEntryContext() {
            // given — defer worker so all three scheduleEvent calls populate the queue before processing starts
            List<Runnable> deferredWorkerTasks = new ArrayList<>();
            doAnswer(inv -> {
                deferredWorkerTasks.add(inv.getArgument(0));
                return CompletableFuture.completedFuture(null);
            }).when(executorService).submit(any(Runnable.class));

            WorkPackage subject = testSubjectBuilder.batchSize(3).build();

            // when
            subject.scheduleEvent(new SimpleEntry<>(EventTestUtils.asEventMessage("event-1"), globalTrackingTokenContext(1L)));
            subject.scheduleEvent(new SimpleEntry<>(EventTestUtils.asEventMessage("event-2"), globalTrackingTokenContext(2L)));
            subject.scheduleEvent(new SimpleEntry<>(EventTestUtils.asEventMessage("event-3"), globalTrackingTokenContext(3L)));
            runDeferred(deferredWorkerTasks);

            assertEquals(3, batchProcessor.getProcessedEvents().size());
            ContextMessage event0 = batchProcessor.getProcessedEvents().get(0);
            ContextMessage event1 = batchProcessor.getProcessedEvents().get(1);
            ContextMessage event2 = batchProcessor.getProcessedEvents().get(2);

            // then — each entry's context must carry its own token, not the batch-end token
            assertThat(TrackingToken.fromContext(event0.context()))
                    .hasValue(new GlobalSequenceTrackingToken(1L));
            assertThat(TrackingToken.fromContext(event1.context()))
                    .hasValue(new GlobalSequenceTrackingToken(2L));
            assertThat(TrackingToken.fromContext(event2.context()))
                    .hasValue(new GlobalSequenceTrackingToken(3L));
        }

        @Test
        void processBatchExposesBatchEndTokenAsLastEventTokenInBatchContext() {
            // given — defer worker so all three scheduleEvent calls populate the queue before processing starts
            List<Runnable> deferredWorkerTasks = new ArrayList<>();
            doAnswer(inv -> {
                deferredWorkerTasks.add(inv.getArgument(0));
                return CompletableFuture.completedFuture(null);
            }).when(executorService).submit(any(Runnable.class));

            List<TrackingToken> capturedBatchEndTokens = new ArrayList<>();
            WorkPackage subject = testSubjectBuilder
                    .batchSize(3)
                    .batchProcessor((entries, ctx) -> {
                        // capture the batch-end token once per event — all three must see the same value
                        entries.forEach(e -> capturedBatchEndTokens.add(ctx.getResource(TrackingToken.BATCH_END_RESOURCE_KEY)));
                        return MessageStream.empty();
                    })
                    .build();

            // when
            subject.scheduleEvent(new SimpleEntry<>(EventTestUtils.asEventMessage("event-1"), globalTrackingTokenContext(1L)));
            subject.scheduleEvent(new SimpleEntry<>(EventTestUtils.asEventMessage("event-2"), globalTrackingTokenContext(2L)));
            subject.scheduleEvent(new SimpleEntry<>(EventTestUtils.asEventMessage("event-3"), globalTrackingTokenContext(3L)));
            runDeferred(deferredWorkerTasks);

            // then — each event in the batch sees the same batch-end token: the last event's position
            TrackingToken expectedBatchEnd = new GlobalSequenceTrackingToken(3L);
            assertThat(capturedBatchEndTokens)
                    .hasSize(3)
                    .containsOnly(expectedBatchEnd);
        }

        @Test
        void processBatchSurfacesPerEventReplayTokenInEntryContext() {
            // given — reset at position 3, so events at 1, 2, 3 are all replay events
            // defer worker so all three events are in the queue before processing begins
            List<Runnable> deferredWorkerTasks = new ArrayList<>();
            doAnswer(inv -> {
                deferredWorkerTasks.add(inv.getArgument(0));
                return CompletableFuture.completedFuture(null);
            }).when(executorService).submit(any(Runnable.class));

            TrackingToken tokenAtReset = new GlobalSequenceTrackingToken(3L);
            WorkPackage subject = testSubjectBuilder
                    .initialToken(ReplayToken.createReplayToken(tokenAtReset))
                    .batchSize(3)
                    .build();

            // when
            subject.scheduleEvent(new SimpleEntry<>(EventTestUtils.asEventMessage("event-1"), globalTrackingTokenContext(1L)));
            subject.scheduleEvent(new SimpleEntry<>(EventTestUtils.asEventMessage("event-2"), globalTrackingTokenContext(2L)));
            subject.scheduleEvent(new SimpleEntry<>(EventTestUtils.asEventMessage("event-3"), globalTrackingTokenContext(3L)));
            runDeferred(deferredWorkerTasks);

            // then — each entry's context must carry a ReplayToken advanced to its own position
            assertEquals(3, batchProcessor.getProcessedEvents().size());
            TrackingToken t0 = TrackingToken.fromContext(batchProcessor.getProcessedEvents().get(0).context()).get();
            TrackingToken t1 = TrackingToken.fromContext(batchProcessor.getProcessedEvents().get(1).context()).get();
            TrackingToken t2 = TrackingToken.fromContext(batchProcessor.getProcessedEvents().get(2).context()).get();

            assertInstanceOf(ReplayToken.class, t0);
            assertEquals(new GlobalSequenceTrackingToken(1L), ((ReplayToken) t0).getCurrentToken());
            assertEquals(tokenAtReset, ((ReplayToken) t0).getTokenAtReset());

            assertInstanceOf(ReplayToken.class, t1);
            assertEquals(new GlobalSequenceTrackingToken(2L), ((ReplayToken) t1).getCurrentToken());
            assertEquals(tokenAtReset, ((ReplayToken) t1).getTokenAtReset());

            assertInstanceOf(ReplayToken.class, t2);
            assertEquals(new GlobalSequenceTrackingToken(3L), ((ReplayToken) t2).getCurrentToken());
            assertEquals(tokenAtReset, ((ReplayToken) t2).getTokenAtReset());
        }

        @Test
        void processBatchExposesBatchEndTokenViaResourceKey() {
            // given — use scheduleEvents (plural) to guarantee all three events are wrapped in one
            // BatchProcessingEntry and therefore processed as a single batch
            List<TrackingToken> capturedBatchEndTokens = new ArrayList<>();
            WorkPackage subject = testSubjectBuilder
                    .batchSize(3)
                    .batchProcessor((entries, ctx) -> {
                        capturedBatchEndTokens.add(ctx.getResource(TrackingToken.BATCH_END_RESOURCE_KEY));
                        return MessageStream.empty();
                    })
                    .build();

            TrackingToken sharedToken = new GlobalSequenceTrackingToken(3L);
            Context sharedContext = trackingTokenContext(sharedToken);

            // when — three events sharing one token are atomically enqueued as one batch
            subject.scheduleEvents(List.of(
                    new SimpleEntry<>(EventTestUtils.asEventMessage("event-1"), sharedContext),
                    new SimpleEntry<>(EventTestUtils.asEventMessage("event-2"), sharedContext),
                    new SimpleEntry<>(EventTestUtils.asEventMessage("event-3"), sharedContext)
            ));

            // then — exactly one batch is processed and its BATCH_END equals the shared token
            await().atMost(TIMEOUT).untilAsserted(() -> {
                assertThat(capturedBatchEndTokens).hasSize(1);
                assertThat(capturedBatchEndTokens.getFirst()).isEqualTo(sharedToken);
            });
        }
    }

    private static void runDeferred(List<Runnable> tasks) {
        while (!tasks.isEmpty()) {
            List<Runnable> snapshot = new ArrayList<>(tasks);
            tasks.clear();
            snapshot.forEach(Runnable::run);
        }
    }

    @Nested
    class ScheduleWorkerLifecycleTest {

        @Test
        void processingEventsIsFalseAfterSuccessfulProcessing() {
            // given
            TrackingToken testToken = new GlobalSequenceTrackingToken(1L);
            var testEvent = new SimpleEntry<>(EventTestUtils.asEventMessage("some-event"), trackingTokenContext(testToken));

            // when
            testSubject.scheduleEvent(testEvent);

            // then
            await().atMost(TIMEOUT).untilAsserted(() -> {
                verify(tokenStore).storeToken(any(), eq(PROCESSOR_NAME), eq(segment.getSegmentId()), any());
                assertThat(testSubject.isProcessingEvents()).isFalse();
            });
        }

        @Test
        void processingEventsIsFalseAfterFailedProcessing() {
            // given
            TrackingToken testToken = new GlobalSequenceTrackingToken(1L);
            var testEvent = new SimpleEntry<>(EventTestUtils.asEventMessage("some-event"), trackingTokenContext(testToken));
            batchProcessorPredicate = (events, token) -> {
                throw new IllegalStateException("processing failure");
            };

            // when
            testSubject.scheduleEvent(testEvent);

            // then - wait for abort to be fully processed (trackerStatus set to null by abort handler)
            await().atMost(TIMEOUT).untilAsserted(() -> assertThat(trackerStatus).isNull());
            assertThat(testSubject.isProcessingEvents()).isFalse();
        }

        @Test
        void workerReschedulesWhenQueueHasRemainingEventsAfterBatch() {
            // given - two events with different tokens; batchSize is 1 so each is processed separately
            TrackingToken tokenOne = new GlobalSequenceTrackingToken(1L);
            TrackingToken tokenTwo = new GlobalSequenceTrackingToken(2L);
            var eventOne = new SimpleEntry<>(EventTestUtils.asEventMessage("event-one"), trackingTokenContext(tokenOne));
            var eventTwo = new SimpleEntry<>(EventTestUtils.asEventMessage("event-two"), trackingTokenContext(tokenTwo));

            // when
            testSubject.scheduleEvent(eventOne);
            testSubject.scheduleEvent(eventTwo);

            // then - second event is only reachable if scheduleWorker reschedules after the first batch
            await().atMost(TIMEOUT).untilAsserted(
                    () -> assertThat(batchProcessor.getProcessedEvents()).hasSize(2)
            );
        }

        @Test
        void tokenStoreFailureTriggersAbort() {
            // given
            TrackingToken testToken = new GlobalSequenceTrackingToken(1L);
            var testEvent = new SimpleEntry<>(EventTestUtils.asEventMessage("some-event"), trackingTokenContext(testToken));
            doReturn(CompletableFuture.failedFuture(new RuntimeException("store failure")))
                    .when(tokenStore).storeToken(any(), anyString(), anyInt(), any());

            // when
            testSubject.scheduleEvent(testEvent);

            // then
            await().atMost(TIMEOUT).untilAsserted(() -> assertThat(trackerStatus).isNull());
            assertThat(testSubject.abort(null)).isDone();
        }
    }

    private static Context globalTrackingTokenContext(long globalIndex) {
        return trackingTokenContext(new GlobalSequenceTrackingToken(globalIndex));
    }

    private static Context trackingTokenContext(TrackingToken token) {
        return TrackingToken.addToContext(
                Context.empty(), token);
    }

    private class TestEventFilter implements WorkPackage.EventFilter {

        private final List<EventMessage> validatedEvents = new ArrayList<>();

        @Override
        public boolean canHandle(EventMessage eventMessage, ProcessingContext context, Segment segment)
                throws Exception {
            validatedEvents.add(eventMessage);
            return eventFilterPredicate.test(eventMessage);
        }

        public List<EventMessage> getValidatedEvents() {
            return validatedEvents;
        }
    }

    private class TestBatchProcessor implements WorkPackage.BatchProcessor {

        private final List<ContextMessage> processedEvents = new ArrayList<>();

        @Override
        public MessageStream.Empty<Message> process(@NonNull List<MessageStream.Entry<? extends EventMessage>> entries, @NonNull ProcessingContext context) {
            List<? extends EventMessage> events = entries.stream().map(MessageStream.Entry::message).toList();
            if (batchProcessorPredicate.test(events, context.getResource(TrackingToken.BATCH_END_RESOURCE_KEY))) {
                processedEvents.addAll(entries.stream().map(e -> new ContextMessage(e.message(), e)).toList());
            }
            return MessageStream.empty();
        }

        public List<ContextMessage> getProcessedEvents() {
            return processedEvents;
        }
    }

    record ContextMessage(EventMessage message, Context context) {

    }
}
