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

import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.AsyncInMemoryStreamableEventSource;
import org.axonframework.messaging.eventhandling.EventTestUtils;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorModule;
import org.axonframework.messaging.eventhandling.processing.EventProcessor;
import org.axonframework.messaging.eventhandling.processing.streaming.progress.SegmentProgressContext;
import org.axonframework.messaging.eventhandling.processing.streaming.progress.SegmentProgressStrategy;
import org.axonframework.messaging.eventhandling.processing.streaming.progress.TokenStoringProgressStrategy;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.SimpleSegmentChangeListener;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.inmemory.InMemoryTokenStore;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.axonframework.common.FutureUtils.emptyCompletedFuture;

/**
 * Test class validating that the {@link PooledStreamingEventProcessor} does not claim a segment it released again
 * before the release of that segment has completed, regardless of how short the release deadline is.
 *
 * @author Allard Buijze
 */
class PooledStreamingEventProcessorSegmentReleaseTest {

    private static final String PROCESSOR_NAME = "segment-release-processor";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final long TOKEN_CLAIM_INTERVAL = 50;

    private static final String CLAIMED = "claimed";
    private static final String STORING_FINAL_PROGRESS = "storing final progress";
    private static final String FINAL_PROGRESS_STORED = "final progress stored";
    private static final String RELEASE_LISTENER_INVOKED = "release listener invoked";
    private static final String CLAIM_RELEASED = "claim released";
    private static final String RELEASED = "released";

    private final List<String> segmentLog = new CopyOnWriteArrayList<>();
    private final List<SegmentChange> segmentChanges = new CopyOnWriteArrayList<>();
    private final CompletableFuture<Void> releaseListenerGate = new CompletableFuture<>();
    private final Map<Integer, CompletableFuture<Void>> segmentReleaseListenerGates = new ConcurrentHashMap<>();
    private final CompletableFuture<Void> finalProgressGate = new CompletableFuture<>();
    private final AtomicBoolean firstFinalProgressFails = new AtomicBoolean();
    private final AtomicBoolean failNextEvent = new AtomicBoolean(true);
    private final AtomicReference<@Nullable Throwable> releaseListenerFailure = new AtomicReference<>();
    private final AsyncInMemoryStreamableEventSource eventSource = new AsyncInMemoryStreamableEventSource(false, false);
    private @Nullable AxonConfiguration configuration;

    @AfterEach
    void tearDown() {
        releaseListenerGate.complete(null);
        segmentReleaseListenerGates.values().forEach(gate -> gate.complete(null));
        finalProgressGate.complete(null);
        if (configuration != null) {
            configuration.shutdown();
        }
    }

    @Nested
    class WhenReleasedOnRequest {

        @BeforeEach
        void setUp() {
            finalProgressGate.complete(null);
        }

        @Test
        void segmentIsNotClaimedAgainBeforeItsReleaseListenersCompleted() {
            // given
            startProcessor();

            // when - the release deadline passes long before the release listener lets the release finish
            processor().releaseSegment(0, TOKEN_CLAIM_INTERVAL, TimeUnit.MILLISECONDS);
            await().atMost(TIMEOUT).until(() -> segmentLog.contains(RELEASE_LISTENER_INVOKED));

            // then
            await().during(Duration.ofMillis(TOKEN_CLAIM_INTERVAL * 10))
                   .atMost(TIMEOUT)
                   .untilAsserted(() -> assertThat(segmentLog).containsExactly(
                           CLAIMED, STORING_FINAL_PROGRESS, FINAL_PROGRESS_STORED, RELEASE_LISTENER_INVOKED
                   ));

            // when
            releaseListenerGate.complete(null);

            // then - the claim of the old release is released before the segment is claimed again
            await().atMost(TIMEOUT).untilAsserted(() -> assertThat(segmentLog).containsExactly(
                    CLAIMED, STORING_FINAL_PROGRESS, FINAL_PROGRESS_STORED, RELEASE_LISTENER_INVOKED,
                    CLAIM_RELEASED, CLAIMED
            ));
        }
    }

    @Nested
    class WhenAbortedByAnError {

        @BeforeEach
        void setUp() {
            releaseListenerGate.complete(null);
        }

        @Test
        void segmentIsNotClaimedAgainBeforeItsFinalProgressIsStored() {
            // given
            startProcessor();

            // when - an event fails, aborting the work package with a release deadline of one second
            eventSource.publishMessage(EventTestUtils.asEventMessage("event"));
            await().atMost(TIMEOUT).until(() -> segmentLog.contains(STORING_FINAL_PROGRESS));

            // then - the segment stays released after the deadline passed, while its final progress is not stored
            await().during(Duration.ofMillis(1500))
                   .atMost(TIMEOUT)
                   .untilAsserted(() -> assertThat(segmentLog).containsExactly(CLAIMED, STORING_FINAL_PROGRESS));

            // when
            finalProgressGate.complete(null);

            // then
            await().atMost(TIMEOUT).untilAsserted(() -> assertThat(segmentLog).containsExactly(
                    CLAIMED, STORING_FINAL_PROGRESS, FINAL_PROGRESS_STORED, RELEASE_LISTENER_INVOKED,
                    CLAIM_RELEASED, CLAIMED
            ));
        }

        @Test
        void releaseContinuesAfterAFailedFinalStore() {
            // given
            firstFinalProgressFails.set(true);
            finalProgressGate.complete(null);
            startProcessor();

            // when
            eventSource.publishMessage(EventTestUtils.asEventMessage("event"));

            // then - the listeners still wind down and the claim is still released before the segment is claimed again
            await().atMost(TIMEOUT).untilAsserted(() -> assertThat(segmentLog).containsExactly(
                    CLAIMED, STORING_FINAL_PROGRESS, RELEASE_LISTENER_INVOKED, CLAIM_RELEASED, CLAIMED
            ));
        }

        @Test
        void shutdownWaitsForTheReleaseOfAnAbortedWorkPackage() {
            // given
            startProcessor();
            eventSource.publishMessage(EventTestUtils.asEventMessage("event"));
            await().atMost(TIMEOUT).until(() -> segmentLog.contains(STORING_FINAL_PROGRESS));

            // when
            CompletableFuture<Void> shutdown = processor().shutdown();

            // then
            await().during(Duration.ofMillis(TOKEN_CLAIM_INTERVAL * 10))
                   .atMost(TIMEOUT)
                   .untilAsserted(() -> assertThat(shutdown).isNotDone());

            // when
            finalProgressGate.complete(null);

            // then
            await().atMost(TIMEOUT).untilAsserted(() -> assertThat(shutdown).isCompleted());
            assertThat(segmentLog).containsExactly(
                    CLAIMED, STORING_FINAL_PROGRESS, FINAL_PROGRESS_STORED, RELEASE_LISTENER_INVOKED, CLAIM_RELEASED
            );
        }
    }

    @Nested
    class WhenShutDown {

        @Test
        void shutdownCompletesWhenAReleaseEndsWithAnError() {
            // given
            finalProgressGate.complete(null);
            releaseListenerFailure.set(new Error("release listener crashed"));
            startProcessor();

            // when
            CompletableFuture<Void> shutdown = processor().shutdown();

            // then
            assertThat(shutdown).succeedsWithin(TIMEOUT);
        }
    }

    @Nested
    class WhenTheFinalStoreNeverCompletes {

        private static final long CLAIM_EXTENSION_THRESHOLD = 300;

        @BeforeEach
        void setUp() {
            releaseListenerGate.complete(null);
            startProcessor(1, CLAIM_EXTENSION_THRESHOLD);
        }

        @Test
        void segmentIsClaimedAgainOnceTheClaimExtensionThresholdPassed() {
            // when
            processor().releaseSegment(0, TOKEN_CLAIM_INTERVAL, TimeUnit.MILLISECONDS);

            // then - the final store counts as failed, so the release continues
            await().atMost(TIMEOUT).untilAsserted(() -> assertThat(segmentLog).containsExactly(
                    CLAIMED, STORING_FINAL_PROGRESS, RELEASE_LISTENER_INVOKED, CLAIM_RELEASED, CLAIMED
            ));
        }

        @Test
        void shutdownCompletesOnceTheClaimExtensionThresholdPassed() {
            // when
            CompletableFuture<Void> shutdown = processor().shutdown();

            // then
            assertThat(shutdown).succeedsWithin(TIMEOUT);
            assertThat(segmentLog).containsExactly(
                    CLAIMED, STORING_FINAL_PROGRESS, RELEASE_LISTENER_INVOKED, CLAIM_RELEASED
            );
        }
    }

    @Nested
    class WhenInstructionsWaitForSeveralReleases {

        private final CompletableFuture<Void> releaseOfSegmentZero = new CompletableFuture<>();
        private final CompletableFuture<Void> releaseOfSegmentOne = new CompletableFuture<>();
        private final List<String> completedInstructions = new CopyOnWriteArrayList<>();

        @BeforeEach
        void setUp() {
            finalProgressGate.complete(null);
            segmentReleaseListenerGates.put(0, releaseOfSegmentZero);
            segmentReleaseListenerGates.put(1, releaseOfSegmentOne);
            startProcessor(2);
            await().atMost(TIMEOUT).until(() -> processor().processingStatus().containsKey(1));
            processor().releaseSegment(0, TOKEN_CLAIM_INTERVAL, TimeUnit.MILLISECONDS);
            processor().releaseSegment(1, TOKEN_CLAIM_INTERVAL, TimeUnit.MILLISECONDS);
            await().atMost(TIMEOUT).untilAsserted(() -> assertThat(segmentChanges).contains(
                    new SegmentChange(RELEASED, new Segment(0, 1)), new SegmentChange(RELEASED, new Segment(1, 1))
            ));
        }

        @Test
        void aMergeAwaitingTheReleasesOfBothSegmentsIsPerformedBeforeALaterClaim() {
            // given
            CompletableFuture<Boolean> merge = recorded("merge", processor().mergeSegment(0));
            CompletableFuture<Boolean> claim = recorded("claim", processor().claimSegment(1));

            // when - the releases end one after the other
            releaseOfSegmentZero.complete(null);
            await().pollDelay(Duration.ofMillis(TOKEN_CLAIM_INTERVAL * 4)).until(() -> true);
            releaseOfSegmentOne.complete(null);

            // then - the merge goes first, after which there is no segment one to claim anymore
            assertThat(merge).succeedsWithin(TIMEOUT).isEqualTo(true);
            assertThat(claim).succeedsWithin(TIMEOUT).isEqualTo(false);
            assertThat(completedInstructions).containsExactly("merge", "claim");
        }

        @Test
        void instructionsAwaitingReleasesArePerformedInTheOrderTheyWereGivenWhateverOrderTheReleasesEndIn() {
            // given
            CompletableFuture<Boolean> splitOfOne = recorded("split 1", processor().splitSegment(1));
            CompletableFuture<Boolean> splitOfZero = recorded("split 0", processor().splitSegment(0));
            await().pollDelay(Duration.ofMillis(TOKEN_CLAIM_INTERVAL * 4)).until(() -> true);

            // when - the release awaited by the instruction given last ends first
            releaseOfSegmentZero.complete(null);
            await().pollDelay(Duration.ofMillis(TOKEN_CLAIM_INTERVAL * 4)).until(() -> true);
            releaseOfSegmentOne.complete(null);

            // then
            assertThat(splitOfOne).succeedsWithin(TIMEOUT).isEqualTo(true);
            assertThat(splitOfZero).succeedsWithin(TIMEOUT).isEqualTo(true);
            assertThat(completedInstructions).containsExactly("split 1", "split 0");
        }

        private CompletableFuture<Boolean> recorded(String instruction, CompletableFuture<Boolean> result) {
            return result.whenComplete((outcome, failure) -> completedInstructions.add(instruction));
        }
    }

    @Nested
    class WhenAnInstructionWaitsForARelease {

        @BeforeEach
        void setUp() {
            failNextEvent.set(false);
            finalProgressGate.complete(null);
            startProcessor(2);
            await().atMost(TIMEOUT).until(() -> processor().processingStatus().containsKey(1));
            processor().releaseSegment(0, TOKEN_CLAIM_INTERVAL, TimeUnit.MILLISECONDS);
            await().atMost(TIMEOUT).until(() -> segmentLog.contains(RELEASE_LISTENER_INVOKED));
        }

        @Test
        void anInstructionForAnotherSegmentWaitsBehindAnInstructionWaitingForTheRelease() {
            // given - a claim of segment one, which this processor already processes, would succeed right away
            CompletableFuture<Boolean> claimOfZero = processor().claimSegment(0);
            CompletableFuture<Boolean> claimOfOne = processor().claimSegment(1);

            // then
            await().during(Duration.ofMillis(TOKEN_CLAIM_INTERVAL * 4))
                   .atMost(TIMEOUT)
                   .untilAsserted(() -> assertThat(claimOfOne).isNotDone());

            // when
            releaseListenerGate.complete(null);

            // then
            assertThat(claimOfZero).succeedsWithin(TIMEOUT).isEqualTo(true);
            assertThat(claimOfOne).succeedsWithin(TIMEOUT).isEqualTo(true);
        }

        @Test
        void otherSegmentsKeepProcessingWhileAClaimWaitsForTheRelease() {
            assertOtherSegmentsKeepProcessingWhileWaitingFor(processor().claimSegment(0));
        }

        @Test
        void otherSegmentsKeepProcessingWhileASplitWaitsForTheRelease() {
            assertOtherSegmentsKeepProcessingWhileWaitingFor(processor().splitSegment(0));
        }

        @Test
        void otherSegmentsKeepProcessingWhileAMergeWaitsForTheRelease() {
            assertOtherSegmentsKeepProcessingWhileWaitingFor(processor().mergeSegment(0));
        }

        @Test
        void instructionsWaitingForTheReleaseRunInTheOrderTheyWereGiven() {
            // given
            List<String> completedInstructions = new CopyOnWriteArrayList<>();
            CompletableFuture<Boolean> claim = processor().claimSegment(0);
            claim.thenRun(() -> completedInstructions.add("claim"));
            CompletableFuture<Boolean> split = processor().splitSegment(0);
            split.thenRun(() -> completedInstructions.add("split"));
            await().pollDelay(Duration.ofMillis(TOKEN_CLAIM_INTERVAL * 4)).until(() -> true);

            // when
            releaseListenerGate.complete(null);

            // then
            assertThat(claim).succeedsWithin(TIMEOUT).isEqualTo(true);
            assertThat(split).succeedsWithin(TIMEOUT).isEqualTo(true);
            assertThat(completedInstructions).containsExactly("claim", "split");
        }

        private void assertOtherSegmentsKeepProcessingWhileWaitingFor(CompletableFuture<Boolean> instruction) {
            // given - the coordinator picked up the instruction
            await().pollDelay(Duration.ofMillis(TOKEN_CLAIM_INTERVAL * 4)).until(() -> true);
            OptionalLong positionBefore = processor().processingStatus().get(1).getCurrentPosition();

            // when
            eventSource.publishMessage(EventTestUtils.asEventMessage("event"));

            // then - the instruction waits for the release, while the other segment keeps up with the stream
            await().atMost(TIMEOUT).untilAsserted(
                    () -> assertThat(processor().processingStatus().get(1).getCurrentPosition())
                            .isNotEqualTo(positionBefore)
            );
            assertThat(instruction).isNotDone();

            // when
            releaseListenerGate.complete(null);

            // then
            assertThat(instruction).succeedsWithin(TIMEOUT).isEqualTo(true);
        }
    }

    private void startProcessor() {
        startProcessor(1);
    }

    private void startProcessor(int segmentCount) {
        startProcessor(segmentCount, TIMEOUT.toMillis());
    }

    private void startProcessor(int segmentCount, long claimExtensionThreshold) {
        var listener = new SimpleSegmentChangeListener(
                (segment, from) -> {
                    segmentLog.add(CLAIMED);
                    segmentChanges.add(new SegmentChange(CLAIMED, segment));
                    return emptyCompletedFuture();
                },
                segment -> {
                    segmentLog.add(RELEASE_LISTENER_INVOKED);
                    segmentChanges.add(new SegmentChange(RELEASED, segment));
                    Throwable failure = releaseListenerFailure.get();
                    if (failure != null) {
                        return CompletableFuture.failedFuture(failure);
                    }
                    return segmentReleaseListenerGates.getOrDefault(segment.getSegmentId(), releaseListenerGate);
                }
        );
        var module = EventProcessorModule.pooledStreaming(PROCESSOR_NAME)
                                         .eventHandlingComponents(c -> c.autodetected("handler",
                                                                                      cfg -> new Handler()))
                                         .customized((cfg, c) -> c.eventSource(eventSource)
                                                                  .tokenStore(new RecordingTokenStore())
                                                                  .initialSegmentCount(segmentCount)
                                                                  .tokenClaimInterval(TOKEN_CLAIM_INTERVAL)
                                                                  .claimExtensionThreshold(claimExtensionThreshold)
                                                                  .progressStrategyFactoryBuilder(
                                                                          components -> GatedReleaseStrategy::new
                                                                  )
                                                                  .addSegmentChangeListener(listener));
        configuration = MessagingConfigurer.create()
                                           .eventProcessing(ep -> ep.pooledStreaming(ps -> ps.processor(module)))
                                           .build();
        configuration.start();
        await().atMost(TIMEOUT).until(() -> processor().processingStatus().containsKey(0));
    }

    private PooledStreamingEventProcessor processor() {
        return (PooledStreamingEventProcessor) configuration.getComponents(EventProcessor.class).get(PROCESSOR_NAME);
    }

    /**
     * Stores the final progress of a released segment only once the test opens the {@link #finalProgressGate}, like a
     * strategy that has to wait for in-flight work before it knows the position that is safe to store.
     */
    private class GatedReleaseStrategy implements SegmentProgressStrategy {

        private final SegmentProgressContext context;
        private final TokenStoringProgressStrategy delegate;

        private GatedReleaseStrategy(SegmentProgressContext context) {
            this.context = context;
            this.delegate = new TokenStoringProgressStrategy(context);
        }

        @Override
        public CompletableFuture<Void> onBatchCommit(ProcessingContext processingContext) {
            return delegate.onBatchCommit(processingContext);
        }

        @Override
        public boolean hasPendingWork() {
            return delegate.hasPendingWork();
        }

        @Override
        public CompletableFuture<Void> onSegmentReleased(ProcessingContext processingContext) {
            segmentLog.add(STORING_FINAL_PROGRESS);
            return finalProgressGate.thenCompose(ignored -> {
                if (firstFinalProgressFails.compareAndSet(true, false)) {
                    return CompletableFuture.failedFuture(new IllegalStateException("final store failed"));
                }
                return context.persistProgress(context.lastConsumedToken(), processingContext)
                              .thenRun(() -> segmentLog.add(FINAL_PROGRESS_STORED));
            });
        }
    }

    private record SegmentChange(String change, Segment segment) {

    }

    private class RecordingTokenStore extends InMemoryTokenStore {

        @Override
        public CompletableFuture<Void> releaseClaim(String processorName,
                                                    int segment,
                                                    @Nullable ProcessingContext context) {
            segmentLog.add(CLAIM_RELEASED);
            return super.releaseClaim(processorName, segment, context);
        }
    }

    private class Handler {

        @EventHandler
        void on(String event) {
            if (failNextEvent.compareAndSet(true, false)) {
                throw new IllegalStateException("simulated failure handling " + event);
            }
        }
    }
}
