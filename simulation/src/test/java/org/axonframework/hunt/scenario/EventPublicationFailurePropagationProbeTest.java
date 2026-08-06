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

import org.axonframework.messaging.core.EmptyApplicationContext;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.unitofwork.SimpleUnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.SimpleEventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Probes failure propagation of {@link SimpleEventBus#publish}: a failing subscriber must surface
 * through the returned {@link CompletableFuture}, and one subscriber's failure must not prevent the
 * other subscribers from being notified - on both the direct (null-context) path and the
 * UnitOfWork path.
 */
class EventPublicationFailurePropagationProbeTest {

    private static final MessageType EVENT_TYPE = new MessageType("hunt.propagation.event");

    private SimpleEventBus bus;

    @BeforeEach
    void setUp() {
        bus = new SimpleEventBus();
    }

    private static EventMessage event(Object payload) {
        return new GenericEventMessage(EVENT_TYPE, payload);
    }

    @Nested
    class FailedFutureSubscriber {

        @Test
        void failedSubscriberFutureFailsThePublishFutureAndOtherSubscribersStillRun() {
            AtomicInteger recorded = new AtomicInteger();
            bus.subscribe((events, ctx) -> CompletableFuture.failedFuture(new IllegalStateException("boom")));
            bus.subscribe((events, ctx) -> {
                recorded.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            });

            CompletableFuture<Void> publish = bus.publish(null, List.of(event("e1")));

            assertThat(publish).isCompletedExceptionally();
            assertThat(recorded.get()).isEqualTo(1);
        }
    }

    @Nested
    class SynchronouslyThrowingSubscriber {

        @Test
        void directPathLeaksSynchronousSubscriberFailureAndSkipsRemainingSubscribers() {
            // Expected gap (finding F-28): a subscriber that throws synchronously escapes
            // publish(null, ...) as a thrown exception rather than a failed future, and the
            // subscribers registered after it are never notified. This pins today's behavior;
            // it flips red when notifySubscribers guards each subscriber invocation.
            AtomicInteger recorded = new AtomicInteger();
            bus.subscribe((events, ctx) -> {
                throw new IllegalStateException("sync boom");
            });
            bus.subscribe((events, ctx) -> {
                recorded.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            });

            RuntimeException leaked = null;
            try {
                bus.publish(null, List.of(event("e1")));
            } catch (RuntimeException e) {
                leaked = e;
            }
            System.out.println("[probe] direct path: leaked=" + leaked
                                       + " second subscriber invocations=" + recorded.get());
            assertThat(leaked).isInstanceOf(IllegalStateException.class).hasMessage("sync boom");
            assertThat(recorded.get()).isZero();
        }

        @Test
        void unitOfWorkPathFailsTheExecutionButSkipsRemainingSubscribers() {
            // Expected gap (finding F-28): on the UnitOfWork path the failure does reach the
            // execute() future (the prepare-commit phase converts the throw), but the subscribers
            // registered after the throwing one are still never notified. Pins today's behavior;
            // flips red when notifySubscribers guards each subscriber invocation.
            AtomicInteger recorded = new AtomicInteger();
            bus.subscribe((events, ctx) -> {
                throw new IllegalStateException("sync boom");
            });
            bus.subscribe((events, ctx) -> {
                recorded.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            });

            UnitOfWork uow = new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE).create();
            uow.onInvocation(ctx -> bus.publish(ctx, List.of(event("e1"))));
            CompletableFuture<Void> execute = uow.execute();

            boolean exceptional;
            try {
                execute.orTimeout(2, TimeUnit.SECONDS).join();
                exceptional = false;
            } catch (RuntimeException e) {
                exceptional = true;
            }
            System.out.println("[probe] uow path: execute exceptional=" + exceptional
                                       + " second subscriber invocations=" + recorded.get());
            assertThat(exceptional).isTrue();
            assertThat(recorded.get()).isZero();
        }
    }

    @Nested
    class ConcurrentPublishersWithIntermittentFailure {

        @Test
        void everyFailedPublishCompletesExceptionallyAndEverySuccessfulOneAfterAllSubscribersRan() throws Exception {
            // one subscriber fails every third batch (by payload marker), the other records; count
            // both sides of every publish future
            int publishers = 4;
            int publishesEach = 500;
            CopyOnWriteArrayList<Object> recorded = new CopyOnWriteArrayList<>();
            bus.subscribe((events, ctx) -> {
                int marker = (Integer) events.get(0).payload();
                return marker % 3 == 0
                        ? CompletableFuture.failedFuture(new IllegalStateException("boom " + marker))
                        : CompletableFuture.completedFuture(null);
            });
            bus.subscribe((events, ctx) -> {
                recorded.add(events.get(0).payload());
                return CompletableFuture.completedFuture(null);
            });

            AtomicInteger failedFutures = new AtomicInteger();
            AtomicInteger successFutures = new AtomicInteger();
            AtomicInteger successBeforeAllRan = new AtomicInteger();
            Thread[] threads = new Thread[publishers];
            for (int p = 0; p < publishers; p++) {
                int base = p * publishesEach;
                threads[p] = new Thread(() -> {
                    for (int i = 0; i < publishesEach; i++) {
                        int marker = base + i;
                        CompletableFuture<Void> f = bus.publish(null, List.of(event(marker)));
                        try {
                            f.orTimeout(5, TimeUnit.SECONDS).join();
                            successFutures.incrementAndGet();
                            // a successful publish must complete only after ALL subscribers ran:
                            // the recorder must already have seen this marker
                            if (!recorded.contains(marker)) {
                                successBeforeAllRan.incrementAndGet();
                            }
                        } catch (RuntimeException e) {
                            failedFutures.incrementAndGet();
                            // even a failed publish must have notified the recorder (allOf runs all)
                            if (!recorded.contains(marker)) {
                                successBeforeAllRan.incrementAndGet();
                            }
                        }
                    }
                });
            }
            for (Thread t : threads) {
                t.start();
            }
            for (Thread t : threads) {
                t.join(60_000);
            }

            int total = publishers * publishesEach;
            int expectedFailures = countMultiplesOfThree(total);
            System.out.println("[probe] publishes=" + total
                                       + " failedFutures=" + failedFutures.get()
                                       + " successFutures=" + successFutures.get()
                                       + " expectedFailures=" + expectedFailures
                                       + " recorderInvocations=" + recorded.size()
                                       + " completionsBeforeAllSubscribersRan=" + successBeforeAllRan.get());
            assertThat(failedFutures.get()).isEqualTo(expectedFailures);
            assertThat(successFutures.get()).isEqualTo(total - expectedFailures);
            assertThat(recorded.size()).isEqualTo(total);
            assertThat(successBeforeAllRan.get()).isZero();
        }

        private int countMultiplesOfThree(int totalExclusive) {
            int count = 0;
            for (int i = 0; i < totalExclusive; i++) {
                if (i % 3 == 0) {
                    count++;
                }
            }
            return count;
        }
    }
}
