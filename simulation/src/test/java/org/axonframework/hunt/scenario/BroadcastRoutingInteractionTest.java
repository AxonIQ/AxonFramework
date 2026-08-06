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

import org.axonframework.eventsourcing.eventstore.AppendCondition;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.GenericTaggedEventMessage;
import org.axonframework.eventsourcing.eventstore.StorageEngineBackedEventStore;
import org.axonframework.eventsourcing.eventstore.TaggedEventMessage;
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine;
import org.axonframework.messaging.core.ApplicationContext;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.sequencing.SequencingPolicy;
import org.axonframework.messaging.core.unitofwork.SimpleUnitOfWorkFactory;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.SimpleEventBus;
import org.axonframework.messaging.eventhandling.SimpleEventHandlingComponent;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorConfiguration;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessor;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorConfiguration;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.inmemory.InMemoryTokenStore;
import org.awaitility.Awaitility;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How the broadcast sequence identifier and the per-component segment routing behave when they meet in one pooled
 * processor.
 * <p>
 * Two independent decisions are involved for every event. Admission: a work package admits an event to its segment
 * when any supporting component's sequence identifier matches the segment, and skips segment matching entirely when
 * any of those identifiers is {@link SequencingPolicy#BROADCAST}, so a broadcast admits the event to every segment.
 * Handling: each component is then re-checked individually, and a component only handles the event in the segment its
 * own identifier hashes into. The gap the combination could open is an event admitted to a segment on the strength of
 * one component's identifier and then handled there by nobody, with the token advancing past it regardless -- which is
 * the shape of {@code NoCommittedEventGoesUndelivered}.
 * <p>
 * These are end-to-end probes through a real {@link PooledStreamingEventProcessor}: the admission filter and the
 * per-component routing both run, against an in-memory store, and the oracle is the handled-count per component per
 * event read after every segment's position has passed the whole stream.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
class BroadcastRoutingInteractionTest {

    private static final QualifiedName ROUTED_EVENT = new QualifiedName("hunt.RoutedEvent");
    private static final QualifiedName ALPHA_EVENT = new QualifiedName("hunt.AlphaEvent");
    private static final QualifiedName BETA_EVENT = new QualifiedName("hunt.BetaEvent");
    private static final Duration BEAT = Duration.ofMillis(150);
    private static final Duration BUDGET = Duration.ofSeconds(60);

    private static final ApplicationContext NO_COMPONENTS = new ApplicationContext() {
        @Override
        public <C> C component(Class<C> type, @Nullable String name) {
            throw new UnsupportedOperationException(
                    "The routing probe provides no component of type [" + type.getName() + "].");
        }
    };

    @Nested
    class ABroadcastComponentBesideARoutedOne {

        @Test
        void broadcastHandlesOncePerSegmentWhileTheRoutedComponentHandlesOnce() {
            // given one processor over four segments, holding a broadcasting component and a normally routed one
            Counts counts = new Counts();
            EventHandlingComponent broadcaster = SimpleEventHandlingComponent
                    .create("broadcaster", (event, context) -> Optional.of(SequencingPolicy.BROADCAST))
                    .subscribe(ROUTED_EVENT, (event, context) -> {
                        counts.record("broadcaster", event);
                        return MessageStream.empty().cast();
                    });
            EventHandlingComponent routed = SimpleEventHandlingComponent
                    .create("routed", (event, context) -> Optional.of(String.valueOf(event.payload())))
                    .subscribe(ROUTED_EVENT, (event, context) -> {
                        counts.record("routed", event);
                        return MessageStream.empty().cast();
                    });

            try (Harness harness = Harness.start("broadcast-beside-routed", 4,
                                                 List.of(broadcaster, routed))) {
                // when three events with distinct routing keys are committed and every segment passes them
                harness.append(ROUTED_EVENT, "key-a");
                harness.append(ROUTED_EVENT, "key-b");
                harness.append(ROUTED_EVENT, "key-c");
                harness.awaitAllSegmentsPast(4, 2);

                // then the broadcasting component handled each event once per segment, and the routed one exactly once
                System.out.println("BROADCAST x ROUTING segments=4 " + counts);
                for (String key : List.of("key-a", "key-b", "key-c")) {
                    assertThat(counts.of("broadcaster", key))
                            .as("broadcast component handles [%s] once per segment", key)
                            .isEqualTo(4);
                    assertThat(counts.of("routed", key))
                            .as("routed component handles [%s] exactly once", key)
                            .isEqualTo(1);
                }
            }
        }
    }

    @Nested
    class AnEventAdmittedOnAnotherComponentsIdentifier {

        @Test
        void isStillHandledExactlyOncePerSupportingComponent() {
            // given three components with disjoint policies: alpha and gamma share the alpha event but disagree on its
            // sequence key, so admission regularly puts the event on a segment where only one of them matches; beta
            // funnels every beta event into one lane while gamma spreads them, so the union admission and the
            // per-component routing decide differently on most segments
            Counts counts = new Counts();
            EventHandlingComponent alpha = SimpleEventHandlingComponent
                    .create("alpha", (event, context) -> Optional.of("alpha-" + event.payload()))
                    .subscribe(ALPHA_EVENT, (event, context) -> {
                        counts.record("alpha", event);
                        return MessageStream.empty().cast();
                    });
            EventHandlingComponent beta = SimpleEventHandlingComponent
                    .create("beta", (event, context) -> Optional.of("beta-lane"))
                    .subscribe(BETA_EVENT, (event, context) -> {
                        counts.record("beta", event);
                        return MessageStream.empty().cast();
                    });
            EventHandlingComponent gamma = SimpleEventHandlingComponent
                    .create("gamma", (event, context) -> Optional.of(String.valueOf(event.payload())))
                    .subscribe(ALPHA_EVENT, (event, context) -> {
                        counts.record("gamma", event);
                        return MessageStream.empty().cast();
                    })
                    .subscribe(BETA_EVENT, (event, context) -> {
                        counts.record("gamma", event);
                        return MessageStream.empty().cast();
                    });

            try (Harness harness = Harness.start("admitted-but-dropped", 4, List.of(alpha, beta, gamma))) {
                // when a spread of events is committed and every segment's position passes the whole stream
                for (int i = 0; i < 6; i++) {
                    harness.append(ALPHA_EVENT, "a" + i);
                    harness.append(BETA_EVENT, "b" + i);
                }
                harness.awaitAllSegmentsPast(4, 11);

                // then no event was dropped: every supporting component handled every one of its events exactly once,
                // even though the segments' tokens have advanced past all of them
                System.out.println("ADMITTED VS HANDLED segments=4 " + counts);
                for (int i = 0; i < 6; i++) {
                    assertThat(counts.of("alpha", "a" + i))
                            .as("alpha handles [a%s] exactly once across all segments", i).isEqualTo(1);
                    assertThat(counts.of("gamma", "a" + i))
                            .as("gamma handles [a%s] exactly once across all segments", i).isEqualTo(1);
                    assertThat(counts.of("beta", "b" + i))
                            .as("beta handles [b%s] exactly once across all segments", i).isEqualTo(1);
                    assertThat(counts.of("gamma", "b" + i))
                            .as("gamma handles [b%s] exactly once across all segments", i).isEqualTo(1);
                }
            }
        }
    }

    @Nested
    class ABroadcastAcrossASplitAndAMerge {

        @Test
        void isHandledOncePerLiveSegmentOnEachSideOfTheTransition() {
            // given a broadcasting component on a processor that starts with two segments
            Counts counts = new Counts();
            EventHandlingComponent broadcaster = SimpleEventHandlingComponent
                    .create("broadcaster", (event, context) -> Optional.of(SequencingPolicy.BROADCAST))
                    .subscribe(ROUTED_EVENT, (event, context) -> {
                        counts.record("broadcaster", event);
                        return MessageStream.empty().cast();
                    });

            try (Harness harness = Harness.start("broadcast-across-split", 2, List.of(broadcaster))) {
                // when one event lands while two segments are live
                harness.append(ROUTED_EVENT, "before-split");
                harness.awaitAllSegmentsPast(2, 0);
                assertThat(counts.of("broadcaster", "before-split"))
                        .as("a broadcast over two segments is handled twice").isEqualTo(2);

                // and the processor splits a segment, claims the third, and another event lands
                Boolean split = harness.processor().splitSegment(0).orTimeout(30, TimeUnit.SECONDS).join();
                assertThat(split).as("the split instruction is carried out").isTrue();
                harness.awaitClaimedSegments(3);
                harness.append(ROUTED_EVENT, "after-split");
                harness.awaitAllSegmentsPast(3, 1);

                // then the new event is handled once per NEW segment, and the old one is not handled again
                System.out.println("BROADCAST SPLIT " + counts);
                assertThat(counts.of("broadcaster", "after-split"))
                        .as("a broadcast after the split is handled once per new segment count").isEqualTo(3);
                assertThat(counts.of("broadcaster", "before-split"))
                        .as("the pre-split broadcast is not re-handled by the segment the split created").isEqualTo(2);

                // and when the split is merged back and a third event lands
                Boolean merged = harness.processor().mergeSegment(0).orTimeout(30, TimeUnit.SECONDS).join();
                assertThat(merged).as("the merge instruction is carried out").isTrue();
                harness.awaitClaimedSegments(2);
                harness.append(ROUTED_EVENT, "after-merge");
                harness.awaitAllSegmentsPast(2, 2);

                // then the new event is handled once per merged segment count, and nothing older is re-handled
                System.out.println("BROADCAST MERGE " + counts);
                assertThat(counts.of("broadcaster", "after-merge"))
                        .as("a broadcast after the merge is handled once per merged segment count").isEqualTo(2);
                assertThat(counts.of("broadcaster", "after-split"))
                        .as("the pre-merge broadcast is not re-handled after the merge").isEqualTo(3);
                assertThat(counts.of("broadcaster", "before-split")).isEqualTo(2);
            }
        }
    }

    /**
     * Handled-count per component per routing key, recorded from inside the handlers.
     */
    private static final class Counts {

        private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();

        void record(String component, EventMessage event) {
            counts.computeIfAbsent(component + "/" + event.payload(), key -> new AtomicInteger())
                  .incrementAndGet();
        }

        int of(String component, String key) {
            AtomicInteger count = counts.get(component + "/" + key);
            return count == null ? 0 : count.get();
        }

        @Override
        public String toString() {
            return counts.entrySet().stream()
                         .sorted(Map.Entry.comparingByKey())
                         .map(entry -> entry.getKey() + "=" + entry.getValue())
                         .reduce((a, b) -> a + " " + b)
                         .orElse("(nothing handled)");
        }
    }

    /**
     * One running pooled processor over an in-memory store, with direct append access to the storage engine.
     */
    private record Harness(PooledStreamingEventProcessor processor,
                           EventStorageEngine engine,
                           ScheduledExecutorService coordinator,
                           ScheduledExecutorService worker) implements AutoCloseable {

        static Harness start(String name, int segments, List<EventHandlingComponent> components) {
            InMemoryEventStorageEngine engine = new InMemoryEventStorageEngine();
            StorageEngineBackedEventStore eventStore =
                    new StorageEngineBackedEventStore(engine, new SimpleEventBus(), event -> Set.of());
            ScheduledExecutorService coordinator = new ScheduledThreadPoolExecutor(1, daemon(name + "-coordinator"));
            ScheduledExecutorService worker = new ScheduledThreadPoolExecutor(4, daemon(name + "-worker"));
            PooledStreamingEventProcessorConfiguration configuration =
                    new PooledStreamingEventProcessorConfiguration(new EventProcessorConfiguration(name, null))
                            .eventSource(eventStore)
                            .tokenStore(new InMemoryTokenStore())
                            .unitOfWorkFactory(new SimpleUnitOfWorkFactory(NO_COMPONENTS))
                            .coordinatorExecutor(coordinator)
                            .workerExecutor(worker)
                            .initialSegmentCount(segments)
                            .initialToken(source -> source.firstToken(null))
                            .maxClaimedSegments(8)
                            .tokenClaimInterval(BEAT.toMillis())
                            .claimExtensionThreshold(BEAT.toMillis())
                            .batchSize(1);
            PooledStreamingEventProcessor processor =
                    new PooledStreamingEventProcessor(name, components, configuration);
            processor.start().orTimeout(30, TimeUnit.SECONDS).join();
            return new Harness(processor, engine, coordinator, worker);
        }

        void append(QualifiedName eventName, String payload) {
            EventMessage event = new GenericEventMessage(new MessageType(eventName), payload);
            List<TaggedEventMessage<?>> batch = List.of(new GenericTaggedEventMessage<>(event, Set.of()));
            EventStorageEngine.AppendTransaction<?> transaction =
                    engine.appendEvents(AppendCondition.none(), null, batch)
                          .orTimeout(30, TimeUnit.SECONDS)
                          .join();
            transaction.commit().orTimeout(30, TimeUnit.SECONDS).join();
        }

        /**
         * Waits until exactly {@code segments} segments are claimed and every one of them reports a position of at
         * least {@code position}. A segment past the position has durably decided about every event at or before it,
         * so a handled-count read after this is a read of a settled fact.
         */
        void awaitAllSegmentsPast(int segments, long position) {
            Awaitility.await("all " + segments + " segments past position " + position)
                      .atMost(BUDGET)
                      .pollInterval(Duration.ofMillis(25))
                      .untilAsserted(() -> {
                          var statuses = processor.processingStatus();
                          assertThat(statuses).hasSize(segments);
                          assertThat(statuses.values())
                                  .allSatisfy(status -> assertThat(status.getCurrentPosition().orElse(-1))
                                          .isGreaterThanOrEqualTo(position));
                      });
        }

        void awaitClaimedSegments(int segments) {
            Awaitility.await(segments + " claimed segments")
                      .atMost(BUDGET)
                      .pollInterval(Duration.ofMillis(25))
                      .untilAsserted(() -> assertThat(processor.processingStatus()).hasSize(segments));
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
}
