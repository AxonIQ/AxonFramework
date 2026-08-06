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

package org.axonframework.hunt.scenario;

import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.eventsourcing.eventstore.StorageEngineBackedEventStore;
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine;
import org.axonframework.messaging.core.ApplicationContext;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.SimpleUnitOfWorkFactory;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.SimpleEventBus;
import org.axonframework.messaging.eventhandling.SimpleEventHandlingComponent;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorConfiguration;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessor;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorConfiguration;
import org.axonframework.messaging.eventhandling.processing.streaming.progress.SegmentProgressContext;
import org.axonframework.messaging.eventhandling.processing.streaming.progress.SegmentProgressStrategy;
import org.axonframework.messaging.eventhandling.processing.streaming.token.GlobalSequenceTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.MergedTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.inmemory.InMemoryTokenStore;
import org.awaitility.Awaitility;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether the durable-progress anti-rewind guard holds once real, advancing stores have landed, when the candidates it
 * judges are {@link MergedTrackingToken merged} tokens.
 * <p>
 * The guard compares candidates on their {@code unwrapped upper-bound} positions. A merged token whose upper half is
 * ahead of the stored position therefore passes the guard regardless of its lower half -- and the lower half is
 * exactly where a merged segment resumes. Storing {@code merged(0, ahead)} is a durable rewind to the start of the
 * stream, and the guard admits it <b>even immediately after an advancing plain token was durably stored</b>. This is
 * not a first-store-of-a-claim hole: the compared field is set, the guard runs, and it approves the rewind on every
 * offer.
 * <p>
 * <b>These are expected-gap measurements</b>: they pass while the gap is open and turn red when the guard starts
 * judging a candidate's resume position (its lower bound) rather than only its upper bound. The control case pins the
 * direction that must never break: a merged token whose upper half is behind the stored position is refused.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
class MergedTokenRewindUnderAdvancingStoresTest {

    private static final String PROCESSOR = "merged-rewind-probe";
    private static final long DURABLE_POSITION = 500L;
    private static final long ADVANCE_BASE = 600L;
    private static final int ROUNDS = 50;
    private static final Duration BEAT = Duration.ofMillis(50);

    private static final ApplicationContext NO_COMPONENTS = new ApplicationContext() {
        @Override
        public <C> C component(Class<C> type, @Nullable String name) {
            throw new UnsupportedOperationException(
                    "The progress probe provides no component of type [" + type.getName() + "].");
        }
    };

    @Nested
    class InterleavedWithAdvancingStores {

        /**
         * Round r alternates two candidates through the seam, on the work package's own persist chain: first an
         * advancing plain token, whose durable landing the test <em>confirms</em> before moving on (the in-memory
         * token store defers its write to after the batch commits, so landing is polled, never assumed); then
         * {@code merged(0, ahead)}, whose upper bound covers the stored token, so the guard approves it and the
         * durable position -- the merged token's own position, the minimum of its halves -- drops to 0. Fifty rounds,
         * fifty rewinds, each past a position an advancing store had <em>already durably landed</em>. The compared
         * field is set on every one of them: this is not the first-store-of-a-claim hole.
         */
        @Test
        void everyMergedOfferRewindsPastTheAdvancingStoreThatAlreadyDurablyLanded() {
            // given a store holding progress well into the stream
            TokenStore tokenStore = new InMemoryTokenStore();
            tokenStore.initializeTokenSegments(PROCESSOR, 1, new GlobalSequenceTrackingToken(DURABLE_POSITION), null)
                      .orTimeout(30, TimeUnit.SECONDS)
                      .join();

            OfferedCandidateStrategy.OFFER.set(null);
            int rewinds = 0;
            try (Harness harness = Harness.start(tokenStore, OfferedCandidateStrategy::new)) {
                for (int round = 0; round < ROUNDS; round++) {
                    long advance = ADVANCE_BASE + round * 10L;

                    // when an advancing store durably lands
                    OfferedCandidateStrategy.OFFER.set(new GlobalSequenceTrackingToken(advance));
                    awaitDurablePosition(tokenStore, advance, "advance of round " + round);

                    // and then a merged(0, ahead) candidate is offered against that freshly stored position
                    OfferedCandidateStrategy.OFFER.set(new MergedTrackingToken(
                            new GlobalSequenceTrackingToken(0L),
                            new GlobalSequenceTrackingToken(advance + 5L)));
                    awaitDurablePosition(tokenStore, 0L, "merged rewind of round " + round);
                    rewinds++;
                }

                // then every one of the 50 merged offers was admitted and durably rewound the segment to the
                // start of the stream, each time past an advancing position the store had already accepted
                System.out.println("MERGED REWIND rounds=" + ROUNDS + " rewinds=" + rewinds
                                           + " advances=" + ADVANCE_BASE + ".."
                                           + (ADVANCE_BASE + (ROUNDS - 1) * 10L));
                assertThat(rewinds)
                        .as("every merged(0, ahead) offer rewinds past the advancing store that already landed")
                        .isEqualTo(ROUNDS);
                assertThat(harness.processor().isRunning()).isTrue();
            }
        }

        private void awaitDurablePosition(TokenStore tokenStore, long expected, String what) {
            Awaitility.await(what)
                      .atMost(Duration.ofSeconds(30))
                      .pollInterval(Duration.ofMillis(10))
                      .untilAsserted(() -> {
                          TrackingToken stored = tokenStore.fetchToken(PROCESSOR, 0, null)
                                                           .orTimeout(30, TimeUnit.SECONDS)
                                                           .join();
                          assertThat(stored).isNotNull();
                          assertThat(stored.position().orElse(-1L)).isEqualTo(expected);
                      });
        }
    }

    /**
     * The control: the guard does judge merged tokens -- by their upper bound. A merged token whose upper half is
     * behind the stored position is refused on every offer. This is what makes the case above a measurement of the
     * comparison's blind side rather than of a guard that ignores merged tokens entirely.
     */
    @Nested
    class MergedOfferBehindTheStoredPosition {

        @Test
        void isRefusedOnEveryOffer() {
            // given a store holding progress, and one advancing store to set the compared field
            TokenStore tokenStore = new InMemoryTokenStore();
            tokenStore.initializeTokenSegments(PROCESSOR, 1, new GlobalSequenceTrackingToken(DURABLE_POSITION), null)
                      .orTimeout(30, TimeUnit.SECONDS)
                      .join();

            BehindMergedStrategy.PERSISTS.set(0);
            try (Harness harness = Harness.start(tokenStore, ctx -> new BehindMergedStrategy(ctx))) {
                // when the advance and several behind-merged offers have run
                Awaitility.await("the advance and the behind-merged offers")
                          .atMost(Duration.ofSeconds(30))
                          .pollInterval(Duration.ofMillis(25))
                          .untilAsserted(() -> assertThat(BehindMergedStrategy.PERSISTS.get())
                                  .isGreaterThanOrEqualTo(4));

                // then the durable position is still the advanced one: merged(0, behind) never got through
                TrackingToken settled = tokenStore.fetchToken(PROCESSOR, 0, null)
                                                  .orTimeout(30, TimeUnit.SECONDS)
                                                  .join();
                System.out.println("MERGED CONTROL settled=" + settled
                                           + " after " + BehindMergedStrategy.PERSISTS.get() + " offers");
                assertThat(settled).isEqualTo(new GlobalSequenceTrackingToken(ADVANCE_BASE));
                assertThat(harness.processor().isRunning()).isTrue();
            }
        }
    }

    /**
     * Offers whatever candidate the test currently sets, on every batch commit, through the work package's own
     * persist chain -- the seam's intended threading. Repeating an already-stored candidate is ignored by the
     * framework's own equality short-circuit, so setting an offer once is enough for it to land exactly once.
     */
    private static final class OfferedCandidateStrategy implements SegmentProgressStrategy {

        private static final AtomicReference<@Nullable TrackingToken> OFFER = new AtomicReference<>();

        private final SegmentProgressContext context;

        private OfferedCandidateStrategy(SegmentProgressContext context) {
            this.context = context;
        }

        @Override
        public CompletableFuture<Void> onBatchCommit(ProcessingContext processingContext) {
            return context.persistProgress(OFFER.get(), processingContext);
        }

        @Override
        public boolean hasPendingWork() {
            return false;
        }
    }

    /**
     * Advances once, then repeatedly offers {@code merged(0, 400)} -- a merged token whose upper half is behind the
     * stored position, which the guard must refuse.
     */
    private static final class BehindMergedStrategy implements SegmentProgressStrategy {

        private static final AtomicInteger PERSISTS = new AtomicInteger();

        private final SegmentProgressContext context;

        private BehindMergedStrategy(SegmentProgressContext context) {
            this.context = context;
        }

        @Override
        public CompletableFuture<Void> onBatchCommit(ProcessingContext processingContext) {
            TrackingToken candidate = PERSISTS.getAndIncrement() == 0
                    ? new GlobalSequenceTrackingToken(ADVANCE_BASE)
                    : new MergedTrackingToken(new GlobalSequenceTrackingToken(0L),
                                              new GlobalSequenceTrackingToken(400L));
            return context.persistProgress(candidate, processingContext);
        }

        @Override
        public boolean hasPendingWork() {
            return false;
        }
    }

    /**
     * One running processor and the two executors it owns, closed together. Same shape as the harness in
     * {@link DurableProgressMonotonicityTest}.
     */
    private record Harness(PooledStreamingEventProcessor processor,
                           ScheduledExecutorService coordinator,
                           ScheduledExecutorService worker) implements AutoCloseable {

        static Harness start(TokenStore tokenStore,
                             java.util.function.Function<SegmentProgressContext, SegmentProgressStrategy> strategy) {
            EventStore eventStore = new StorageEngineBackedEventStore(new InMemoryEventStorageEngine(),
                                                                     new SimpleEventBus(),
                                                                     event -> Set.of());
            ScheduledExecutorService coordinator = new ScheduledThreadPoolExecutor(1, daemon("merged-coordinator"));
            ScheduledExecutorService worker = new ScheduledThreadPoolExecutor(1, daemon("merged-worker"));
            EventHandlingComponent component = SimpleEventHandlingComponent.create("merged-probe-component");
            PooledStreamingEventProcessorConfiguration configuration =
                    new PooledStreamingEventProcessorConfiguration(new EventProcessorConfiguration(PROCESSOR, null))
                            .eventSource(eventStore)
                            .tokenStore(tokenStore)
                            .unitOfWorkFactory(new SimpleUnitOfWorkFactory(NO_COMPONENTS))
                            .coordinatorExecutor(coordinator)
                            .workerExecutor(worker)
                            .initialSegmentCount(1)
                            .tokenClaimInterval(BEAT.toMillis())
                            .claimExtensionThreshold(BEAT.toMillis())
                            .batchSize(1)
                            .progressStrategyFactoryBuilder(components -> strategy::apply);
            PooledStreamingEventProcessor processor =
                    new PooledStreamingEventProcessor(PROCESSOR, List.of(component), configuration);
            processor.start().orTimeout(30, TimeUnit.SECONDS).join();
            return new Harness(processor, coordinator, worker);
        }

        @Override
        public void close() {
            try {
                processor.shutdown().orTimeout(30, TimeUnit.SECONDS).join();
            } catch (RuntimeException e) {
                // A processor that will not stop is a finding about the processor, not a reason to leak the threads.
            }
            coordinator.shutdownNow();
            worker.shutdownNow();
        }

        private static java.util.concurrent.ThreadFactory daemon(String prefix) {
            AtomicInteger counter = new AtomicInteger();
            return runnable -> {
                Thread thread = new Thread(runnable, prefix + "-" + counter.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            };
        }
    }
}
