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

import org.axonframework.common.util.DelegateScheduledExecutorService;
import org.axonframework.messaging.core.Context;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.SimpleEntry;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkTestUtils;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventTestUtils;
import org.axonframework.messaging.eventhandling.processing.streaming.checkpoint.CheckpointTrigger;
import org.axonframework.messaging.eventhandling.processing.streaming.checkpoint.Checkpointing;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.TrackerStatus;
import org.axonframework.messaging.eventhandling.processing.streaming.token.GlobalSequenceTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.inmemory.InMemoryTokenStore;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * Test class validating the checkpoint protocol of the {@link WorkPackage}: a self-checkpointing
 * {@link Checkpointing participant} drives when the stored {@link TrackingToken} advances, while the WorkPackage retains
 * control of the transaction and the token write.
 *
 * @author Allard Buijze
 */
class WorkPackageCheckpointTest {

    private static final String PROCESSOR_NAME = "test";
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private TokenStore tokenStore;
    private ScheduledExecutorService executorService;
    private Segment segment;
    private TrackingToken initialToken;
    private TestBatchProcessor batchProcessor;
    private TrackerStatus trackerStatus;

    private WorkPackage.Builder builder;

    @BeforeEach
    void setUp() {
        tokenStore = spy(new InMemoryTokenStore());
        executorService = new DelegateScheduledExecutorService(Executors.newScheduledThreadPool(1));
        segment = Segment.ROOT_SEGMENT;
        initialToken = new GlobalSequenceTrackingToken(0L);
        tokenStore.initializeSegment(initialToken, PROCESSOR_NAME, segment, null);
        batchProcessor = new TestBatchProcessor();
        trackerStatus = new TrackerStatus(segment, initialToken);

        builder = WorkPackage.builder()
                             .name(PROCESSOR_NAME)
                             .tokenStore(tokenStore)
                             .unitOfWorkFactory(UnitOfWorkTestUtils.SIMPLE_FACTORY)
                             .executorService(executorService)
                             .eventFilter((event, context, segment) -> true)
                             .batchProcessor(batchProcessor)
                             .segment(segment)
                             .initialToken(initialToken)
                             .batchSize(1)
                             .claimExtensionThreshold(5000)
                             .segmentStatusUpdater(op -> trackerStatus = op.apply(trackerStatus));
    }

    @AfterEach
    void tearDown() {
        executorService.shutdown();
    }

    @Nested
    class FullyDeferredMode {

        @Test
        void batchIsHandledButTokenIsNotStoredUntilAComponentRequestsACheckpoint() {
            // given
            RecordingCheckpointing participant = new RecordingCheckpointing();
            WorkPackage testSubject = builder.autoMode(false).participants(List.of(participant)).build();
            testSubject.notifySegmentClaimed();

            // when
            testSubject.scheduleEvent(eventAt(1L));

            // then -- the batch is processed, but no token is stored without a checkpoint request
            await().atMost(TIMEOUT).untilAsserted(() -> assertThat(batchProcessor.processed).hasSize(1));
            verify(tokenStore, never()).storeToken(any(), anyString(), anyInt(), any());
        }

        @Test
        void requestOnAnIdlePackageSchedulesAndStoresTheReportedToken() {
            // given -- no events scheduled, the worker is idle
            RecordingCheckpointing participant = new RecordingCheckpointing();
            WorkPackage testSubject = builder.autoMode(false).participants(List.of(participant)).build();
            testSubject.notifySegmentClaimed();

            // when -- an async write confirms durable up to position 5
            participant.trigger.requestCheckpoint(new GlobalSequenceTrackingToken(5L));

            // then
            ArgumentCaptor<TrackingToken> stored = ArgumentCaptor.forClass(TrackingToken.class);
            await().atMost(TIMEOUT).untilAsserted(() -> verify(tokenStore).storeToken(
                    stored.capture(), anyString(), anyInt(), any()
            ));
            assertThat(stored.getValue()).isEqualTo(new GlobalSequenceTrackingToken(5L));
            assertThat(participant.advanced).containsExactly(new GlobalSequenceTrackingToken(5L));
        }

        @Test
        void aParticipantThatCannotBeReconciledUpToTheLeaderFailsTheCheckpointWithoutStoring() {
            // given -- a leader at 8 and a participant stuck at 6 that cannot advance when re-requested higher
            RecordingCheckpointing stuck = new RecordingCheckpointing();
            stuck.advanceResult = requested -> CompletableFuture.completedFuture(new GlobalSequenceTrackingToken(6L));
            RecordingCheckpointing leader = new RecordingCheckpointing();
            leader.advanceResult = requested -> CompletableFuture.completedFuture(new GlobalSequenceTrackingToken(8L));
            WorkPackage testSubject = builder.autoMode(false).participants(List.of(stuck, leader)).build();
            testSubject.notifySegmentClaimed();

            // when
            stuck.trigger.requestCheckpoint(new GlobalSequenceTrackingToken(5L));

            // then -- reconciliation re-requests the stuck participant at 8, which it cannot cover, so the checkpoint
            // fails and NOTHING is stored (rather than storing the lowerBound 6 and forcing the leader to reprocess).
            await().atMost(TIMEOUT).untilAsserted(() -> assertThat(testSubject.abort(null)).isDone());
            verify(tokenStore, never()).storeToken(any(), anyString(), anyInt(), any());
        }

        @Test
        void aReturnedTokenThatDoesNotCoverTheRequestedPositionFailsTheCheckpointWithoutStoring() {
            // given -- the participant reports a position BEHIND the requested one (contract violation)
            RecordingCheckpointing participant = new RecordingCheckpointing();
            participant.advanceResult = requested -> CompletableFuture.completedFuture(new GlobalSequenceTrackingToken(3L));
            WorkPackage testSubject = builder.autoMode(false).participants(List.of(participant)).build();
            testSubject.notifySegmentClaimed();

            // when
            participant.trigger.requestCheckpoint(new GlobalSequenceTrackingToken(5L));

            // then -- the checkpoint fails; nothing is stored and the package aborts
            await().atMost(TIMEOUT).untilAsserted(() -> assertThat(testSubject.abort(null)).isDone());
            verify(tokenStore, never()).storeToken(any(), anyString(), anyInt(), any());
        }

        @Test
        void laggingParticipantIsReRequestedUpToTheLeaderSoTheStoredTokenIsAgreedNotTheLowerBound() {
            // given -- two participants: one reaches position 10, the other initially only 7 but can advance when asked
            RecordingCheckpointing leader = new RecordingCheckpointing();
            leader.advanceResult = requested -> CompletableFuture.completedFuture(new GlobalSequenceTrackingToken(10L));
            RecordingCheckpointing lagging = new RecordingCheckpointing();
            lagging.advanceResult = requested -> CompletableFuture.completedFuture(
                    new GlobalSequenceTrackingToken(Math.max(requested.position().orElse(0L), 7L))
            );
            WorkPackage testSubject = builder.autoMode(false).participants(List.of(leader, lagging)).build();
            testSubject.notifySegmentClaimed();

            // when
            lagging.trigger.requestCheckpoint(new GlobalSequenceTrackingToken(5L));

            // then -- the lagging participant is re-requested up to the leader's position (10), and 10 (the agreed
            // position) is stored -- NOT the lowerBound (7), which would force the leader to reprocess (7, 10].
            ArgumentCaptor<TrackingToken> stored = ArgumentCaptor.forClass(TrackingToken.class);
            await().atMost(TIMEOUT).untilAsserted(() -> verify(tokenStore).storeToken(
                    stored.capture(), anyString(), anyInt(), any()
            ));
            assertThat(stored.getValue()).isEqualTo(new GlobalSequenceTrackingToken(10L));
            // the lagging participant was asked twice: first at 5, then re-requested at the leader's 10
            assertThat(lagging.advanced).containsExactly(
                    new GlobalSequenceTrackingToken(5L), new GlobalSequenceTrackingToken(10L)
            );
        }

        @Test
        void reconciliationReRequestsTheWholeSetWhenALaggardLeapfrogsTheLeader() {
            // given -- A starts at 7 then leapfrogs to 15 when re-requested at 10; B starts at 10 then matches 15.
            // The leapfrog makes the round-one LEADER (B, at 10) a laggard behind the new agreed position (15),
            // so the whole set -- not just the original laggard -- must be reconciled.
            RecordingCheckpointing a = new RecordingCheckpointing();
            a.advanceResult = requested -> {
                long position = requested.position().orElse(0L);
                long reached = position <= 5L ? 7L : (position <= 10L ? 15L : position);
                return CompletableFuture.completedFuture(new GlobalSequenceTrackingToken(reached));
            };
            RecordingCheckpointing b = new RecordingCheckpointing();
            b.advanceResult = requested -> CompletableFuture.completedFuture(
                    new GlobalSequenceTrackingToken(Math.max(requested.position().orElse(0L), 10L))
            );
            WorkPackage testSubject = builder.autoMode(false).participants(List.of(a, b)).build();
            testSubject.notifySegmentClaimed();

            // when
            a.trigger.requestCheckpoint(new GlobalSequenceTrackingToken(5L));

            // then -- converges at 15, and B (a round-one leader) was re-requested up to 15
            ArgumentCaptor<TrackingToken> stored = ArgumentCaptor.forClass(TrackingToken.class);
            await().atMost(TIMEOUT).untilAsserted(() -> verify(tokenStore).storeToken(
                    stored.capture(), anyString(), anyInt(), any()
            ));
            assertThat(stored.getValue()).isEqualTo(new GlobalSequenceTrackingToken(15L));
            assertThat(a.advanced).containsExactly(
                    new GlobalSequenceTrackingToken(5L), new GlobalSequenceTrackingToken(10L)
            );
            assertThat(b.advanced).containsExactly(
                    new GlobalSequenceTrackingToken(5L), new GlobalSequenceTrackingToken(15L)
            );
        }

        @Test
        void aRequestArrivingWhileTheWorkerIsRunningIsStillHonoured() {
            // given -- the participant issues a SECOND request from within the first checkpoint (worker still scheduled)
            RecordingCheckpointing participant = new RecordingCheckpointing();
            participant.advanceResult = requested -> {
                if (requested.equals(new GlobalSequenceTrackingToken(5L))) {
                    // scheduled flag is still set here; this request's scheduleWorker CAS loses
                    participant.trigger.requestCheckpoint(new GlobalSequenceTrackingToken(6L));
                }
                return CompletableFuture.completedFuture(requested);
            };
            WorkPackage testSubject = builder.autoMode(false).participants(List.of(participant)).build();
            testSubject.notifySegmentClaimed();

            // when
            participant.trigger.requestCheckpoint(new GlobalSequenceTrackingToken(5L));

            // then -- the onProcessingComplete re-check reschedules and the second checkpoint runs (no stall)
            await().atMost(TIMEOUT).untilAsserted(() -> assertThat(participant.advanced).containsExactly(
                    new GlobalSequenceTrackingToken(5L), new GlobalSequenceTrackingToken(6L)
            ));
        }

        @Test
        void requestingACheckpointAtLatestResolvesToTheLastConsumedToken() {
            // given -- one event handled, so the last consumed token is at position 1 (deferred: nothing stored yet)
            RecordingCheckpointing participant = new RecordingCheckpointing();
            WorkPackage testSubject = builder.autoMode(false).participants(List.of(participant)).build();
            testSubject.notifySegmentClaimed();
            testSubject.scheduleEvent(eventAt(1L));
            await().atMost(TIMEOUT).untilAsserted(() -> assertThat(batchProcessor.processed).hasSize(1));

            // when -- the component requests a checkpoint at LATEST rather than naming a token
            participant.trigger.requestCheckpoint(TrackingToken.LATEST);

            // then -- LATEST resolved to the last consumed token (position 1), which is what is covered and stored
            ArgumentCaptor<TrackingToken> stored = ArgumentCaptor.forClass(TrackingToken.class);
            await().atMost(TIMEOUT).untilAsserted(() -> verify(tokenStore).storeToken(
                    stored.capture(), anyString(), anyInt(), any()
            ));
            assertThat(stored.getValue()).isEqualTo(new GlobalSequenceTrackingToken(1L));
            assertThat(participant.advanced).containsExactly(new GlobalSequenceTrackingToken(1L));
        }

        @Test
        void requestingACheckpointWithoutATokenResolvesToTheLastConsumedToken() {
            // given -- one event handled, so the last consumed token is at position 1 (deferred: nothing stored yet)
            RecordingCheckpointing participant = new RecordingCheckpointing();
            WorkPackage testSubject = builder.autoMode(false).participants(List.of(participant)).build();
            testSubject.notifySegmentClaimed();
            testSubject.scheduleEvent(eventAt(1L));
            await().atMost(TIMEOUT).untilAsserted(() -> assertThat(batchProcessor.processed).hasSize(1));

            // when -- the component requests a checkpoint without naming a token
            participant.trigger.requestCheckpoint();

            // then -- the request resolves to the last consumed token (position 1), which is what is covered and stored
            ArgumentCaptor<TrackingToken> stored = ArgumentCaptor.forClass(TrackingToken.class);
            await().atMost(TIMEOUT).untilAsserted(() -> verify(tokenStore).storeToken(
                    stored.capture(), anyString(), anyInt(), any()
            ));
            assertThat(stored.getValue()).isEqualTo(new GlobalSequenceTrackingToken(1L));
            assertThat(participant.advanced).containsExactly(new GlobalSequenceTrackingToken(1L));
        }

        @Test
        void theCheckpointTriggerIsResolvableFromTheHandlingContext() {
            // given
            RecordingCheckpointing participant = new RecordingCheckpointing();
            WorkPackage testSubject = builder.autoMode(false).participants(List.of(participant)).build();
            testSubject.notifySegmentClaimed();

            // when
            testSubject.scheduleEvent(eventAt(1L));

            // then -- the per-segment trigger handed on claim is the same one exposed in the handling context
            await().atMost(TIMEOUT).untilAsserted(() -> assertThat(batchProcessor.triggerInContext).isNotNull());
            assertThat(batchProcessor.triggerInContext).isSameAs(participant.trigger);
        }

        @Test
        void theSegmentIsResolvableFromTheHandlingContext() {
            // given
            RecordingCheckpointing participant = new RecordingCheckpointing();
            WorkPackage testSubject = builder.autoMode(false).participants(List.of(participant)).build();
            testSubject.notifySegmentClaimed();

            // when
            testSubject.scheduleEvent(eventAt(1L));

            // then -- the segment is exposed in the handling context, so a handler may declare a Segment parameter
            await().atMost(TIMEOUT).untilAsserted(() -> assertThat(batchProcessor.segmentInContext).isNotNull());
            assertThat(batchProcessor.segmentInContext).isEqualTo(testSubject.segment());
        }

        @Test
        void aRequestAfterTheSegmentIsReleasedIsIgnored() {
            // given
            RecordingCheckpointing participant = new RecordingCheckpointing();
            WorkPackage testSubject = builder.autoMode(false).participants(List.of(participant)).build();
            testSubject.notifySegmentClaimed();
            CheckpointTrigger trigger = participant.trigger;

            // when -- the package is aborted (segment released), then a late async ack requests a checkpoint
            testSubject.abort(null);
            trigger.requestCheckpoint(new GlobalSequenceTrackingToken(5L));

            // then -- the late request is a no-op: no checkpoint runs and nothing is stored
            await().during(Duration.ofMillis(200)).atMost(TIMEOUT).untilAsserted(() -> {
                assertThat(participant.advanced).isEmpty();
                verify(tokenStore, never()).storeToken(any(), anyString(), anyInt(), any());
            });
        }
    }

    @Nested
    class AutoModeWithParticipant {

        @Test
        void aCheckpointAtTheBatchEndTokenIsRequestedEveryBatch() {
            // given -- a self-checkpointing component co-located with an auto component (autoMode = true)
            RecordingCheckpointing participant = new RecordingCheckpointing();
            WorkPackage testSubject = builder.autoMode(true).participants(List.of(participant)).build();
            testSubject.notifySegmentClaimed();

            // when
            testSubject.scheduleEvent(eventAt(1L));

            // then -- the participant is forced to cover the batch-end token, and that token is stored
            ArgumentCaptor<TrackingToken> stored = ArgumentCaptor.forClass(TrackingToken.class);
            await().atMost(TIMEOUT).untilAsserted(() -> {
                assertThat(participant.advanced).containsExactly(new GlobalSequenceTrackingToken(1L));
                verify(tokenStore).storeToken(stored.capture(), anyString(), anyInt(), any());
            });
            assertThat(stored.getValue()).isEqualTo(new GlobalSequenceTrackingToken(1L));
        }
    }

    @Nested
    class Release {

        @Test
        void finalCheckpointStoresTheLowerBoundOfReleasedHighWaterMarks() {
            // given
            RecordingCheckpointing participant = new RecordingCheckpointing();
            participant.releaseResult = upTo -> CompletableFuture.completedFuture(new GlobalSequenceTrackingToken(9L));
            WorkPackage testSubject = builder.autoMode(false).participants(List.of(participant)).build();
            testSubject.notifySegmentClaimed();

            // when -- the Coordinator drives the final checkpoint within a transaction it controls
            UnitOfWorkTestUtils.SIMPLE_FACTORY.create()
                                              .executeWithResult(testSubject::finalCheckpoint)
                                              .orTimeout(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                                              .join();

            // then
            assertThat(participant.released).containsExactly(initialToken);
            verify(tokenStore).storeToken(eq(new GlobalSequenceTrackingToken(9L)), anyString(), anyInt(), any());
        }

        @Test
        void aReleaseReturningLatestStoresTheLastConsumedToken() {
            // given -- one event handled (last consumed token at position 1), nothing checkpointed yet
            RecordingCheckpointing participant = new RecordingCheckpointing();
            participant.releaseResult = upTo -> CompletableFuture.completedFuture(TrackingToken.LATEST);
            WorkPackage testSubject = builder.autoMode(false).participants(List.of(participant)).build();
            testSubject.notifySegmentClaimed();
            testSubject.scheduleEvent(eventAt(1L));
            await().atMost(TIMEOUT).untilAsserted(() -> assertThat(batchProcessor.processed).hasSize(1));

            // when -- on release the component reports LATEST ("everything you gave me")
            UnitOfWorkTestUtils.SIMPLE_FACTORY.create()
                                              .executeWithResult(testSubject::finalCheckpoint)
                                              .orTimeout(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                                              .join();

            // then -- LATEST resolved to the last consumed token (position 1) and stored
            verify(tokenStore).storeToken(eq(new GlobalSequenceTrackingToken(1L)), anyString(), anyInt(), any());
        }

        @Test
        void aReleaseTokenBehindTheLastCheckpointIsIgnoredAndDoesNotRegressTheStoredToken() {
            // given -- the stored checkpoint has advanced to position 5
            RecordingCheckpointing participant = new RecordingCheckpointing();
            WorkPackage testSubject = builder.autoMode(false).participants(List.of(participant)).build();
            testSubject.notifySegmentClaimed();
            participant.trigger.requestCheckpoint(new GlobalSequenceTrackingToken(5L));
            await().atMost(TIMEOUT).untilAsserted(() -> verify(tokenStore).storeToken(
                    eq(new GlobalSequenceTrackingToken(5L)), anyString(), anyInt(), any()
            ));
            clearInvocations(tokenStore);

            // when -- on release the component reports a token BEHIND the last stored checkpoint (a regression)
            participant.releaseResult = upTo -> CompletableFuture.completedFuture(new GlobalSequenceTrackingToken(3L));
            UnitOfWorkTestUtils.SIMPLE_FACTORY.create()
                                              .executeWithResult(testSubject::finalCheckpoint)
                                              .orTimeout(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                                              .join();

            // then -- the regressive token is ignored; the stored token is never rewound
            verify(tokenStore, never()).storeToken(any(), anyString(), anyInt(), any());
        }

        @Test
        void aFailingReleaseFutureDoesNotFailTheFinalCheckpointSoTheClaimCanStillBeReleased() {
            // given -- a component whose onSegmentReleased completes exceptionally (e.g. its async flush failed)
            RecordingCheckpointing participant = new RecordingCheckpointing();
            participant.releaseResult =
                    upTo -> CompletableFuture.failedFuture(new IllegalStateException("flush failed"));
            WorkPackage testSubject = builder.autoMode(false).participants(List.of(participant)).build();
            testSubject.notifySegmentClaimed();

            // when -- the Coordinator drives the final checkpoint within a transaction it controls
            CompletableFuture<Void> result = UnitOfWorkTestUtils.SIMPLE_FACTORY.create()
                                                                              .executeWithResult(testSubject::finalCheckpoint)
                                                                              .orTimeout(TIMEOUT.toMillis(),
                                                                                         TimeUnit.MILLISECONDS);

            // then -- finalCheckpoint completes normally (so the Coordinator still releases the claim) and stores nothing;
            // the uncovered tail is reprocessed from the last stored token on the next claim
            assertThat(result).succeedsWithin(TIMEOUT);
            assertThat(participant.released).containsExactly(initialToken);
            verify(tokenStore, never()).storeToken(any(), anyString(), anyInt(), any());
        }
    }

    private static SimpleEntry<EventMessage> eventAt(long position) {
        return new SimpleEntry<>(
                EventTestUtils.asEventMessage("event-" + position),
                TrackingToken.addToContext(Context.empty(), new GlobalSequenceTrackingToken(position))
        );
    }

    private static class TestBatchProcessor implements WorkPackage.BatchProcessor {

        private final List<EventMessage> processed = new CopyOnWriteArrayList<>();
        private volatile CheckpointTrigger triggerInContext;
        private volatile Segment segmentInContext;

        @Override
        public MessageStream.Empty<Message> process(@NonNull List<MessageStream.Entry<? extends EventMessage>> entries,
                                                     @NonNull ProcessingContext context) {
            CheckpointTrigger.fromContext(context).ifPresent(trigger -> triggerInContext = trigger);
            Segment.fromContext(context).ifPresent(segment -> segmentInContext = segment);
            entries.forEach(entry -> processed.add(entry.message()));
            return MessageStream.empty();
        }
    }

    /**
     * Recording {@link Checkpointing} unit: captures the {@link CheckpointTrigger} handed on claim and records the
     * positions it is asked to cover, returning a configurable high-water mark.
     */
    private static class RecordingCheckpointing implements Checkpointing {

        private final List<TrackingToken> advanced = new CopyOnWriteArrayList<>();
        private final List<TrackingToken> released = new ArrayList<>();
        private volatile CheckpointTrigger trigger;
        private volatile Function<TrackingToken, CompletableFuture<TrackingToken>> advanceResult =
                CompletableFuture::completedFuture;
        private volatile Function<TrackingToken, CompletableFuture<TrackingToken>> releaseResult =
                CompletableFuture::completedFuture;

        @Override
        public void onSegmentClaimed(@NonNull Segment segment, @NonNull CheckpointTrigger trigger) {
            this.trigger = trigger;
        }

        @Override
        public CompletableFuture<TrackingToken> onCheckpointAdvanced(@NonNull Segment segment,
                                                                     @NonNull TrackingToken requested) {
            advanced.add(requested);
            return advanceResult.apply(requested);
        }

        @Override
        public CompletableFuture<TrackingToken> onSegmentReleased(@NonNull Segment segment,
                                                                  @NonNull TrackingToken upTo) {
            released.add(upTo);
            return releaseResult.apply(upTo);
        }
    }
}
