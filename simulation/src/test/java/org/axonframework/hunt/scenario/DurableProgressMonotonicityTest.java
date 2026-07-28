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
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingTokenUtils;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.inmemory.InMemoryTokenStore;
import org.awaitility.Awaitility;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether a segment's durable progress can be rewound by the progress-persistence seam it is stored through.
 * <p>
 * The framework states plainly that it cannot: a candidate token that does not cover the last stored one is
 * "ignored with a warning rather than persisted, so a misbehaving component can never rewind progress on any source".
 * The guard that enforces it compares against a field the work package fills in the first time it stores something,
 * and a work package is built fresh every time its segment is claimed -- so on the first store of every claim cycle
 * there is nothing to compare against and any position at all is written, including one behind the position the store
 * was already holding.
 * <p>
 * <b>This test asserts the rewind, not the guarantee.</b> It is an expected-gap test: it passes while the gap is open
 * and turns red the moment the work package seeds its stored-token field from the token its claim handed it, which is
 * what closes the gap. A failure here is the good news.
 * <p>
 * The candidate offered is not an arbitrary bad token. It is what the framework's own
 * {@link TrackingTokenUtils#lowerBound(java.util.Collection) lowerBound} helper returns for a set of participants that
 * have not reported a position yet, which is the reconcile-several-participants-to-one-safe-position use the seam is
 * documented for.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
class DurableProgressMonotonicityTest {

    private static final String PROCESSOR = "progress-probe";
    private static final long DURABLE_POSITION = 500L;
    private static final long FIRST_DESCENDING_CANDIDATE = 400L;
    private static final long ADVANCED_POSITION = 600L;
    private static final Duration BEAT = Duration.ofMillis(150);

    /**
     * The probe resolves no components: it never resets a processor, which is the one thing that needs a converter.
     */
    private static final ApplicationContext NO_COMPONENTS = new ApplicationContext() {
        @Override
        public <C> C component(Class<C> type, @Nullable String name) {
            throw new UnsupportedOperationException(
                    "The progress probe provides no component of type [" + type.getName() + "].");
        }
    };

    @Nested
    class AFirstStoreAfterAClaim {

        @Test
        void writesACandidateBehindThePositionTheStoreAlreadyHeld() {
            // given a token store holding one segment's progress at a position well into the stream
            TokenStore tokenStore = new InMemoryTokenStore();
            tokenStore.initializeTokenSegments(PROCESSOR, 1, new GlobalSequenceTrackingToken(DURABLE_POSITION), null)
                      .orTimeout(30, TimeUnit.SECONDS)
                      .join();
            assertThat(storedPosition(tokenStore)).isEqualTo(DURABLE_POSITION);

            // and a processor whose progress strategy reconciles participants that have not reported a position yet
            ReconcilingProgressStrategy.PERSISTS.set(0);
            try (Harness harness = Harness.start(tokenStore, ReconcilingProgressStrategy::new)) {

                // when the processor claims the segment and the strategy's first persist reaches the framework
                Awaitility.await("the strategy's first persist")
                          .atMost(Duration.ofSeconds(30))
                          .pollInterval(Duration.ofMillis(25))
                          .untilAsserted(() -> assertThat(ReconcilingProgressStrategy.PERSISTS.get()).isPositive());

                // then the store no longer holds the progress it held before the claim
                Awaitility.await("the durable position to be rewound")
                          .atMost(Duration.ofSeconds(30))
                          .pollInterval(Duration.ofMillis(25))
                          .untilAsserted(() -> assertThat(storedPosition(tokenStore))
                                  .as("durable progress after the first persist of a fresh claim")
                                  .isLessThan(DURABLE_POSITION));
                long after = storedPosition(tokenStore);
                System.out.println("DURABLE PROGRESS before=" + DURABLE_POSITION + " after=" + after
                                           + " persists=" + ReconcilingProgressStrategy.PERSISTS.get()
                                           + " (TrackingToken.FIRST reports position 0)");
                // The rewind is all the way to the start of the stream, which is what the candidate named.
                assertThat(after).isEqualTo(0L);
                // The processor is entirely healthy: nothing failed, nothing aborted, no claim was lost. The only
                // trace the framework leaves of the rewind is the progress itself.
                assertThat(harness.processor().isRunning()).isTrue();
                assertThat(harness.processor().processingStatus()).hasSize(1);
                assertThat(harness.processor().processingStatus().values())
                        .noneMatch(status -> status.isErrorState());
            }
        }

        @Test
        void isTheOnlyOneTheGuardDoesNotJudge() {
            // given the same store, and a strategy offering a strictly descending series of candidates
            TokenStore tokenStore = new InMemoryTokenStore();
            tokenStore.initializeTokenSegments(PROCESSOR, 1, new GlobalSequenceTrackingToken(DURABLE_POSITION), null)
                      .orTimeout(30, TimeUnit.SECONDS)
                      .join();
            DescendingProgressStrategy.PERSISTS.set(0);
            try (Harness harness = Harness.start(tokenStore, DescendingProgressStrategy::new)) {
                // when several persist cycles have run
                Awaitility.await("several persist cycles")
                          .atMost(Duration.ofSeconds(30))
                          .pollInterval(Duration.ofMillis(25))
                          .untilAsserted(() -> assertThat(DescendingProgressStrategy.PERSISTS.get())
                                  .isGreaterThanOrEqualTo(3));

                // then only the first candidate was written: the guard judges every later one against it and refuses
                // them all. Exactly one rewind per claim cycle gets through, and it is durable.
                long settled = storedPosition(tokenStore);
                System.out.println("DURABLE PROGRESS settled=" + settled + " after "
                                           + DescendingProgressStrategy.PERSISTS.get()
                                           + " descending candidates from " + FIRST_DESCENDING_CANDIDATE
                                           + " downwards, against a store holding " + DURABLE_POSITION);
                assertThat(settled)
                        .as("the guard refuses every candidate after the first, and never undoes the first")
                        .isEqualTo(FIRST_DESCENDING_CANDIDATE);
                assertThat(harness.processor().isRunning()).isTrue();
            }
        }
    }

    /**
     * The control: with the stored-token field already set, the guard does exactly what it promises.
     * <p>
     * This is what makes the two cases above evidence rather than a pair of assertions that happen to hold. The
     * strategy here offers an advancing candidate first, which sets the field the guard compares against, and only
     * then offers the same rewinding candidate the first case used. The rewind is refused. So the mechanism is the
     * field being unset and nothing else -- and seeding it from the claimed token, which is the candidate fix, is
     * enough to close the gap.
     * <p>
     * It is also the direction that must never break: unlike the two cases above, this one asserts the guarantee, so
     * it stays green when the gap is closed.
     */
    @Nested
    class AStoreAfterTheFieldIsSet {

        @Test
        void isRefusedWhenItDoesNotAdvance() {
            // given a store holding progress, and a strategy that advances once before trying to rewind
            TokenStore tokenStore = new InMemoryTokenStore();
            tokenStore.initializeTokenSegments(PROCESSOR, 1, new GlobalSequenceTrackingToken(DURABLE_POSITION), null)
                      .orTimeout(30, TimeUnit.SECONDS)
                      .join();
            AdvanceThenRewindProgressStrategy.PERSISTS.set(0);
            try (Harness harness = Harness.start(tokenStore, AdvanceThenRewindProgressStrategy::new)) {
                // when the advancing store and at least two rewind attempts have run
                Awaitility.await("the advance and the rewind attempts")
                          .atMost(Duration.ofSeconds(30))
                          .pollInterval(Duration.ofMillis(25))
                          .untilAsserted(() -> assertThat(AdvanceThenRewindProgressStrategy.PERSISTS.get())
                                  .isGreaterThanOrEqualTo(3));

                // then the guard held every rewind off, and the durable position is the advanced one
                long settled = storedPosition(tokenStore);
                System.out.println("DURABLE PROGRESS guarded=" + settled + " after "
                                           + AdvanceThenRewindProgressStrategy.PERSISTS.get()
                                           + " cycles, of which all but the first offered TrackingToken.FIRST");
                assertThat(settled)
                        .as("with the compared field set, the documented guard refuses the rewind")
                        .isEqualTo(ADVANCED_POSITION);
                assertThat(harness.processor().isRunning()).isTrue();
            }
        }
    }

    private static long storedPosition(TokenStore tokenStore) {
        TrackingToken token = tokenStore.fetchToken(PROCESSOR, 0, null).orTimeout(30, TimeUnit.SECONDS).join();
        return token == null ? -1L : token.position().orElse(-1L);
    }

    /**
     * A strategy doing exactly what the seam is documented for: deciding a safe position by reconciling the positions
     * its participants report, through the framework's own helper for it.
     * <p>
     * The participant list holds one participant that has not reported a position, which is the state every
     * participant is in immediately after a claim. {@code lowerBound} renders that as
     * {@link TrackingToken#FIRST}, and offering it is legal by the contract: {@code persistProgress} accepts any
     * candidate and undertakes to ignore the ones that do not advance.
     */
    private static final class ReconcilingProgressStrategy implements SegmentProgressStrategy {

        private static final AtomicInteger PERSISTS = new AtomicInteger();

        private final SegmentProgressContext context;

        private ReconcilingProgressStrategy(SegmentProgressContext context) {
            this.context = context;
        }

        @Override
        public CompletableFuture<Void> onBatchCommit(ProcessingContext processingContext) {
            List<TrackingToken> participants = new ArrayList<>();
            participants.add(null);
            TrackingToken safe = TrackingTokenUtils.lowerBound(participants);
            PERSISTS.incrementAndGet();
            return context.persistProgress(safe, processingContext);
        }

        @Override
        public boolean hasPendingWork() {
            return false;
        }
    }

    /**
     * A strategy whose successive candidates descend, so the guard's engagement is observable.
     * <p>
     * The first candidate is already behind the position the store was holding, and every later one is behind the one
     * before it. What is stored is therefore a direct measurement of how many of them the guard judged.
     */
    private static final class DescendingProgressStrategy implements SegmentProgressStrategy {

        private static final AtomicInteger PERSISTS = new AtomicInteger();

        private final SegmentProgressContext context;
        private long next = FIRST_DESCENDING_CANDIDATE;

        private DescendingProgressStrategy(SegmentProgressContext context) {
            this.context = context;
        }

        @Override
        public CompletableFuture<Void> onBatchCommit(ProcessingContext processingContext) {
            TrackingToken candidate = new GlobalSequenceTrackingToken(next);
            next -= 100L;
            PERSISTS.incrementAndGet();
            return context.persistProgress(candidate, processingContext);
        }

        @Override
        public boolean hasPendingWork() {
            return false;
        }
    }

    /**
     * A strategy that advances the stored token once, then repeatedly offers a rewinding candidate.
     * <p>
     * Its first store is the one the framework would already have made for itself had the work package seeded its
     * stored-token field from the claim, so the cycles after it are the fixed engine's behaviour, reached without
     * touching the engine.
     */
    private static final class AdvanceThenRewindProgressStrategy implements SegmentProgressStrategy {

        private static final AtomicInteger PERSISTS = new AtomicInteger();

        private final SegmentProgressContext context;

        private AdvanceThenRewindProgressStrategy(SegmentProgressContext context) {
            this.context = context;
        }

        @Override
        public CompletableFuture<Void> onBatchCommit(ProcessingContext processingContext) {
            TrackingToken candidate = PERSISTS.getAndIncrement() == 0
                    ? new GlobalSequenceTrackingToken(ADVANCED_POSITION)
                    : TrackingToken.FIRST;
            return context.persistProgress(candidate, processingContext);
        }

        @Override
        public boolean hasPendingWork() {
            return false;
        }
    }

    /**
     * One running processor and the two executors it owns, closed together.
     */
    private record Harness(PooledStreamingEventProcessor processor,
                           ScheduledExecutorService coordinator,
                           ScheduledExecutorService worker) implements AutoCloseable {

        static Harness start(TokenStore tokenStore,
                             java.util.function.Function<SegmentProgressContext, SegmentProgressStrategy> strategy) {
            EventStore eventStore = new StorageEngineBackedEventStore(new InMemoryEventStorageEngine(),
                                                                     new SimpleEventBus(),
                                                                     event -> Set.of());
            ScheduledExecutorService coordinator = new ScheduledThreadPoolExecutor(1, daemon("probe-coordinator"));
            ScheduledExecutorService worker = new ScheduledThreadPoolExecutor(1, daemon("probe-worker"));
            EventHandlingComponent component = SimpleEventHandlingComponent.create("progress-probe-component");
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
