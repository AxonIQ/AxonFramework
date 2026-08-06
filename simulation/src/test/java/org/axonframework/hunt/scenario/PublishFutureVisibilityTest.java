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

import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.eventsourcing.eventstore.AppendCondition;
import org.axonframework.eventsourcing.eventstore.ConsistencyMarker;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.SourcingCondition;
import org.axonframework.eventsourcing.eventstore.StorageEngineBackedEventStore;
import org.axonframework.eventsourcing.eventstore.TaggedEventMessage;
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.SimpleEventBus;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventstreaming.StreamingCondition;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Probes the read-your-write contract of {@code EventSink.publish} on the store-backed event store: the Javadoc
 * states that successful completion of the returned future means the events were published, so at the moment the
 * future completes the events must be visible to a reader of the same store.
 * <p>
 * The first arm gates the storage engine's {@code afterCommit} future and asserts the publish future does not
 * complete before it. That is precisely the regression fixed by making the commit chain compose rather than map the
 * nested {@code afterCommit} future ({@code StorageEngineBackedEventStore.publish}, PR #4838): under the old
 * {@code thenApply} the publish future completed while {@code afterCommit} was still pending. This arm fails on the
 * unfixed code and passes on the fixed code.
 * <p>
 * The second arm runs many concurrent publishers against the in-heap engine, each asserting immediately at
 * future-completion that its own event is readable, while a reader streams the store throughout. Claims C4 and C37
 * in {@code docs/testing-plans/axon-hunt.md}.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
class PublishFutureVisibilityTest {

    private static EventMessage event(String identifier) {
        return new GenericEventMessage(identifier,
                                       new MessageType("hunt.published", "0.0.1"),
                                       Map.of("id", identifier),
                                       Map.of(),
                                       Instant.EPOCH);
    }

    private static List<String> readableIds(EventStorageEngine engine) {
        List<String> ids = new ArrayList<>();
        MessageStream<EventMessage> stream =
                engine.source(SourcingCondition.conditionFor(EventCriteria.havingAnyTag()), null);
        try {
            for (var entry = stream.next(); entry.isPresent(); entry = stream.next()) {
                if (entry.get().getResource(ConsistencyMarker.RESOURCE_KEY) == null) {
                    ids.add(entry.get().message().identifier());
                }
            }
        } finally {
            stream.close();
        }
        return ids;
    }

    @Nested
    class ThePublishFutureAwaitsAfterCommit {

        @Test
        void publishFutureDoesNotCompleteWhileAfterCommitIsStillPending() {
            // given an engine whose afterCommit future only completes when the test releases it
            CompletableFuture<Void> afterCommitGate = new CompletableFuture<>();
            InMemoryEventStorageEngine delegate = new InMemoryEventStorageEngine();
            var store = new StorageEngineBackedEventStore(new GatedAfterCommitEngine(delegate, afterCommitGate),
                                                          new SimpleEventBus(),
                                                          e -> Set.of());

            // when publishing without a processing context, the path that commits inline
            CompletableFuture<Void> published = store.publish(null, List.of(event("gated-1")));

            // then the future must still be pending: afterCommit has not completed. The pre-fix
            // thenApply chain completed it here, with the nested afterCommit future dropped.
            assertThat(published).isNotDone();

            // and releasing afterCommit completes the publish, with the event readable
            afterCommitGate.complete(null);
            published.orTimeout(30, TimeUnit.SECONDS).join();
            assertThat(readableIds(delegate)).containsExactly("gated-1");
        }
    }

    @Nested
    class ReadYourWriteUnderContention {

        @Test
        void everyEventIsReadableTheMomentItsPublishFutureCompletes() throws Exception {
            // given one in-heap store, eight concurrent publishers and one concurrent full-stream reader
            InMemoryEventStorageEngine engine = new InMemoryEventStorageEngine();
            var store = new StorageEngineBackedEventStore(engine, new SimpleEventBus(), e -> Set.of());
            int writers = 8;
            int eventsPerWriter = 25;
            ExecutorService pool = Executors.newFixedThreadPool(writers + 1);
            CountDownLatch startTogether = new CountDownLatch(1);
            CompletableFuture<Void> stopReader = new CompletableFuture<>();
            try {
                // a reader hammering the store while the writers publish, so visibility is probed under read load
                Future<Long> reader = pool.submit(() -> {
                    long polls = 0;
                    startTogether.await();
                    while (!stopReader.isDone()) {
                        readableIds(engine);
                        polls++;
                    }
                    return polls;
                });

                // when each publisher checks its own event at the exact moment its publish future completes
                List<Future<List<String>>> invisible = new ArrayList<>();
                for (int w = 0; w < writers; w++) {
                    int writer = w;
                    invisible.add(pool.submit(() -> {
                        List<String> missing = new ArrayList<>();
                        startTogether.await();
                        for (int i = 0; i < eventsPerWriter; i++) {
                            String id = "w" + writer + "-" + i;
                            store.publish(null, List.of(event(id)))
                                 .orTimeout(30, TimeUnit.SECONDS)
                                 .join();
                            if (!readableIds(engine).contains(id)) {
                                missing.add(id);
                            }
                        }
                        return missing;
                    }));
                }
                startTogether.countDown();

                // then no writer ever completed a publish whose event a same-thread read could not see
                List<String> allMissing = new ArrayList<>();
                for (Future<List<String>> f : invisible) {
                    allMissing.addAll(f.get(60, TimeUnit.SECONDS));
                }
                stopReader.complete(null);
                long polls = reader.get(60, TimeUnit.SECONDS);
                System.out.println("read-your-write: writers=" + writers + " events=" + (writers * eventsPerWriter)
                                           + " reader polls=" + polls + " missing=" + allMissing.size());
                assertThat(allMissing).isEmpty();
                assertThat(readableIds(engine)).hasSize(writers * eventsPerWriter);
            } finally {
                stopReader.complete(null);
                pool.shutdownNow();
            }
        }
    }

    /**
     * Delegates everything to a real engine, but makes every append transaction's {@code afterCommit} future wait on
     * the given gate before completing. This is the asynchronous {@code afterCommit} that persistent engines have and
     * the in-heap engine does not, which is what makes the dropped-nested-future regression observable in-memory.
     */
    private static final class GatedAfterCommitEngine implements EventStorageEngine {

        private final EventStorageEngine delegate;
        private final CompletableFuture<Void> gate;

        private GatedAfterCommitEngine(EventStorageEngine delegate, CompletableFuture<Void> gate) {
            this.delegate = delegate;
            this.gate = gate;
        }

        @Override
        public CompletableFuture<AppendTransaction<?>> appendEvents(AppendCondition condition,
                                                                    @Nullable ProcessingContext context,
                                                                    List<TaggedEventMessage<?>> events) {
            return delegate.appendEvents(condition, context, events).thenApply(this::gated);
        }

        @SuppressWarnings("unchecked")
        private AppendTransaction<?> gated(AppendTransaction<?> transaction) {
            AppendTransaction<Object> tx = (AppendTransaction<Object>) transaction;
            return new AppendTransaction<>() {
                @Override
                public CompletableFuture<Object> commit() {
                    return tx.commit();
                }

                @Override
                public void rollback() {
                    tx.rollback();
                }

                @Override
                public CompletableFuture<ConsistencyMarker> afterCommit(Object commitResult) {
                    return gate.thenCompose(v -> tx.afterCommit(commitResult));
                }
            };
        }

        @Override
        public MessageStream<EventMessage> source(SourcingCondition condition, @Nullable ProcessingContext context) {
            return delegate.source(condition, context);
        }

        @Override
        public MessageStream<EventMessage> stream(StreamingCondition condition) {
            return delegate.stream(condition);
        }

        @Override
        public CompletableFuture<TrackingToken> firstToken() {
            return delegate.firstToken();
        }

        @Override
        public CompletableFuture<TrackingToken> latestToken() {
            return delegate.latestToken();
        }

        @Override
        public CompletableFuture<TrackingToken> tokenAt(Instant at) {
            return delegate.tokenAt(at);
        }

        @Override
        public void describeTo(ComponentDescriptor descriptor) {
            descriptor.describeProperty("delegate", delegate);
        }
    }
}
