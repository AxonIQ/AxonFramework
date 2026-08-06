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
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.unitofwork.SimpleUnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.queryhandling.GenericQueryMessage;
import org.axonframework.messaging.queryhandling.GenericSubscriptionQueryUpdateMessage;
import org.axonframework.messaging.queryhandling.QueryMessage;
import org.axonframework.messaging.queryhandling.SimpleQueryBus;
import org.axonframework.messaging.queryhandling.SubscriptionQueryUpdateMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Probes the match-count contract of {@link SimpleQueryBus#emitUpdateAndCount} and
 * {@link SimpleQueryBus#completeSubscriptionsAndCount}: the returned count against the updates
 * actually delivered to subscription-query streams, sequentially and under contention.
 */
class QueryEmitMatchCountProbeTest {

    private static final MessageType QUERY_TYPE = new MessageType("hunt.count.query");
    private static final MessageType UPDATE_TYPE = new MessageType("hunt.count.update");

    private SimpleQueryBus bus;

    @BeforeEach
    void setUp() {
        bus = new SimpleQueryBus(new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE));
    }

    private static SubscriptionQueryUpdateMessage update(Object payload) {
        return new GenericSubscriptionQueryUpdateMessage(UPDATE_TYPE, payload);
    }

    private static Predicate<QueryMessage> matches(QueryMessage query) {
        return q -> q.identifier().equals(query.identifier());
    }

    @Nested
    class SequentialCountVersusDelivery {

        @Test
        void countOnBufferOverflowStillReportsTheSubscriberTheUpdateNeverReached() {
            // given a subscription with an update buffer of exactly one, never consumed
            QueryMessage query = new GenericQueryMessage(QUERY_TYPE, "q");
            MessageStream<SubscriptionQueryUpdateMessage> updates = bus.subscribeToUpdates(query, 1);

            // when the first emit fills the buffer
            OptionalInt first = bus.emitUpdateAndCount(matches(query), () -> update("u1"), null)
                                   .orTimeout(1, TimeUnit.SECONDS).join();
            // and the second emit overflows it
            OptionalInt second = bus.emitUpdateAndCount(matches(query), () -> update("u2"), null)
                                    .orTimeout(1, TimeUnit.SECONDS).join();

            // then the stream carries u1 and an overflow error - u2 was never delivered
            var delivered = new ArrayList<Object>();
            while (updates.hasNextAvailable()) {
                updates.next().ifPresent(e -> delivered.add(e.message().payload()));
            }
            System.out.println("[probe] first count=" + first + " second count=" + second
                                       + " delivered=" + delivered
                                       + " completedExceptionally=" + updates.error().isPresent());
            assertThat(first).hasValue(1);
            assertThat(delivered).containsExactly("u1");
            assertThat(updates.error()).isPresent();
            // Expected gap (finding F-29): the Javadoc contract is "the number of subscription
            // queries the update was emitted to", yet u2 reached nobody - the counted subscriber
            // was killed by the overflow instead of receiving the update. This pins today's
            // behavior; it flips red when the count starts reporting actual deliveries.
            assertThat(second).hasValue(1);
        }

        @Test
        void deferredCountMatchesWhatIsDeliveredAtCommit() {
            // given one matching subscription present at emit time
            QueryMessage early = new GenericQueryMessage(QUERY_TYPE, "early");
            QueryMessage late = new GenericQueryMessage(QUERY_TYPE, "late");
            MessageStream<SubscriptionQueryUpdateMessage> earlyUpdates = bus.subscribeToUpdates(early, 16);

            Predicate<QueryMessage> matchesEither =
                    q -> q.identifier().equals(early.identifier()) || q.identifier().equals(late.identifier());

            UnitOfWork uow = new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE).create();
            List<OptionalInt> countedAtEmit = new ArrayList<>();
            List<MessageStream<SubscriptionQueryUpdateMessage>> lateStream = new ArrayList<>();
            uow.onInvocation(context -> {
                countedAtEmit.add(bus.emitUpdateAndCount(matchesEither, () -> update("u"), context)
                                     .orTimeout(1, TimeUnit.SECONDS).join());
                // before commit: the counted subscriber leaves, an uncounted one arrives
                earlyUpdates.close();
                lateStream.add(bus.subscribeToUpdates(late, 16));
                return CompletableFuture.completedFuture(null);
            });
            uow.execute().orTimeout(2, TimeUnit.SECONDS).join();

            boolean earlyGotIt = earlyUpdates.hasNextAvailable();
            boolean lateGotIt = lateStream.get(0).hasNextAvailable();
            System.out.println("[probe] deferred count=" + countedAtEmit.get(0)
                                       + " earlyDelivered=" + earlyGotIt + " lateDelivered=" + lateGotIt);
            // Measurement, not a violation: the count is a call-time snapshot (pinned as intended
            // by the framework's own SimpleQueryBusTest), so the counted subscriber got nothing
            // and the delivered subscriber was never counted. The number can coincide with the
            // delivery count, as here, while describing a different subscriber entirely.
            assertThat(earlyGotIt).isFalse();
            assertThat(lateGotIt).isTrue();
            assertThat(countedAtEmit.get(0)).hasValue(1);
        }
    }

    @Nested
    class ConcurrentEmitAndComplete {

        @Test
        void noUpdateIsObservedAfterTheStreamReportedCompleted() throws Exception {
            // Hammer: an emitter races completeSubscriptions; the consumer keeps polling after
            // observing completion. An entry surfacing after isCompleted() breaks the
            // MessageStream contract ("next() will not return further entries").
            int rounds = 2_000;
            AtomicInteger postCompletionDeliveries = new AtomicInteger();
            for (int i = 0; i < rounds; i++) {
                QueryMessage query = new GenericQueryMessage(QUERY_TYPE, "q" + i);
                MessageStream<SubscriptionQueryUpdateMessage> updates = bus.subscribeToUpdates(query, 64);
                CountDownLatch go = new CountDownLatch(1);
                Thread emitter = new Thread(() -> {
                    awaitQuietly(go);
                    for (int j = 0; j < 8; j++) {
                        bus.emitUpdateAndCount(matches(query), () -> update("u"), null).join();
                    }
                });
                Thread completer = new Thread(() -> {
                    awaitQuietly(go);
                    bus.completeSubscriptionsAndCount(matches(query), null).join();
                });
                emitter.start();
                completer.start();
                go.countDown();
                emitter.join(2_000);
                completer.join(2_000);

                // drain to completion
                boolean sawCompleted = false;
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
                while (System.nanoTime() < deadline) {
                    if (updates.next().isPresent()) {
                        if (sawCompleted) {
                            postCompletionDeliveries.incrementAndGet();
                        }
                    } else if (updates.isCompleted()) {
                        if (sawCompleted) {
                            break; // polled once more after completion and got nothing
                        }
                        sawCompleted = true;
                    }
                }
            }
            System.out.println("[probe] post-completion deliveries over " + rounds + " rounds: "
                                       + postCompletionDeliveries.get());
            assertThat(postCompletionDeliveries.get()).isZero();
        }

        @Test
        void countedUpdatesMatchDeliveredUpdatesUnderSubscriptionChurn() throws Exception {
            // Hammer: emitters count while subscribers churn. Tally the counted total against the
            // delivered total; a persistent gap on a quiesced bus means the count and the delivery
            // used different snapshots of the subscription map.
            int emitters = 2;
            int emitsPerThread = 3_000;
            QueryMessage stable = new GenericQueryMessage(QUERY_TYPE, "stable");
            MessageStream<SubscriptionQueryUpdateMessage> stableUpdates = bus.subscribeToUpdates(stable, emitters * emitsPerThread + 16);

            AtomicBoolean churn = new AtomicBoolean(true);
            AtomicInteger counted = new AtomicInteger();
            Thread churner = new Thread(() -> {
                int k = 0;
                while (churn.get()) {
                    QueryMessage q = new GenericQueryMessage(QUERY_TYPE, "churn" + k++);
                    MessageStream<SubscriptionQueryUpdateMessage> s = bus.subscribeToUpdates(q, 4);
                    s.close();
                }
            });
            List<Thread> emitterThreads = new ArrayList<>();
            Predicate<QueryMessage> all = q -> true;
            for (int t = 0; t < emitters; t++) {
                emitterThreads.add(new Thread(() -> {
                    for (int j = 0; j < emitsPerThread; j++) {
                        OptionalInt c = bus.emitUpdateAndCount(all, () -> update("u"), null).join();
                        counted.addAndGet(c.orElse(0));
                    }
                }));
            }
            churner.start();
            emitterThreads.forEach(Thread::start);
            for (Thread t : emitterThreads) {
                t.join(30_000);
            }
            churn.set(false);
            churner.join(5_000);

            int deliveredToStable = 0;
            while (stableUpdates.hasNextAvailable()) {
                stableUpdates.next();
                deliveredToStable++;
            }
            int totalEmits = emitters * emitsPerThread;
            System.out.println("[probe] emits=" + totalEmits
                                       + " countedTotal=" + counted.get()
                                       + " deliveredToStableSubscriber=" + deliveredToStable
                                       + " (stable subscriber alone accounts for " + totalEmits + " if nothing was lost)");
            // the stable subscriber matched every emit and its buffer never overflowed, so every
            // emit must have reached it, and every count must have included it
            assertThat(deliveredToStable).isEqualTo(totalEmits);
            assertThat(counted.get()).isGreaterThanOrEqualTo(totalEmits);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
