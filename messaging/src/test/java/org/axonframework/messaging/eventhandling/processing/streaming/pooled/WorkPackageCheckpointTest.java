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
import org.axonframework.messaging.eventhandling.processing.streaming.token.ReplayToken;
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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
            WorkPackage testSubject = builder.autoCheckpointing(false).checkpointingParticipants(List.of(participant)).build();
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
            WorkPackage testSubject = builder.autoCheckpointing(false).checkpointingParticipants(List.of(participant)).build();
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
            WorkPackage testSubject = builder.autoCheckpointing(false).checkpointingParticipants(List.of(stuck, leader)).build();
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
            WorkPackage testSubject = builder.autoCheckpointing(false).checkpointingParticipants(List.of(participant)).build();
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
            WorkPackage testSubject = builder.autoCheckpointing(false).checkpointingParticipants(List.of(leader, lagging)).build();
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
            WorkPackage testSubject = builder.autoCheckpointing(false).checkpointingParticipants(List.of(a, b)).build();
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
            WorkPackage testSubject = builder.autoCheckpointing(false).checkpointingParticipants(List.of(participant)).build();
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
            WorkPackage testSubject = builder.autoCheckpointing(false).checkpointingParticipants(List.of(participant)).build();
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
            WorkPackage testSubject = builder.autoCheckpointing(false).checkpointingParticipants(List.of(participant)).build();
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
            WorkPackage testSubject = builder.autoCheckpointing(false).checkpointingParticipants(List.of(participant)).build();
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
            WorkPackage testSubject = builder.autoCheckpointing(false).checkpointingParticipants(List.of(participant)).build();
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
            WorkPackage testSubject = builder.autoCheckpointing(false).checkpointingParticipants(List.of(participant)).build();
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
            // given -- a self-checkpointing component co-located with an auto component (autoCheckpointing = true)
            RecordingCheckpointing participant = new RecordingCheckpointing();
            WorkPackage testSubject = builder.autoCheckpointing(true).checkpointingParticipants(List.of(participant)).build();
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
        void checkpointOnReleaseStoresTheReconciledReleasePosition() {
            // given -- a single participant reports position 9 on release
            RecordingCheckpointing participant = new RecordingCheckpointing();
            participant.releaseResult = upTo -> CompletableFuture.completedFuture(new GlobalSequenceTrackingToken(9L));
            WorkPackage testSubject = builder.autoCheckpointing(false).checkpointingParticipants(List.of(participant)).build();
            testSubject.notifySegmentClaimed();

            // when -- the Coordinator drives the final checkpoint within a transaction it controls
            UnitOfWorkTestUtils.SIMPLE_FACTORY.create()
                                              .executeWithResult(testSubject::checkpointOnRelease)
                                              .orTimeout(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                                              .join();

            // then
            assertThat(participant.released).containsExactly(initialToken);
            verify(tokenStore).storeToken(eq(new GlobalSequenceTrackingToken(9L)), anyString(), anyInt(), any());
        }

        @Test
        void onReleaseALaggingParticipantIsReRequestedUpToTheLeaderAndTheHighestPositionIsStored() {
            // given -- two participants report DIFFERENT positions on release: a leader at 10 and a laggard at 7 that
            // can still advance to the agreed position when re-requested (via onCheckpointAdvanced)
            RecordingCheckpointing leader = new RecordingCheckpointing();
            leader.releaseResult = upTo -> CompletableFuture.completedFuture(new GlobalSequenceTrackingToken(10L));
            leader.advanceResult = requested -> CompletableFuture.completedFuture(new GlobalSequenceTrackingToken(10L));
            RecordingCheckpointing lagging = new RecordingCheckpointing();
            lagging.releaseResult = upTo -> CompletableFuture.completedFuture(new GlobalSequenceTrackingToken(7L));
            lagging.advanceResult = requested -> CompletableFuture.completedFuture(
                    new GlobalSequenceTrackingToken(Math.max(requested.position().orElse(0L), 7L))
            );
            WorkPackage testSubject =
                    builder.autoCheckpointing(false).checkpointingParticipants(List.of(leader, lagging)).build();
            testSubject.notifySegmentClaimed();

            // when -- the Coordinator drives the final checkpoint
            UnitOfWorkTestUtils.SIMPLE_FACTORY.create()
                                              .executeWithResult(testSubject::checkpointOnRelease)
                                              .orTimeout(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                                              .join();

            // then -- reconciliation drives the laggard up to the leader's 10 and stores that (the highest), NOT the
            // lowerBound (7) which would leave the leader durably ahead of the stored token
            verify(tokenStore).storeToken(eq(new GlobalSequenceTrackingToken(10L)), anyString(), anyInt(), any());
            assertThat(lagging.advanced).containsExactly(new GlobalSequenceTrackingToken(10L));
        }

        @Test
        void onReleaseAParticipantThatCannotReachTheAgreedPositionFallsBackToTheLowerBound() {
            // given -- on release a leader reports 10 and a stuck participant reports 6 and CANNOT advance further when
            // re-requested, so the positions cannot be reconciled
            RecordingCheckpointing leader = new RecordingCheckpointing();
            leader.releaseResult = upTo -> CompletableFuture.completedFuture(new GlobalSequenceTrackingToken(10L));
            leader.advanceResult = requested -> CompletableFuture.completedFuture(new GlobalSequenceTrackingToken(10L));
            RecordingCheckpointing stuck = new RecordingCheckpointing();
            stuck.releaseResult = upTo -> CompletableFuture.completedFuture(new GlobalSequenceTrackingToken(6L));
            stuck.advanceResult = requested -> CompletableFuture.completedFuture(new GlobalSequenceTrackingToken(6L));
            WorkPackage testSubject =
                    builder.autoCheckpointing(false).checkpointingParticipants(List.of(leader, stuck)).build();
            testSubject.notifySegmentClaimed();

            // when -- the Coordinator drives the final checkpoint
            UnitOfWorkTestUtils.SIMPLE_FACTORY.create()
                                              .executeWithResult(testSubject::checkpointOnRelease)
                                              .orTimeout(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                                              .join();

            // then -- reconciliation fails (the stuck participant cannot cover 10), but the claim must still be freed:
            // the fallback stores the lowerBound of the reported release positions (6) and logs a warning, rather than
            // failing outright. The uncovered tail (6, 10] is reprocessed on the next claim.
            verify(tokenStore).storeToken(eq(new GlobalSequenceTrackingToken(6L)), anyString(), anyInt(), any());
        }

        @Test
        void aReleaseReturningLatestStoresTheLastConsumedToken() {
            // given -- one event handled (last consumed token at position 1), nothing checkpointed yet
            RecordingCheckpointing participant = new RecordingCheckpointing();
            participant.releaseResult = upTo -> CompletableFuture.completedFuture(TrackingToken.LATEST);
            WorkPackage testSubject = builder.autoCheckpointing(false).checkpointingParticipants(List.of(participant)).build();
            testSubject.notifySegmentClaimed();
            testSubject.scheduleEvent(eventAt(1L));
            await().atMost(TIMEOUT).untilAsserted(() -> assertThat(batchProcessor.processed).hasSize(1));

            // when -- on release the component reports LATEST ("everything you gave me")
            UnitOfWorkTestUtils.SIMPLE_FACTORY.create()
                                              .executeWithResult(testSubject::checkpointOnRelease)
                                              .orTimeout(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                                              .join();

            // then -- LATEST resolved to the last consumed token (position 1) and stored
            verify(tokenStore).storeToken(eq(new GlobalSequenceTrackingToken(1L)), anyString(), anyInt(), any());
        }

        @Test
        void aReleaseTokenBehindTheLastCheckpointIsIgnoredAndDoesNotRegressTheStoredToken() {
            // given -- the stored checkpoint has advanced to position 5
            RecordingCheckpointing participant = new RecordingCheckpointing();
            WorkPackage testSubject = builder.autoCheckpointing(false).checkpointingParticipants(List.of(participant)).build();
            testSubject.notifySegmentClaimed();
            participant.trigger.requestCheckpoint(new GlobalSequenceTrackingToken(5L));
            await().atMost(TIMEOUT).untilAsserted(() -> verify(tokenStore).storeToken(
                    eq(new GlobalSequenceTrackingToken(5L)), anyString(), anyInt(), any()
            ));
            clearInvocations(tokenStore);

            // when -- on release the component reports a token BEHIND the last stored checkpoint (a regression)
            participant.releaseResult = upTo -> CompletableFuture.completedFuture(new GlobalSequenceTrackingToken(3L));
            UnitOfWorkTestUtils.SIMPLE_FACTORY.create()
                                              .executeWithResult(testSubject::checkpointOnRelease)
                                              .orTimeout(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                                              .join();

            // then -- the regressive token is ignored; the stored token is never rewound
            verify(tokenStore, never()).storeToken(any(), anyString(), anyInt(), any());
        }

        @Test
        void aReleaseTokenIncomparableToTheLastCheckpointIsIgnoredAndDoesNotRewindAnySource() {
            // given -- the stored checkpoint is a multi-source position {A=5, B=3}
            RecordingCheckpointing participant = new RecordingCheckpointing();
            WorkPackage testSubject = builder.autoCheckpointing(false).checkpointingParticipants(List.of(participant)).build();
            testSubject.notifySegmentClaimed();
            participant.trigger.requestCheckpoint(new TwoAxisToken(5L, 3L));
            await().atMost(TIMEOUT).untilAsserted(() -> verify(tokenStore).storeToken(
                    eq(new TwoAxisToken(5L, 3L)), anyString(), anyInt(), any()
            ));
            clearInvocations(tokenStore);

            // when -- on release the component reports {A=4, B=7}: source B advanced but source A fell back, so the two
            // tokens are incomparable (neither covers the other) rather than a clean advance or a strict regression
            participant.releaseResult = upTo -> CompletableFuture.completedFuture(new TwoAxisToken(4L, 7L));
            UnitOfWorkTestUtils.SIMPLE_FACTORY.create()
                                              .executeWithResult(testSubject::checkpointOnRelease)
                                              .orTimeout(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                                              .join();

            // then -- the incomparable token is ignored; storing it would have rewound source A from 5 to 4
            verify(tokenStore, never()).storeToken(any(), anyString(), anyInt(), any());
        }

        @Test
        void aFailingReleaseFutureDoesNotFailTheFinalCheckpointSoTheClaimCanStillBeReleased() {
            // given -- a component whose onSegmentReleased completes exceptionally (e.g. its async flush failed)
            RecordingCheckpointing participant = new RecordingCheckpointing();
            participant.releaseResult =
                    upTo -> CompletableFuture.failedFuture(new IllegalStateException("flush failed"));
            WorkPackage testSubject = builder.autoCheckpointing(false).checkpointingParticipants(List.of(participant)).build();
            testSubject.notifySegmentClaimed();

            // when -- the Coordinator drives the final checkpoint within a transaction it controls
            CompletableFuture<Void> result = UnitOfWorkTestUtils.SIMPLE_FACTORY.create()
                                                                              .executeWithResult(testSubject::checkpointOnRelease)
                                                                              .orTimeout(TIMEOUT.toMillis(),
                                                                                         TimeUnit.MILLISECONDS);

            // then -- checkpointOnRelease completes normally (so the Coordinator still releases the claim) and stores nothing;
            // the uncovered tail is reprocessed from the last stored token on the next claim
            assertThat(result).succeedsWithin(TIMEOUT);
            assertThat(participant.released).containsExactly(initialToken);
            verify(tokenStore, never()).storeToken(any(), anyString(), anyInt(), any());
        }
    }

    @Nested
    class MultiSegmentTriggerKeying {

        @Test
        void eachSegmentReceivesItsOwnTriggerAndRequestsAreRoutedPerSegment() {
            // given -- a SINGLE Checkpointing component shared across two segments (two WorkPackages). A component that
            // keys its trigger by segment (rather than a naive `this.trigger = trigger`, which would clobber) must
            // receive a distinct trigger for each segment.
            Segment[] segments = Segment.ROOT_SEGMENT.split();
            Segment segmentZero = segments[0];
            Segment segmentOne = segments[1];
            tokenStore.initializeSegment(initialToken, PROCESSOR_NAME, segmentOne, null);

            SegmentKeyedCheckpointing participant = new SegmentKeyedCheckpointing();
            WorkPackage packageZero = workPackageFor(segmentZero, participant);
            WorkPackage packageOne = workPackageFor(segmentOne, participant);
            packageZero.notifySegmentClaimed();
            packageOne.notifySegmentClaimed();

            // then -- a distinct trigger is handed for each segment
            CheckpointTrigger triggerZero = participant.triggers.get(segmentZero);
            CheckpointTrigger triggerOne = participant.triggers.get(segmentOne);
            assertThat(triggerZero).isNotNull();
            assertThat(triggerOne).isNotNull();
            assertThat(triggerZero).isNotSameAs(triggerOne);

            // when -- a checkpoint is requested on segment 0's trigger only
            triggerZero.requestCheckpoint(new GlobalSequenceTrackingToken(5L));

            // then -- the token is stored for segment 0 only; segment 1 is never touched
            await().atMost(TIMEOUT).untilAsserted(() -> verify(tokenStore).storeToken(
                    eq(new GlobalSequenceTrackingToken(5L)), anyString(), eq(segmentZero.getSegmentId()), any()
            ));
            verify(tokenStore, never()).storeToken(any(), anyString(), eq(segmentOne.getSegmentId()), any());
        }

        private WorkPackage workPackageFor(Segment segment, Checkpointing participant) {
            return WorkPackage.builder()
                              .name(PROCESSOR_NAME)
                              .tokenStore(tokenStore)
                              .unitOfWorkFactory(UnitOfWorkTestUtils.SIMPLE_FACTORY)
                              .executorService(executorService)
                              .eventFilter((event, context, seg) -> true)
                              .batchProcessor(new TestBatchProcessor())
                              .segment(segment)
                              .initialToken(initialToken)
                              .batchSize(1)
                              .claimExtensionThreshold(5000)
                              .autoCheckpointing(false)
                              .checkpointingParticipants(List.of(participant))
                              .segmentStatusUpdater(op -> {
                              })
                              .build();
        }
    }

    @Nested
    class ReplayBoundary {

        @Test
        void aPlainTokenPastTheReplayBoundaryAdvancesBeyondTheStoredReplayTokenAndIsStored() {
            // given -- the stored checkpoint is still a ReplayToken (replaying position 5, reset was taken at 10)
            RecordingCheckpointing participant = new RecordingCheckpointing();
            WorkPackage testSubject = builder.autoCheckpointing(false).checkpointingParticipants(List.of(participant)).build();
            testSubject.notifySegmentClaimed();
            TrackingToken replayToken = ReplayToken.createReplayToken(new GlobalSequenceTrackingToken(10L),
                                                                      new GlobalSequenceTrackingToken(5L));
            participant.trigger.requestCheckpoint(replayToken);
            await().atMost(TIMEOUT).untilAsserted(() -> verify(tokenStore).storeToken(
                    eq(replayToken), anyString(), anyInt(), any()
            ));
            clearInvocations(tokenStore);

            // when -- a plain stream token past the replay position is checkpointed. Comparing the raw tokens directly
            // would throw (a GlobalSequenceTrackingToken rejects the wrapped ReplayToken), so the store decision must
            // compare the unwrapped positions to recognize this as the advance it is.
            participant.trigger.requestCheckpoint(new GlobalSequenceTrackingToken(6L));

            // then -- the live token advances beyond the stored replay position (5) and is stored
            await().atMost(TIMEOUT).untilAsserted(() -> verify(tokenStore).storeToken(
                    eq(new GlobalSequenceTrackingToken(6L)), anyString(), anyInt(), any()
            ));
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

    /**
     * A {@link Checkpointing} unit that correctly retains the {@link CheckpointTrigger} <em>keyed by segment</em>, so a
     * single instance can participate in multiple segments (multiple {@link WorkPackage}s) at once.
     */
    private static class SegmentKeyedCheckpointing implements Checkpointing {

        private final Map<Segment, CheckpointTrigger> triggers = new ConcurrentHashMap<>();

        @Override
        public void onSegmentClaimed(@NonNull Segment segment, @NonNull CheckpointTrigger trigger) {
            triggers.put(segment, trigger);
        }

        @Override
        public CompletableFuture<TrackingToken> onCheckpointAdvanced(@NonNull Segment segment,
                                                                     @NonNull TrackingToken requested) {
            return CompletableFuture.completedFuture(requested);
        }
    }

    /**
     * A minimal two-axis {@link TrackingToken}, a deliberately simplified stand-in for a multi-source token (such as
     * the {@code MultiSourceTrackingToken} provided by an extension). It {@link #covers(TrackingToken) covers} another
     * two-axis token only when BOTH axes are at or beyond the other's, so two tokens where one axis advanced while the
     * other regressed are mutually non-covering (incomparable) -- the partial-regression case that must never be
     * stored, as doing so would rewind progress on the regressed axis.
     */
    private record TwoAxisToken(long a, long b) implements TrackingToken {

        @Override
        public TrackingToken lowerBound(TrackingToken other) {
            TwoAxisToken that = (TwoAxisToken) other;
            return new TwoAxisToken(Math.min(a, that.a), Math.min(b, that.b));
        }

        @Override
        public TrackingToken upperBound(TrackingToken other) {
            TwoAxisToken that = (TwoAxisToken) other;
            return new TwoAxisToken(Math.max(a, that.a), Math.max(b, that.b));
        }

        @Override
        public boolean covers(TrackingToken other) {
            TwoAxisToken that = (TwoAxisToken) other;
            return a >= that.a && b >= that.b;
        }
    }
}
