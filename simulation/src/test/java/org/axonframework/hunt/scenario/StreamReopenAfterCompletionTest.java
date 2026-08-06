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

import org.axonframework.messaging.core.ApplicationContext;
import org.axonframework.messaging.core.Context;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.SimpleEntry;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.SimpleUnitOfWorkFactory;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.SimpleEventHandlingComponent;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorConfiguration;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessor;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorConfiguration;
import org.axonframework.messaging.eventhandling.processing.streaming.token.GlobalSequenceTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.inmemory.InMemoryTokenStore;
import org.axonframework.messaging.eventstreaming.StreamableEventSource;
import org.axonframework.messaging.eventstreaming.StreamingCondition;
import org.axonframework.messaging.eventstreaming.Tag;
import org.awaitility.Awaitility;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How the pooled processor's coordinator behaves when an event source ends its streams, cleanly or with a failure.
 * <p>
 * The coordinator treats a completed stream as a reason to claim segments and open a replacement. A stream a source
 * <em>failed</em> is reported instead of replaced, so the coordinator's own error handling paces the retry by its
 * error back-off. A stream a source completed <em>cleanly</em> is replaced immediately, and the
 * {@link MessageStream#setCallback(Runnable) availability callback} contract fires the callback on an already
 * completed stream, which schedules the next coordination run with no delay at all. This probe measures both paths
 * against a source the test controls completely, with no mocking framework in between:
 * <ul>
 *   <li>the reopen <b>rate</b> when every stream the source hands out is born completed, against the reopen rate when
 *       every stream is born failed;</li>
 *   <li>the reopen <b>boundary</b>: a source that ends the stream after every single event must cost no event a skip
 *       and no event a redelivery, however many reopens that takes;</li>
 *   <li>a source that <b>alternates</b> failing and completing, where the back-off must land on every failed run and
 *       no event may be lost across the alternation.</li>
 * </ul>
 * The probe's stream honours the framework's own stream conventions: a stream opened at token position {@code p}
 * delivers the event stored at index {@code p} first, every delivered event carries a token of its index plus one,
 * and the callback is invoked at registration time when the stream is already completed, exactly as
 * {@code AbstractMessageStream.setCallback} does.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
class StreamReopenAfterCompletionTest {

    private static final String PROCESSOR = "reopen-probe";
    private static final Duration MEASURE_WINDOW = Duration.ofSeconds(2);

    /**
     * The probe resolves no components: it never resets a processor, which is the one thing that needs a converter.
     */
    private static final ApplicationContext NO_COMPONENTS = new ApplicationContext() {
        @Override
        public <C> C component(Class<C> type, @Nullable String name) {
            throw new UnsupportedOperationException(
                    "The reopen probe provides no component of type [" + type.getName() + "].");
        }
    };

    @Nested
    class ASourceWhoseStreamsAreBornCompleted {

        @Test
        void isReopenedWithoutTheErrorBackOff() {
            // given a source that only ever hands out cleanly completed streams
            ProbeEventSource source = new ProbeEventSource(open -> ProbeEventSource.Plan.completedInstantly());
            List<String> delivered = new CopyOnWriteArrayList<>();
            try (Harness harness = Harness.start(source, delivered)) {
                Awaitility.await("the segment to be claimed")
                          .atMost(Duration.ofSeconds(30))
                          .untilAsserted(() -> assertThat(harness.processor().processingStatus()).containsKey(0));

                // when the coordinator runs against it for a fixed window
                long before = source.opens();
                sleep(MEASURE_WINDOW);
                long opened = source.opens() - before;
                System.out.println("CLEAN-COMPLETION REOPENS in " + MEASURE_WINDOW.toMillis() + "ms: " + opened);

                // then a clean completion is a reason to reopen on the very next coordination run, so the window
                // holds far more opens than the failed-stream path's error back-off would ever allow
                assertThat(opened)
                        .as("streams opened in %dms against an instantly-completing source", MEASURE_WINDOW.toMillis())
                        .isGreaterThan(10);
                assertThat(harness.processor().isError()).isFalse();
            }
        }
    }

    @Nested
    class ASourceWhoseStreamsAreBornFailed {

        @Test
        void isReopenedOnlyAtTheErrorBackOffPace() {
            // given a source that only ever hands out streams already completed with an error
            ProbeEventSource source =
                    new ProbeEventSource(open -> ProbeEventSource.Plan.failedInstantly(
                            new IllegalStateException("stream " + open + " failed at birth")));
            List<String> delivered = new CopyOnWriteArrayList<>();
            try (Harness harness = Harness.start(source, delivered)) {
                Awaitility.await("the first stream to be opened")
                          .atMost(Duration.ofSeconds(30))
                          .untilAsserted(() -> assertThat(source.opens()).isPositive());

                // when the coordinator runs against it for the same window as the clean-completion case
                long before = source.opens();
                sleep(MEASURE_WINDOW);
                long opened = source.opens() - before;
                System.out.println("FAILED-STREAM REOPENS in " + MEASURE_WINDOW.toMillis() + "ms: " + opened);

                // then the coordinator's error back-off paces every retry: at least a second passes between
                // attempts, so the window holds only a handful of opens
                assertThat(opened)
                        .as("streams opened in %dms against an instantly-failing source", MEASURE_WINDOW.toMillis())
                        .isLessThan(10);
            }
        }
    }

    @Nested
    class ASourceThatEndsTheStreamAfterEveryEvent {

        @Test
        void costsNoEventASkipAndNoEventARedelivery() {
            // given twenty-five events and a source that cleanly completes every stream after one delivery
            ProbeEventSource source =
                    new ProbeEventSource(open -> ProbeEventSource.Plan.completesAfterDelivering(1));
            List<String> expected = source.publish(25);
            List<String> delivered = new CopyOnWriteArrayList<>();
            try (Harness harness = Harness.start(source, delivered)) {

                // when the processor works through the stream, reopening after every single event
                Awaitility.await("all events to be delivered across the reopens")
                          .atMost(Duration.ofSeconds(30))
                          .pollInterval(Duration.ofMillis(25))
                          .untilAsserted(() -> assertThat(delivered).hasSizeGreaterThanOrEqualTo(expected.size()));
                System.out.println("BOUNDARY: delivered " + delivered.size() + " events over "
                                           + source.opens() + " stream opens: " + delivered);

                // then every event arrived exactly once and in order: each reopen resumed at the position right
                // after the event the completed stream delivered last, so a per-reopen off-by-one would surface
                // here as one lost or one repeated event per reopen
                assertThat(delivered).containsExactlyElementsOf(expected);
                // and it took at least one reopen per event, so the boundary was crossed as often as it could be
                assertThat(source.opens()).isGreaterThanOrEqualTo(expected.size());
                assertThat(harness.processor().isError()).isFalse();
            }
        }
    }

    @Nested
    class ASourceThatAlternatesFailingAndCompleting {

        @Test
        void losesNoEventAndBacksOffOnEveryFailedRun() {
            // given twelve events and a source whose opens alternate: even opens deliver two events and complete
            // cleanly, odd opens hand out a stream born failed
            ProbeEventSource source = new ProbeEventSource(open -> open % 2 == 0
                    ? ProbeEventSource.Plan.completesAfterDelivering(2)
                    : ProbeEventSource.Plan.failedInstantly(
                    new IllegalStateException("stream " + open + " failed at birth")));
            List<String> expected = source.publish(12);
            List<String> delivered = new CopyOnWriteArrayList<>();
            try (Harness harness = Harness.start(source, delivered)) {

                // when the processor works through the flapping source
                Awaitility.await("all events to be delivered across the flapping")
                          .atMost(Duration.ofSeconds(60))
                          .pollInterval(Duration.ofMillis(25))
                          .untilAsserted(() -> assertThat(delivered).containsAll(expected));
                List<Long> openInstants = source.openInstantsMillis();
                System.out.println("FLAP: delivered " + delivered.size() + " events over " + source.opens()
                                           + " stream opens; open instants (ms since start): " + openInstants);

                // then nothing was lost, and the order of first deliveries is the publication order
                assertThat(firstOccurrences(delivered)).containsExactlyElementsOf(expected);

                // and every failed open was followed by a pause of at least the coordinator's error back-off:
                // the scheduled retry delay is a lower bound, so a shorter gap proves the back-off did not land
                for (int open = 1; open + 1 < openInstants.size(); open += 2) {
                    long gap = openInstants.get(open + 1) - openInstants.get(open);
                    assertThat(gap)
                            .as("pause after failed open [%d] before open [%d]", open, open + 1)
                            .isGreaterThanOrEqualTo(900L);
                }
            }
        }

        private static List<String> firstOccurrences(List<String> delivered) {
            return delivered.stream().distinct().toList();
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The measurement window was interrupted.", e);
        }
    }

    /**
     * One running processor and the two executors it owns, closed together.
     */
    private record Harness(PooledStreamingEventProcessor processor,
                           ScheduledExecutorService coordinator,
                           ScheduledExecutorService worker) implements AutoCloseable {

        static Harness start(StreamableEventSource source, List<String> delivered) {
            ScheduledExecutorService coordinator = new ScheduledThreadPoolExecutor(1, daemon("reopen-coordinator"));
            ScheduledExecutorService worker = new ScheduledThreadPoolExecutor(1, daemon("reopen-worker"));
            SimpleEventHandlingComponent component = SimpleEventHandlingComponent.create("reopen-probe-component");
            component.subscribe(new QualifiedName(String.class), (event, context) -> {
                delivered.add((String) event.payload());
                return MessageStream.empty();
            });
            PooledStreamingEventProcessorConfiguration configuration =
                    new PooledStreamingEventProcessorConfiguration(new EventProcessorConfiguration(PROCESSOR, null))
                            .eventSource(source)
                            .tokenStore(new InMemoryTokenStore())
                            .unitOfWorkFactory(new SimpleUnitOfWorkFactory(NO_COMPONENTS))
                            .coordinatorExecutor(coordinator)
                            .workerExecutor(worker)
                            .initialSegmentCount(1)
                            // Far beyond every window measured here, so a reopen inside a window can only be
                            // explained by the coordinator reacting to the stream's own state.
                            .tokenClaimInterval(30_000)
                            .claimExtensionThreshold(5_000)
                            .batchSize(1);
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

        private static ThreadFactory daemon(String prefix) {
            AtomicInteger counter = new AtomicInteger();
            return runnable -> {
                Thread thread = new Thread(runnable, prefix + "-" + counter.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            };
        }
    }

    /**
     * A {@link StreamableEventSource} the test scripts one stream at a time, counting every open.
     * <p>
     * The plan given at construction decides, per open, whether the stream is born completed, born failed, or
     * delivers a number of events and then completes cleanly. Streams follow the conventions of the framework's own
     * in-memory engine: a stream opened at token position {@code p} reads the event stored at index
     * {@code max(0, p)} first, a delivered event carries a token of its index plus one, and the availability
     * callback fires at registration when the stream is already completed and again the moment the stream completes.
     */
    private static final class ProbeEventSource implements StreamableEventSource {

        private final ConcurrentSkipListMap<Long, EventMessage> storage = new ConcurrentSkipListMap<>();
        private final AtomicLong nextIndex = new AtomicLong();
        private final AtomicInteger opens = new AtomicInteger();
        private final List<Long> openInstantsNanos = new CopyOnWriteArrayList<>();
        private final long startNanos = System.nanoTime();
        private final IntFunction<Plan> plans;

        private ProbeEventSource(IntFunction<Plan> plans) {
            this.plans = plans;
        }

        /**
         * Publishes {@code count} events with payloads {@code evt-0 .. evt-(count-1)} and returns those payloads.
         *
         * @param count the number of events to publish
         * @return the published payloads, in publication order
         */
        List<String> publish(int count) {
            List<String> payloads = new CopyOnWriteArrayList<>();
            for (int i = 0; i < count; i++) {
                String payload = "evt-" + i;
                storage.put(nextIndex.getAndIncrement(),
                            new GenericEventMessage(UUID.randomUUID().toString(),
                                                    new MessageType(String.class),
                                                    payload,
                                                    Map.of(),
                                                    Instant.EPOCH));
                payloads.add(payload);
            }
            return payloads;
        }

        long opens() {
            return opens.get();
        }

        /**
         * Returns when each stream was opened, in milliseconds since this source was constructed.
         *
         * @return the open instants, one per open, in open order
         */
        List<Long> openInstantsMillis() {
            return openInstantsNanos.stream().map(nanos -> (nanos - startNanos) / 1_000_000L).toList();
        }

        @Override
        public MessageStream<EventMessage> open(StreamingCondition condition, @Nullable ProcessingContext context) {
            int open = opens.getAndIncrement();
            openInstantsNanos.add(System.nanoTime());
            return new ProbeStream(condition, plans.apply(open));
        }

        @Override
        public CompletableFuture<TrackingToken> firstToken(@Nullable ProcessingContext context) {
            return CompletableFuture.completedFuture(new GlobalSequenceTrackingToken(-1));
        }

        @Override
        public CompletableFuture<TrackingToken> latestToken(@Nullable ProcessingContext context) {
            return CompletableFuture.completedFuture(new GlobalSequenceTrackingToken(nextIndex.get()));
        }

        @Override
        public CompletableFuture<TrackingToken> tokenAt(Instant at, @Nullable ProcessingContext context) {
            return firstToken(context);
        }

        /**
         * What one opened stream does: how many events it delivers before completing, and the error it is born
         * with, if any.
         *
         * @param deliveriesBeforeCompletion how many events the stream delivers before completing cleanly
         * @param bornWith                   the error the stream is born completed with, or {@code null}
         */
        private record Plan(int deliveriesBeforeCompletion, @Nullable Throwable bornWith) {

            static Plan completedInstantly() {
                return new Plan(0, null);
            }

            static Plan failedInstantly(Throwable cause) {
                return new Plan(0, cause);
            }

            static Plan completesAfterDelivering(int deliveries) {
                return new Plan(deliveries, null);
            }

            boolean bornTerminal() {
                return deliveriesBeforeCompletion == 0;
            }
        }

        /**
         * The stream a plan scripts, honouring the callback-on-completion contract.
         */
        private final class ProbeStream implements MessageStream<EventMessage> {

            private final Plan plan;
            private final AtomicLong position;
            private final AtomicInteger deliveries = new AtomicInteger();
            private final AtomicReference<Runnable> callback = new AtomicReference<>(() -> {
            });
            private volatile boolean completed;
            private volatile @Nullable Throwable error;

            private ProbeStream(StreamingCondition condition, Plan plan) {
                this.plan = plan;
                long start = condition.position().position().orElse(-1);
                this.position = new AtomicLong(Math.max(0, start));
                if (plan.bornTerminal()) {
                    this.completed = true;
                    this.error = plan.bornWith();
                }
            }

            @Override
            public Optional<Entry<EventMessage>> next() {
                if (completed) {
                    return Optional.empty();
                }
                long index = position.get();
                EventMessage event = storage.get(index);
                if (event == null) {
                    return Optional.empty();
                }
                position.incrementAndGet();
                Optional<Entry<EventMessage>> entry = Optional.of(new SimpleEntry<>(event, contextAt(index)));
                if (deliveries.incrementAndGet() >= plan.deliveriesBeforeCompletion()) {
                    completed = true;
                    // The MessageStream contract fires the availability callback on completion.
                    callback.get().run();
                }
                return entry;
            }

            @Override
            public Optional<Entry<EventMessage>> peek() {
                if (completed) {
                    return Optional.empty();
                }
                long index = position.get();
                EventMessage event = storage.get(index);
                if (event == null) {
                    return Optional.empty();
                }
                return Optional.of(new SimpleEntry<>(event, contextAt(index)));
            }

            private Context contextAt(long index) {
                Context entryContext = Context.empty();
                entryContext = TrackingToken.addToContext(entryContext, new GlobalSequenceTrackingToken(index + 1));
                return Tag.addToContext(entryContext, Set.of());
            }

            @Override
            public void setCallback(Runnable callback) {
                this.callback.set(callback);
                // The MessageStream contract fires the callback at registration when entries may be available or
                // the stream has already completed, exactly as AbstractMessageStream.setCallback does.
                if (completed || hasNextAvailable()) {
                    callback.run();
                }
            }

            @Override
            public Optional<Throwable> error() {
                return Optional.ofNullable(error);
            }

            @Override
            public boolean isCompleted() {
                return completed;
            }

            @Override
            public boolean hasNextAvailable() {
                return !completed && storage.containsKey(position.get());
            }

            @Override
            public void close() {
                completed = true;
            }
        }
    }
}
