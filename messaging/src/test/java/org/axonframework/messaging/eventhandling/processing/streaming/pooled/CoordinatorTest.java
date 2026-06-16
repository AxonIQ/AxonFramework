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

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.axonframework.common.FutureUtils.emptyCompletedFuture;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.axonframework.common.FutureUtils;
import org.axonframework.common.ProcessRetriesExhaustedException;
import org.axonframework.common.ReflectionUtils;
import org.axonframework.messaging.core.Context;
import org.axonframework.messaging.core.EmptyApplicationContext;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.SimpleEntry;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.SimpleUnitOfWorkFactory;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventTestUtils;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.SegmentChangeListener;
import org.axonframework.messaging.eventhandling.processing.streaming.token.GlobalSequenceTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.ReplayToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.UnableToClaimTokenException;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.StreamableEventSource;
import org.axonframework.messaging.eventstreaming.StreamingCondition;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.*;
import org.mockito.*;
import org.mockito.stubbing.*;

/**
 * Test class validating the {@link Coordinator}.
 *
 * @author Fabio Couto
 */
class CoordinatorTest {

    private static final String PROCESSOR_NAME = "test";
    private final Segment SEGMENT_ZERO = Segment.ROOT_SEGMENT;
    private final int SEGMENT_ID = 0;
    private final List<Segment> SEGMENTS = List.of(Segment.ROOT_SEGMENT);
    private final Segment SEGMENT_ONE = new Segment(1, 1);
    private final TokenStore tokenStore = mock(TokenStore.class);
    private final ScheduledThreadPoolExecutor executorService = mock(ScheduledThreadPoolExecutor.class);
    private final StreamableEventSource messageSource = mock(StreamableEventSource.class);
    private final WorkPackage workPackage = mock(WorkPackage.class);
    private Coordinator testSubject;

    private static Context trackingTokenContext(TrackingToken token) {
        return TrackingToken.addToContext(
                Context.empty(), token);
    }

    static @NonNull StreamingCondition streamingFrom(TrackingToken testToken) {
        return StreamingCondition.conditionFor(testToken, EventCriteria.havingAnyTag());
    }

    @BeforeEach
    void setUp() {
        when(messageSource.latestToken(null)).thenReturn(FutureUtils.emptyCompletedFuture());
        when(messageSource.firstToken(null)).thenReturn(FutureUtils.emptyCompletedFuture());
        testSubject = buildCoordinator();
    }

    private Coordinator buildCoordinator() {
        return buildCoordinatorWith(builder -> builder);
    }

    private void awaitStart(Coordinator coordinator) {
        coordinator.start().orTimeout(1, TimeUnit.SECONDS).join();
    }

    private Coordinator buildCoordinatorWith(UnaryOperator<Coordinator.Builder> customizer) {
        return customizer.apply(Coordinator.builder()
                                           .name(PROCESSOR_NAME)
                                           .eventSource(messageSource)
                                           .tokenStore(tokenStore)
                                           .unitOfWorkFactory(new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE))
                                           .executorService(executorService)
                                           .workPackageFactory((segment, trackingToken) -> workPackage)
                                           .processingStatusUpdater((id, updater) -> {})
                                           .initialToken(es -> es.firstToken(null).thenApply(ReplayToken::createReplayToken))
                                           .maxSegmentProvider(e -> SEGMENTS.size()))
                         .build();
    }

    @Test
    void ifCoordinationTaskRescheduledAfterTokenReleaseClaimFails() {
        //arrange
        final RuntimeException streamOpenException = new RuntimeException("Some exception during event stream open");
        final RuntimeException releaseClaimException = new RuntimeException("Some exception during release claim");
        final GlobalSequenceTrackingToken token = new GlobalSequenceTrackingToken(0);

        doReturn(completedFuture(SEGMENTS)).when(tokenStore).fetchSegments(eq(PROCESSOR_NAME), any());
        doReturn(completedFuture(token)).when(tokenStore).fetchToken(eq(PROCESSOR_NAME), any(Segment.class), any());
        doThrow(releaseClaimException).when(tokenStore).releaseClaim(eq(PROCESSOR_NAME), anyInt(), any());
        doThrow(streamOpenException).when(messageSource).open(any(), isNull());
        doReturn(completedFuture(streamOpenException)).when(workPackage).abort(any());
        doReturn(SEGMENT_ZERO).when(workPackage).segment();
        doAnswer(runTaskSync()).when(executorService).submit(any(Runnable.class));

        //act
        awaitStart(testSubject);

        //asserts
        verify(executorService, times(1)).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
        // should be zero since we mock there already is a segment
        verify(tokenStore, times(0)).initializeTokenSegments(anyString(),
                                                             anyInt(),
                                                             any(TrackingToken.class),
                                                             any(ProcessingContext.class));
    }

    @Test
    void ifCoordinationTaskInitializesTokenStoreWhenNeeded() {
        //arrange
        final GlobalSequenceTrackingToken token = new GlobalSequenceTrackingToken(0);

        doReturn(completedFuture(List.of()))
                .when(tokenStore)
                .fetchSegments(eq(PROCESSOR_NAME), any());
        doReturn(emptyCompletedFuture())
                .when(tokenStore)
                .initializeTokenSegments(eq(PROCESSOR_NAME), anyInt(), any(), any(ProcessingContext.class)
                );
        doReturn(completedFuture(token)).when(tokenStore).fetchToken(eq(PROCESSOR_NAME), any(Segment.class), any());
        doReturn(SEGMENT_ZERO).when(workPackage).segment();
        doAnswer(runTaskSync()).when(executorService).submit(any(Runnable.class));
        // Token-store initialization runs the persist in its own unit of work dispatched via
        // executorService.execute(...).
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(executorService).execute(any(Runnable.class));

        //act
        awaitStart(testSubject);

        //asserts
        verify(executorService, times(1)).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
        verify(tokenStore, times(1)).initializeTokenSegments(anyString(),
                                                             anyInt(),
                                                             isNull(),
                                                             any(ProcessingContext.class));
    }

    @Test
    void ifCoordinationTaskSchedulesEventsWithTheSameTokenTogether() {
        TrackingToken testToken = new GlobalSequenceTrackingToken(0);
        var testEventOne = EventTestUtils.asEventMessage("this-event");
        var testEventTwo = EventTestUtils.asEventMessage("this-event");
        var testEntryOne = new SimpleEntry<>(testEventOne, trackingTokenContext(testToken));
        var testEntryTwo = new SimpleEntry<>(testEventTwo, trackingTokenContext(testToken));
        List<MessageStream.Entry<? extends EventMessage>> testEntries = List.of(testEntryOne, testEntryTwo);

        when(workPackage.hasRemainingCapacity()).thenReturn(true)
                                                .thenReturn(false);
        when(workPackage.isAbortTriggered()).thenReturn(false);

        when(workPackage.scheduleEvents(testEntries)).thenReturn(true);
        when(workPackage.scheduleEvents(any())).thenReturn(true);

        MessageStream<EventMessage> testStream = MessageStream.fromIterable(
                List.of(testEventOne, testEventTwo),
                (e) -> trackingTokenContext(testToken)
        );

        when(executorService.submit(any(Runnable.class))).thenAnswer(runTaskAsync());
        // Segment claiming is async. The stream's setCallback fires scheduleImmediateCoordinationTask immediately
        // (events are ready), consuming one schedule call while processingGate is still true — that run exits early.
        // The whenComplete callback then calls coordinateEvents() directly (no extra round-trip), which processes
        // events and schedules a delayed reschedule — that second schedule call is ignored.
        doAnswer(invocation -> { ((Runnable) invocation.getArgument(0)).run(); return null; })
                .doAnswer(invocation -> null)
                .when(executorService).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
        when(tokenStore.fetchSegments(eq(PROCESSOR_NAME), any())).thenReturn(completedFuture(SEGMENTS));
        when(tokenStore.fetchAvailableSegments(eq(PROCESSOR_NAME), any()))
                .thenReturn(completedFuture(Collections.singletonList(SEGMENT_ONE)));
        when(tokenStore.fetchToken(eq(PROCESSOR_NAME), eq(SEGMENT_ONE), any()))
                .thenReturn(completedFuture(testToken));
        when(messageSource.open(streamingFrom(testToken), null)).thenReturn(testStream);

        awaitStart(testSubject);

        await().atMost(500, TimeUnit.MILLISECONDS)
               .untilAsserted(() -> verify(tokenStore).fetchToken(eq(PROCESSOR_NAME), eq(SEGMENT_ONE), any()));

        await().atMost(500, TimeUnit.MILLISECONDS)
               .untilAsserted(() -> verify(messageSource).open(streamingFrom(testToken), null));

        //noinspection unchecked
        ArgumentCaptor<List<MessageStream.Entry<? extends EventMessage>>> eventsCaptor = ArgumentCaptor.forClass(List.class);
        await().atMost(500, TimeUnit.MILLISECONDS)
               .untilAsserted(() -> verify(workPackage).scheduleEvents(eventsCaptor.capture()));

        var resultEvents = eventsCaptor.getValue();
        assertEquals(2, resultEvents.size());
        assertTrue(resultEvents.contains(testEntryOne));
        assertTrue(resultEvents.contains(testEntryTwo));

        verify(workPackage, times(0)).scheduleEvent(any());
    }

    /**
     * Tests for the {@code claimNewSegments} path — specifically how the coordinator handles
     * {@link UnableToClaimTokenException} during token claiming.
     */
    @Nested
    class ClaimNewSegments {

        @BeforeEach
        void setUp() {
            doReturn(completedFuture(SEGMENTS)).when(tokenStore).fetchSegments(eq(PROCESSOR_NAME), any());
            doAnswer(runTaskSync()).when(executorService).submit(any(Runnable.class));
        }

        @Test
        void skipsSegmentWhenUnableToClaimToken() {
            // given - token store has segments but claiming fails with UnableToClaimTokenException
            doReturn(completedFuture(List.of(SEGMENT_ZERO)))
                    .when(tokenStore).fetchAvailableSegments(eq(PROCESSOR_NAME), any());
            doReturn(CompletableFuture.failedFuture(new UnableToClaimTokenException("claim failed")))
                    .when(tokenStore).fetchToken(eq(PROCESSOR_NAME), any(Segment.class), any());

            // when
            awaitStart(testSubject);

            // then - token claim was attempted, no work package created for the segment
            verify(tokenStore).fetchToken(eq(PROCESSOR_NAME), any(Segment.class), any());
            verify(workPackage, never()).onBatchProcessed(any());
            // coordinator schedules delayed retry since no segments were successfully claimed
            verify(executorService, times(1)).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
        }

        @Test
        void continuesClaimingRemainingSegmentsAfterOneClaimFails() {
            // given - SEGMENT_ZERO fails to claim, SEGMENT_ONE succeeds
            GlobalSequenceTrackingToken token = new GlobalSequenceTrackingToken(0);
            doReturn(completedFuture(List.of(SEGMENT_ZERO, SEGMENT_ONE)))
                    .when(tokenStore).fetchAvailableSegments(eq(PROCESSOR_NAME), any());
            doReturn(CompletableFuture.failedFuture(new UnableToClaimTokenException("already claimed")))
                    .when(tokenStore).fetchToken(eq(PROCESSOR_NAME), eq(SEGMENT_ZERO), any());
            doReturn(completedFuture(token))
                    .when(tokenStore).fetchToken(eq(PROCESSOR_NAME), eq(SEGMENT_ONE), any());
            doReturn(SEGMENT_ONE).when(workPackage).segment();
            doReturn(false).when(workPackage).isAbortTriggered();
            doReturn(true).when(workPackage).hasRemainingCapacity();
            doReturn(false).when(workPackage).isDone();
            doReturn(MessageStream.fromIterable(Collections.emptyList()))
                    .when(messageSource).open(any(StreamingCondition.class), isNull());

            // when
            awaitStart(testSubject);

            // then - SEGMENT_ZERO was attempted but skipped; SEGMENT_ONE was still claimed and scheduled
            verify(tokenStore).fetchToken(eq(PROCESSOR_NAME), eq(SEGMENT_ZERO), any());
            verify(tokenStore).fetchToken(eq(PROCESSOR_NAME), eq(SEGMENT_ONE), any());
            verify(workPackage).scheduleWorker();
        }
    }

    @Test
    void claimTokenUnexpectedExceptionCausesAbortAndRetry() {
        // given - fetchToken fails with an exception that is not UnableToClaimTokenException
        doReturn(completedFuture(SEGMENTS)).when(tokenStore).fetchSegments(eq(PROCESSOR_NAME), any());
        doReturn(completedFuture(List.of(SEGMENT_ZERO))).when(tokenStore)
                                                         .fetchAvailableSegments(eq(PROCESSOR_NAME), any());
        doReturn(CompletableFuture.failedFuture(new RuntimeException("unexpected token failure")))
                .when(tokenStore).fetchToken(eq(PROCESSOR_NAME), any(Segment.class), any());
        doAnswer(runTaskSync()).when(executorService).submit(any(Runnable.class));

        // when
        awaitStart(testSubject);

        // then - the unexpected exception propagates through claimSegmentToken and triggers a retry
        verify(executorService).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    }

    @Test
    void coordinatorShouldNotTryToOpenStreamWithNoToken() throws NoSuchFieldException {
        //arrange
        final GlobalSequenceTrackingToken token = new GlobalSequenceTrackingToken(0);

        doReturn(completedFuture(SEGMENTS)).when(tokenStore).fetchSegments(eq(PROCESSOR_NAME), any());
        doReturn(completedFuture(token)).when(tokenStore).fetchToken(eq(PROCESSOR_NAME), any(Segment.class), any());
        doReturn(SEGMENT_ZERO).when(workPackage).segment();
        doAnswer(runTaskSync()).when(executorService).submit(any(Runnable.class));
        //Using reflection to add a work package to keep the test simple
        Map<Integer, WorkPackage> workPackages =
                ReflectionUtils.getFieldValue(Coordinator.class.getDeclaredField("workPackages"), testSubject);
        workPackages.put(SEGMENT_ID, workPackage);
        CompletableFuture<Exception> abortFuture = new CompletableFuture<>();
        doReturn(abortFuture).when(workPackage).abort(any());

        //act
        awaitStart(testSubject);

        //asserts
        verify(messageSource, never()).open(any(StreamingCondition.class), eq(null));
    }

    private Answer<Future<Void>> runTaskSync() {
        return invocationOnMock -> {
            final Runnable runnable = invocationOnMock.getArgument(0);
            runnable.run();
            return completedFuture(null);
        };
    }

    private Answer<Future<Void>> runTaskAsync() {
        return invocationOnMock -> CompletableFuture.runAsync(invocationOnMock.getArgument(0));
    }

    /**
     * Tests for the token store initialization retry logic delegated to
     * {@link org.axonframework.common.ProcessUtils#executeUntilTrue}:
     * single-failure-then-success path, and all-attempts-exhausted path.
     * <p>
     * Uses a 1 ms retry interval and 3 max retries to keep test execution fast.
     */
    @Nested
    class InitializeTokenStoreWithRetry {

        @BeforeEach
        void setUp() {
            testSubject = buildCoordinatorWith(b -> b.tokenStoreInitRetryInterval(1).tokenStoreInitMaxRetries(3));
            // The JDK-internal delayer fires executorService.execute(r) after the retry interval.
            // Stub execute so the runnable runs immediately once the delay has elapsed.
            doAnswer(invocation -> {
                ((Runnable) invocation.getArgument(0)).run();
                return null;
            }).when(executorService).execute(any(Runnable.class));
            // Prevent the coordination task from running after a successful start.
            doReturn(completedFuture(null)).when(executorService).submit(any(Runnable.class));
        }

        @Test
        void retriesOnceAndSucceedsWhenFirstAttemptReturnsFalse() {
            // given - first fetchSegments call throws (initializeTokenStore returns false), second succeeds
            AtomicInteger callCount = new AtomicInteger(0);
            doAnswer(invocation -> {
                if (callCount.getAndIncrement() == 0) {
                    return CompletableFuture.failedFuture(new RuntimeException("transient store error"));
                }
                return completedFuture(SEGMENTS);
            }).when(tokenStore).fetchSegments(eq(PROCESSOR_NAME), any());

            // when
            CompletableFuture<Void> startFuture = testSubject.start();

            // then - fetchSegments is called twice (initial attempt + 1 retry) and start completes normally
            await().atMost(500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
                assertTrue(startFuture.isDone());
                assertFalse(startFuture.isCompletedExceptionally());
            });
            verify(tokenStore, times(2)).fetchSegments(eq(PROCESSOR_NAME), any());
        }

        @Test
        void completesExceptionallyWithProcessRetriesExhaustedExceptionWhenAllAttemptsExhausted() {
            // given - fetchSegments always throws so initializeTokenStore always returns false
            doReturn(CompletableFuture.failedFuture(new RuntimeException("persistent store error")))
                    .when(tokenStore).fetchSegments(eq(PROCESSOR_NAME), any());

            // when
            CompletableFuture<Void> startFuture = testSubject.start();

            // then - after all 3 retries the future completes exceptionally with a ProcessRetriesExhaustedException
            await().atMost(1, TimeUnit.SECONDS).untilAsserted(() -> assertTrue(startFuture.isCompletedExceptionally()));
            CompletionException thrown = assertThrows(CompletionException.class, startFuture::join);
            assertInstanceOf(ProcessRetriesExhaustedException.class, thrown.getCause());
            verify(tokenStore, times(3)).fetchSegments(eq(PROCESSOR_NAME), any());
        }
    }

    /**
     * Tests for the {@code start()} exceptionally handler that runs when
     * {@code initializeTokenStoreWithRetry} itself fails permanently.
     */
    @Nested
    class Start {

        @BeforeEach
        void stubExecutorExecuteToRunImmediately() {
            doAnswer(invocation -> {
                ((Runnable) invocation.getArgument(0)).run();
                return null;
            }).when(executorService).execute(any(Runnable.class));
            doReturn(completedFuture(null)).when(executorService).submit(any(Runnable.class));
        }

        @Test
        void returnsCompletedFutureWhenAlreadyRunning() {
            // given - coordinator already running
            doReturn(completedFuture(SEGMENTS)).when(tokenStore).fetchSegments(eq(PROCESSOR_NAME), any());
            awaitStart(testSubject);

            // when - start is called again
            CompletableFuture<Void> secondStart = testSubject.start();

            // then - returns an already-completed, non-exceptional future immediately
            assertThat(secondStart).isCompleted();
            assertThat(secondStart.isCompletedExceptionally()).isFalse();
        }

        @Test
        void throwsIllegalStateExceptionWhenStartCalledWhileShuttingDown() {
            // given - coordinator that has been started and then stopped (shutdown handle not yet done,
            //         because the coordination task was captured without running)
            doReturn(completedFuture(SEGMENTS)).when(tokenStore).fetchSegments(eq(PROCESSOR_NAME), any());
            awaitStart(testSubject);
            testSubject.stop();

            // when / then
            assertThrows(IllegalStateException.class, testSubject::start);
        }

        @Test
        void completesShutdownHandleWhenInitializationPermanentlyFails() {
            // given - a coordinator with a tracked shutdown action
            AtomicBoolean shutdownInvoked = new AtomicBoolean(false);
            Coordinator coordinator = Coordinator.builder()
                                                 .name(PROCESSOR_NAME)
                                                 .eventSource(messageSource)
                                                 .tokenStore(tokenStore)
                                                 .unitOfWorkFactory(new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE))
                                                 .executorService(executorService)
                                                 .workPackageFactory((segment, token) -> workPackage)
                                                 .initialToken(es -> es.firstToken(null).thenApply(ReplayToken::createReplayToken))
                                                 .maxSegmentProvider(e -> SEGMENTS.size())
                                                 .onShutdown(() -> shutdownInvoked.set(true))
                                                 .build();

            // fetchSegments always fails so all retries are exhausted and start() enters its exceptionally block
            doReturn(CompletableFuture.failedFuture(new RuntimeException("persistent store error")))
                    .when(tokenStore).fetchSegments(eq(PROCESSOR_NAME), any());

            // when
            CompletableFuture<Void> startFuture = coordinator.start();

            // then - the shutdown action is triggered once the start future completes exceptionally
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                assertTrue(startFuture.isCompletedExceptionally());
                assertTrue(shutdownInvoked.get());
            });
        }
    }

    /**
     * Tests for the {@code createWorkPackage} method, specifically the error-handling path
     * in which the {@link SegmentChangeListener#onSegmentClaimed} callback throws.
     */
    @Nested
    class CreateWorkPackage {

        @Test
        void stillRegistersWorkPackageWhenOnSegmentClaimedFails() {
            // given - coordinator with a claim listener that always fails
            GlobalSequenceTrackingToken token = new GlobalSequenceTrackingToken(0);
            Coordinator coordinator = Coordinator.builder()
                                                 .name(PROCESSOR_NAME)
                                                 .eventSource(messageSource)
                                                 .tokenStore(tokenStore)
                                                 .unitOfWorkFactory(new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE))
                                                 .executorService(executorService)
                                                 .workPackageFactory((segment, trackingToken) -> workPackage)
                                                 .initialToken(es -> es.firstToken(null).thenApply(ReplayToken::createReplayToken))
                                                 .maxSegmentProvider(e -> SEGMENTS.size())
                                                 .segmentChangeListener(SegmentChangeListener.onClaim(
                                                         segment -> CompletableFuture.failedFuture(
                                                                 new RuntimeException("claim listener failed"))
                                                 ))
                                                 .build();

            doReturn(completedFuture(SEGMENTS)).when(tokenStore).fetchSegments(eq(PROCESSOR_NAME), any());
            doReturn(completedFuture(List.of(SEGMENT_ZERO))).when(tokenStore)
                                                             .fetchAvailableSegments(eq(PROCESSOR_NAME), any());
            doReturn(completedFuture(token)).when(tokenStore).fetchToken(eq(PROCESSOR_NAME), eq(SEGMENT_ZERO), any());
            doReturn(SEGMENT_ZERO).when(workPackage).segment();
            doReturn(false).when(workPackage).isAbortTriggered();
            doReturn(true).when(workPackage).hasRemainingCapacity();
            doReturn(false).when(workPackage).isDone();
            doReturn(MessageStream.fromIterable(Collections.emptyList()))
                    .when(messageSource).open(any(StreamingCondition.class), isNull());
            doAnswer(runTaskSync()).when(executorService).submit(any(Runnable.class));

            // when
            awaitStart(coordinator);

            // then - the work package is still registered and scheduled despite the failing claim listener
            verify(workPackage).scheduleWorker();
        }
    }

    /**
     * Tests for the {@code ensureOpenStream} null-token path where
     * {@link StreamableEventSource#firstToken} is used to determine the stream start position.
     */
    @Nested
    class EnsureOpenStream {

        @Test
        void callsFirstTokenWhenAllClaimedTokensAreNull() {
            // given - fetchToken returns null, so the lower-bound position is null after claiming
            GlobalSequenceTrackingToken resolvedFirstToken = new GlobalSequenceTrackingToken(0);
            doReturn(completedFuture(SEGMENTS)).when(tokenStore).fetchSegments(eq(PROCESSOR_NAME), any());
            doReturn(completedFuture(List.of(SEGMENT_ZERO))).when(tokenStore)
                                                             .fetchAvailableSegments(eq(PROCESSOR_NAME), any());
            doReturn(completedFuture(null)).when(tokenStore).fetchToken(eq(PROCESSOR_NAME), any(Segment.class), any());
            // override setUp default so firstToken resolves to a real token
            doReturn(completedFuture(resolvedFirstToken)).when(messageSource).firstToken(null);
            doReturn(SEGMENT_ZERO).when(workPackage).segment();
            doReturn(false).when(workPackage).isAbortTriggered();
            doReturn(true).when(workPackage).hasRemainingCapacity();
            doReturn(false).when(workPackage).isDone();
            doReturn(MessageStream.fromIterable(Collections.emptyList()))
                    .when(messageSource).open(any(StreamingCondition.class), isNull());
            doAnswer(runTaskSync()).when(executorService).submit(any(Runnable.class));

            // when
            awaitStart(testSubject);

            // then - firstToken is consulted to determine the stream start position,
            //        and the stream is subsequently opened from that resolved token
            verify(messageSource).firstToken(null);
            verify(messageSource).open(streamingFrom(resolvedFirstToken), null);
        }
    }

    /**
     * Tests for the {@link Coordinator.CoordinationTask#run()} method — specifically the
     * stale-generation guard and the async {@code extendClaimIfThresholdIsMet} failure path.
     */
    @Nested
    class Run {

        @Test
        void staleTaskExitsWithoutClaimingSegments() throws NoSuchFieldException {
            // given - capture the first task without running it, then advance the generation counter
            AtomicReference<Runnable> capturedTask = new AtomicReference<>();
            doAnswer(inv -> {
                capturedTask.set(inv.getArgument(0));
                return completedFuture(null);
            }).when(executorService).submit(any(Runnable.class));
            doReturn(completedFuture(SEGMENTS)).when(tokenStore).fetchSegments(eq(PROCESSOR_NAME), any());

            awaitStart(testSubject);

            AtomicLong generationCounter = ReflectionUtils.getFieldValue(
                    Coordinator.class.getDeclaredField("coordinationTaskGeneration"), testSubject);
            generationCounter.incrementAndGet();

            // when - the now-stale task runs
            capturedTask.get().run();

            // then - no segment claiming attempted; stale task exited early
            verify(tokenStore, never()).fetchAvailableSegments(any(), any());
        }

        @Test
        void extendClaimCompletionExceptionCauseIsUnwrappedBeforeAbort() throws NoSuchFieldException {
            // given - work package whose extension fails with a CompletionException wrapping an inner cause
            RuntimeException innerCause = new RuntimeException("inner cause");
            Coordinator coordinator = Coordinator.builder()
                                                 .name(PROCESSOR_NAME)
                                                 .eventSource(messageSource)
                                                 .tokenStore(tokenStore)
                                                 .unitOfWorkFactory(new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE))
                                                 .executorService(executorService)
                                                 .workPackageFactory((segment, token) -> workPackage)
                                                 .maxSegmentProvider(e -> SEGMENTS.size())
                                                 .coordinatorClaimExtension(true)
                                                 .build();

            doReturn(completedFuture(SEGMENTS)).when(tokenStore).fetchSegments(eq(PROCESSOR_NAME), any());
            doReturn(completedFuture(Collections.emptyList())).when(tokenStore)
                                                              .fetchAvailableSegments(eq(PROCESSOR_NAME), any());
            doReturn(SEGMENT_ZERO).when(workPackage).segment();
            doReturn(false).when(workPackage).isAbortTriggered();
            doReturn(true).when(workPackage).isProcessingEvents();
            doReturn(CompletableFuture.failedFuture(new CompletionException(innerCause)))
                    .when(workPackage).extendClaimIfThresholdIsMet();
            doReturn(emptyCompletedFuture()).when(workPackage).abort(any());
            doReturn(emptyCompletedFuture()).when(tokenStore).releaseClaim(eq(PROCESSOR_NAME), anyInt(), any());
            doAnswer(runTaskSync()).when(executorService).submit(any(Runnable.class));

            Map<Integer, WorkPackage> workPackages =
                    ReflectionUtils.getFieldValue(Coordinator.class.getDeclaredField("workPackages"), coordinator);
            workPackages.put(SEGMENT_ID, workPackage);

            // when
            awaitStart(coordinator);

            // then - abort is called with the unwrapped inner cause, not the CompletionException wrapper
            ArgumentCaptor<Exception> captor = ArgumentCaptor.forClass(Exception.class);
            verify(workPackage, atLeastOnce()).abort(captor.capture());
            assertThat(captor.getAllValues()).anyMatch(e -> e == innerCause);
        }

        @Test
        void extendClaimFailureAbortsWorkPackage() throws NoSuchFieldException {
            // given - coordinator with claim extension enabled and a work package that fails to extend
            Coordinator coordinator = Coordinator.builder()
                                                 .name(PROCESSOR_NAME)
                                                 .eventSource(messageSource)
                                                 .tokenStore(tokenStore)
                                                 .unitOfWorkFactory(new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE))
                                                 .executorService(executorService)
                                                 .workPackageFactory((segment, token) -> workPackage)
                                                 .maxSegmentProvider(e -> SEGMENTS.size())
                                                 .coordinatorClaimExtension(true)
                                                 .build();

            doReturn(completedFuture(SEGMENTS)).when(tokenStore).fetchSegments(eq(PROCESSOR_NAME), any());
            doReturn(completedFuture(Collections.emptyList())).when(tokenStore)
                                                              .fetchAvailableSegments(eq(PROCESSOR_NAME), any());
            doReturn(SEGMENT_ZERO).when(workPackage).segment();
            doReturn(false).when(workPackage).isAbortTriggered();
            doReturn(true).when(workPackage).isProcessingEvents();
            doReturn(CompletableFuture.failedFuture(new RuntimeException("extend claim failed")))
                    .when(workPackage).extendClaimIfThresholdIsMet();
            doReturn(emptyCompletedFuture()).when(workPackage).abort(any());
            doReturn(emptyCompletedFuture()).when(tokenStore).releaseClaim(eq(PROCESSOR_NAME), anyInt(), any());
            doAnswer(runTaskSync()).when(executorService).submit(any(Runnable.class));

            Map<Integer, WorkPackage> workPackages =
                    ReflectionUtils.getFieldValue(Coordinator.class.getDeclaredField("workPackages"), coordinator);
            workPackages.put(SEGMENT_ID, workPackage);

            // when
            awaitStart(coordinator);

            // then - abort was triggered on the work package due to the extend-claim failure
            verify(workPackage, atLeastOnce()).abort(any());
        }
    }

    /**
     * Tests for the {@code coordinateEvents()} guard that handles the unexpected state
     * in which work packages exist but the event stream is null.
     */
    @Nested
    class CoordinateEvents {

        @Test
        void abortsWorkPackagesAndSchedulesRetryWhenEventStreamIsNullButWorkPackagesPresent()
                throws NoSuchFieldException {
            // given - coordinator has an active work package but no open stream and no new available segments to claim
            // No token received, stream stays null
            doReturn(completedFuture(SEGMENTS)).when(tokenStore).fetchSegments(eq(PROCESSOR_NAME), any());
            doReturn(completedFuture(Collections.emptyList())).when(tokenStore)
                                                              .fetchAvailableSegments(eq(PROCESSOR_NAME), any());
            doReturn(SEGMENT_ZERO).when(workPackage).segment();
            doReturn(emptyCompletedFuture()).when(workPackage).abort(any());
            doReturn(emptyCompletedFuture()).when(tokenStore).releaseClaim(eq(PROCESSOR_NAME), anyInt(), any());
            doAnswer(runTaskSync()).when(executorService).submit(any(Runnable.class));

            // inject the pre-existing work package before the coordination task runs
            Map<Integer, WorkPackage> workPackages =
                    ReflectionUtils.getFieldValue(Coordinator.class.getDeclaredField("workPackages"), testSubject);
            workPackages.put(SEGMENT_ID, workPackage);

            // when
            awaitStart(testSubject);

            // then - the null-stream guard triggers abort on the active work package
            verify(workPackage).abort(any());
        }

        @Test
        void callsOnSegmentReleasedAfterAbortingWorkPackage() throws NoSuchFieldException {
            // given - coordinator with a release listener that records the released segment
            AtomicBoolean releasedCalled = new AtomicBoolean(false);
            Coordinator coordinator = Coordinator.builder()
                                                 .name(PROCESSOR_NAME)
                                                 .eventSource(messageSource)
                                                 .tokenStore(tokenStore)
                                                 .unitOfWorkFactory(new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE))
                                                 .executorService(executorService)
                                                 .workPackageFactory((segment, trackingToken) -> workPackage)
                                                 .maxSegmentProvider(e -> SEGMENTS.size())
                                                 .segmentChangeListener(SegmentChangeListener.onRelease(segment -> {
                                                     releasedCalled.set(true);
                                                     return emptyCompletedFuture();
                                                 }))
                                                 .build();

            doReturn(completedFuture(SEGMENTS)).when(tokenStore).fetchSegments(eq(PROCESSOR_NAME), any());
            doReturn(completedFuture(Collections.emptyList())).when(tokenStore)
                                                              .fetchAvailableSegments(eq(PROCESSOR_NAME), any());
            doReturn(SEGMENT_ZERO).when(workPackage).segment();
            doReturn(emptyCompletedFuture()).when(workPackage).abort(any());
            doReturn(emptyCompletedFuture()).when(tokenStore).releaseClaim(eq(PROCESSOR_NAME), anyInt(), any());
            doAnswer(runTaskSync()).when(executorService).submit(any(Runnable.class));

            Map<Integer, WorkPackage> workPackages =
                    ReflectionUtils.getFieldValue(Coordinator.class.getDeclaredField("workPackages"), coordinator);
            workPackages.put(SEGMENT_ID, workPackage);

            // when
            awaitStart(coordinator);

            // then - onSegmentReleased was invoked as part of the abort chain
            assertThat(releasedCalled.get()).isTrue();
        }

        @Test
        void onSegmentReleasedFailureIsHandledGracefully() throws NoSuchFieldException {
            // given - coordinator with a release listener that always fails
            Coordinator coordinator = Coordinator.builder()
                                                 .name(PROCESSOR_NAME)
                                                 .eventSource(messageSource)
                                                 .tokenStore(tokenStore)
                                                 .unitOfWorkFactory(new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE))
                                                 .executorService(executorService)
                                                 .workPackageFactory((segment, trackingToken) -> workPackage)
                                                 .maxSegmentProvider(e -> SEGMENTS.size())
                                                 .segmentChangeListener(SegmentChangeListener.onRelease(
                                                         segment -> CompletableFuture.failedFuture(
                                                                 new RuntimeException("release listener failed"))
                                                 ))
                                                 .build();

            doReturn(completedFuture(SEGMENTS)).when(tokenStore).fetchSegments(eq(PROCESSOR_NAME), any());
            doReturn(completedFuture(Collections.emptyList())).when(tokenStore)
                                                              .fetchAvailableSegments(eq(PROCESSOR_NAME), any());
            doReturn(SEGMENT_ZERO).when(workPackage).segment();
            doReturn(emptyCompletedFuture()).when(workPackage).abort(any());
            doReturn(emptyCompletedFuture()).when(tokenStore).releaseClaim(eq(PROCESSOR_NAME), anyInt(), any());
            doAnswer(runTaskSync()).when(executorService).submit(any(Runnable.class));

            Map<Integer, WorkPackage> workPackages =
                    ReflectionUtils.getFieldValue(Coordinator.class.getDeclaredField("workPackages"), coordinator);
            workPackages.put(SEGMENT_ID, workPackage);

            // when
            awaitStart(coordinator);

            // then - the failure is swallowed by the exceptionally handler and a retry is still scheduled
            verify(executorService).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
        }
    }
}
