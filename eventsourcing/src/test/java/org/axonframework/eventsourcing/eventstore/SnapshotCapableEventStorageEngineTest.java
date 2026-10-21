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

package org.axonframework.eventsourcing.eventstore;

import org.axonframework.common.configuration.DecoratingComponent;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine.AppendTransaction;
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine;
import org.axonframework.eventsourcing.snapshot.api.Snapshot;
import org.axonframework.eventsourcing.snapshot.api.SnapshotCandidate;
import org.axonframework.eventsourcing.snapshot.api.SnapshotMatch;
import org.axonframework.eventsourcing.snapshot.inmemory.InMemorySnapshotStore;
import org.axonframework.eventsourcing.snapshot.store.SnapshotStore;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.VersionedType;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.axonframework.messaging.eventhandling.EventTestUtils.createEvent;

/**
 * Test for {@link SnapshotCapableEventStorageEngine}.
 *
 * @author John Hendrikx
 */
class SnapshotCapableEventStorageEngineTest {

    private static final Tag AGGREGATE_TAG = Tag.of("aggregateId", "order-1");
    private static final String AGGREGATE_ID = "order-1";
    private static final QualifiedName SNAPSHOT_TYPE = new QualifiedName("Order");
    private static final EventCriteria CRITERIA = EventCriteria.havingTags(AGGREGATE_TAG);

    private InMemoryEventStorageEngine delegate = new InMemoryEventStorageEngine();
    private InMemorySnapshotStore snapshotStore = new InMemorySnapshotStore();
    private SnapshotCapableEventStorageEngine engine = new SnapshotCapableEventStorageEngine(delegate, snapshotStore);

    @Nested
    class WhenSourcedWithoutSnapshotStrategy {
        @Test
        void delegatesToEngineDirectly() {
            appendToDelegate(createEvent(0));
            appendToDelegate(createEvent(1));
            appendToDelegate(createEvent(2));

            List<EventMessage> result = sourceMessages(engine.source(SourcingCondition.conditionFor(CRITERIA)));

            assertThat(result).extracting(EventMessage::payload).containsExactly(0, 1, 2);
        }
    }

    @Nested
    class WhenSourcedWithSnapshotStrategy {
        private SourcingCondition condition = SourcingCondition.conditionFor(
            new SourcingStrategy.Snapshot(List.of(candidate(SNAPSHOT_TYPE)), AGGREGATE_ID, null),
            CRITERIA
        );

        @Nested
        class AndSnapshotIsFound {
            @Test
            void prependsSnapshotMessageFollowedBySubsequentEvents() {
                appendToDelegate(createEvent(0));  // captured in snapshot
                appendToDelegate(createEvent(1));  // expected in stream after snapshot
                appendToDelegate(createEvent(2));  // expected in stream after snapshot
                Object snapshotState = "state-after-event-0";
                storeSnapshot(snapshotState, GlobalIndexPositions.of(1));

                List<EventMessage> result = sourceMessages(engine.source(condition));

                assertThat(result).hasSize(3);
                assertThat(result.get(0).payload())
                    .isInstanceOfSatisfying(Snapshot.class, s -> assertThat(s.payload()).isEqualTo(snapshotState));
                assertThat(result.subList(1, 3)).extracting(EventMessage::payload).containsExactly(1, 2);
            }
        }

        @Nested
        class AndNoSnapshotIsFound {
            @Test
            void fullReconstructionFromStart() {
                appendToDelegate(createEvent(0));
                appendToDelegate(createEvent(1));
                appendToDelegate(createEvent(2));

                List<EventMessage> result = sourceMessages(engine.source(condition));

                assertThat(result).extracting(EventMessage::payload).containsExactly(0, 1, 2);
            }
        }

        @Nested
        class AndSnapshotLoadingFails {
            @Test
            void fullReconstructionFromStart() {
                appendToDelegate(createEvent(0));
                appendToDelegate(createEvent(1));
                appendToDelegate(createEvent(2));
                engine = new SnapshotCapableEventStorageEngine(delegate, failingSnapshotStore());

                List<EventMessage> result = sourceMessages(engine.source(condition));

                assertThat(result).extracting(EventMessage::payload).containsExactly(0, 1, 2);
            }
        }
    }

    /*
     * A snapshot may still be stored under a name the entity was previously known by. The strategy carries those
     * names as further candidates, all handed to the store in a single load.
     */
    @Nested
    class WhenSourcedWithSeveralCandidates {

        private final QualifiedName formerType = new QualifiedName("LegacyOrder");
        private final QualifiedName evenEarlierType = new QualifiedName("AncientOrder");

        private final SourcingCondition condition = SourcingCondition.conditionFor(
            new SourcingStrategy.Snapshot(
                List.of(candidate(SNAPSHOT_TYPE), candidate(formerType), candidate(evenEarlierType)),
                AGGREGATE_ID,
                null
            ),
            CRITERIA
        );

        @Test
        void snapshotStoredUnderALaterCandidateIsUsed() {
            appendToDelegate(createEvent(0));
            appendToDelegate(createEvent(1));
            appendToDelegate(createEvent(2));
            storeSnapshot(formerType, "state-after-event-0", GlobalIndexPositions.of(1));

            List<EventMessage> result = sourceMessages(engine.source(condition));

            assertThat(result).hasSize(3);
            assertThat(result.get(0).payload())
                .isInstanceOfSatisfying(Snapshot.class, s -> assertThat(s.payload()).isEqualTo("state-after-event-0"));
            assertThat(result.subList(1, 3)).extracting(EventMessage::payload).containsExactly(1, 2);
        }

        @Test
        void theNameTheSnapshotWasFoundUnderIsCarriedOnTheMessage() {
            appendToDelegate(createEvent(0));
            appendToDelegate(createEvent(1));
            storeSnapshot(formerType, "state-after-event-0", GlobalIndexPositions.of(1));

            List<EventMessage> result = sourceMessages(engine.source(condition));

            // The stored identity, not the entity's current one, is what lets a transformation recognize it.
            assertThat(((SnapshotEventMessage) result.get(0)).snapshotType())
                .isEqualTo(VersionedType.of(formerType, "1.0"));
        }

        @Test
        void anEarlierCandidateTakesPrecedenceOverLaterOnes() {
            appendToDelegate(createEvent(0));
            appendToDelegate(createEvent(1));
            storeSnapshot(SNAPSHOT_TYPE, "current", GlobalIndexPositions.of(1));
            storeSnapshot(formerType, "former", GlobalIndexPositions.of(1));

            List<EventMessage> result = sourceMessages(engine.source(condition));

            assertThat(result.get(0).payload())
                .isInstanceOfSatisfying(Snapshot.class, s -> assertThat(s.payload()).isEqualTo("current"));
        }

        @Test
        void candidatesAreTriedInTheOrderGiven() {
            appendToDelegate(createEvent(0));
            appendToDelegate(createEvent(1));
            storeSnapshot(evenEarlierType, "ancient", GlobalIndexPositions.of(1));
            storeSnapshot(formerType, "former", GlobalIndexPositions.of(1));

            List<EventMessage> result = sourceMessages(engine.source(condition));

            assertThat(result.get(0).payload())
                .isInstanceOfSatisfying(Snapshot.class, s -> assertThat(s.payload()).isEqualTo("former"));
        }

        @Test
        void fullReconstructionWhenNoCandidateYieldsASnapshot() {
            appendToDelegate(createEvent(0));
            appendToDelegate(createEvent(1));
            appendToDelegate(createEvent(2));

            List<EventMessage> result = sourceMessages(engine.source(condition));

            assertThat(result).extracting(EventMessage::payload).containsExactly(0, 1, 2);
        }

        @Test
        void shouldReconstructFullyRatherThanUseASnapshotOfAnUnacceptableVersion() {
            // given a snapshot written by a newer version of the application than the one now sourcing it
            appendToDelegate(createEvent(0));
            appendToDelegate(createEvent(1));
            storeSnapshot(SNAPSHOT_TYPE, "written-by-newer-version", GlobalIndexPositions.of(1), "2.0");

            // when
            List<EventMessage> result = sourceMessages(engine.source(condition));

            // then
            assertThat(result).extracting(EventMessage::payload).containsExactly(0, 1);
        }
    }

    /*
     * An aggregate-based snapshot, such as one taken by Axon Framework 4, is described by an aggregate-based candidate,
     * which only a store that can hold such snapshots looks up. The engine hands it to the store together with the
     * regular candidates.
     */
    @Nested
    class WhenSourcedWithAnAggregateBasedCandidate {

        private final SnapshotCandidate aggregateBasedCandidate =
            new SnapshotCandidate(new QualifiedName("com.example.OrderAggregate"), Set.of("0.0.0"));

        private final SourcingCondition condition = SourcingCondition.conditionFor(
            new SourcingStrategy.Snapshot(
                List.of(candidate(SNAPSHOT_TYPE)), aggregateBasedCandidate, AGGREGATE_ID, null
            ),
            CRITERIA
        );

        @Test
        void shouldUseTheSnapshotAnAggregateBasedStoreFindsForTheAggregateBasedCandidate() {
            // given
            appendToDelegate(createEvent(0));
            appendToDelegate(createEvent(1));
            appendToDelegate(createEvent(2));
            engine = new SnapshotCapableEventStorageEngine(delegate, aggregateBasedSnapshotStore());

            // when
            List<EventMessage> result = sourceMessages(engine.source(condition));

            // then
            assertThat(result).hasSize(3);
            assertThat(((SnapshotEventMessage) result.get(0)).snapshotType())
                .isEqualTo(VersionedType.of(aggregateBasedCandidate.qualifiedName(), "0.0.0"));
            assertThat(result.get(0).payload())
                .isInstanceOfSatisfying(Snapshot.class, s -> assertThat(s.payload()).isEqualTo("af4-state"));
            assertThat(result.subList(1, 3)).extracting(EventMessage::payload).containsExactly(1, 2);
        }

        @Test
        void shouldReconstructFullyWhenTheStoreCannotHoldAggregateBasedSnapshots() {
            // given
            appendToDelegate(createEvent(0));
            appendToDelegate(createEvent(1));

            // when
            List<EventMessage> result = sourceMessages(engine.source(condition));

            // then
            assertThat(result).extracting(EventMessage::payload).containsExactly(0, 1);
        }

        /*
         * Answers only the aggregate-based candidate, with a snapshot covering the first event.
         */
        private SnapshotStore aggregateBasedSnapshotStore() {
            return new SnapshotStore() {

                @Override
                public CompletableFuture<Void> store(QualifiedName qn, Object id, Snapshot s,
                                                     ProcessingContext context) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public CompletableFuture<SnapshotMatch> load(List<SnapshotCandidate> candidates,
                                                             SnapshotCandidate aggregateBased,
                                                             Object id,
                                                             ProcessingContext context) {
                    if (aggregateBased == null || !id.equals(AGGREGATE_ID)) {
                        return CompletableFuture.completedFuture(null);
                    }

                    Snapshot snapshot = new Snapshot(
                        GlobalIndexPositions.of(1), "0.0.0", "af4-state", Instant.now(), Map.of()
                    );

                    return CompletableFuture.completedFuture(
                        new SnapshotMatch(aggregateBased.qualifiedName(), snapshot)
                    );
                }

                @Override
                public CompletableFuture<SnapshotMatch> load(List<SnapshotCandidate> candidates, Object id,
                                                             ProcessingContext context) {
                    return load(candidates, null, id, context);
                }

                // temporary: SnapshotStore keeps this deprecated method so Axoniq Framework keeps compiling
                @Override
                public CompletableFuture<Snapshot> load(QualifiedName qn, Object id, ProcessingContext context) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void describeTo(ComponentDescriptor descriptor) {
                    throw new UnsupportedOperationException();
                }
            };
        }
    }

    @Nested
    class WhenAppendingEvents {
        @Test
        void appendedEventsAreVisibleWhenSourced() {
            GenericTaggedEventMessage<EventMessage> tagged = new GenericTaggedEventMessage<>(createEvent(0), Set.of(AGGREGATE_TAG));
            AppendTransaction<?> tx = engine.appendEvents(AppendCondition.none(), null, List.of(tagged)).join();

            tx.commit().join();

            assertThat(sourceMessages(engine.source(SourcingCondition.conditionFor(CRITERIA))))
                .extracting(EventMessage::payload)
                .containsExactly(0);
        }
    }

    private void appendToDelegate(EventMessage event) {
        GenericTaggedEventMessage<EventMessage> tagged = new GenericTaggedEventMessage<>(event, Set.of(AGGREGATE_TAG));
        AppendTransaction<?> tx = delegate.appendEvents(AppendCondition.none(), null, List.of(tagged)).join();

        tx.commit().join();
    }

    private void storeSnapshot(Object payload, Position position) {
        storeSnapshot(SNAPSHOT_TYPE, payload, position);
    }

    private void storeSnapshot(QualifiedName qualifiedName, Object payload, Position position) {
        storeSnapshot(qualifiedName, payload, position, "1.0");
    }

    private void storeSnapshot(QualifiedName qualifiedName, Object payload, Position position, String version) {
        snapshotStore.store(
            qualifiedName,
            AGGREGATE_ID,
            new Snapshot(position, version, payload, Instant.now(), Map.of()),
            null
        ).join();
    }

    private static SnapshotCandidate candidate(QualifiedName qualifiedName) {
        return new SnapshotCandidate(qualifiedName, Set.of("1.0"));
    }

    private static List<EventMessage> sourceMessages(MessageStream<? extends EventMessage> stream) {
        return stream.filter(entry -> entry.getResource(ConsistencyMarker.RESOURCE_KEY) == null)
            .<List<EventMessage>>reduce(new ArrayList<>(), (list, entry) -> {
                list.add(entry.message());
                return list;
            })
            .join();
    }

    @Nested
    class Decorate {

        @Test
        void decoratesAnEngineThatIsNotTheSnapshotStore() {
            EventStorageEngine result = SnapshotCapableEventStorageEngine.decorate(delegate, snapshotStore);

            assertThat(result).isInstanceOf(SnapshotCapableEventStorageEngine.class);
        }

        // Such an engine resolves the snapshot within its own source call, so decorating costs it a round trip.
        @Test
        void returnsAnEngineThatIsTheSnapshotStoreAsIs() {
            SnapshotResolvingEngine snapshotResolvingEngine = new SnapshotResolvingEngine();

            EventStorageEngine result =
                    SnapshotCapableEventStorageEngine.decorate(snapshotResolvingEngine, snapshotResolvingEngine);

            assertThat(result).isSameAs(snapshotResolvingEngine);
        }

        // An engine that happens to implement SnapshotStore is not automatically the SnapshotStore a caller means:
        // a different, unrelated SnapshotStore instance always takes precedence over the engine's own support.
        @Test
        void decoratesAnEngineThatImplementsSnapshotStoreWhenGivenADifferentSnapshotStoreInstance() {
            SnapshotResolvingEngine snapshotResolvingEngine = new SnapshotResolvingEngine();

            EventStorageEngine result = SnapshotCapableEventStorageEngine.decorate(
                    snapshotResolvingEngine, new InMemorySnapshotStore()
            );

            assertThat(result).isInstanceOf(SnapshotCapableEventStorageEngine.class);
        }

        // config.getComponent(SnapshotStore.class) may return a decorated view of the engine's own SnapshotStore
        // (e.g. wrapped for tracing) rather than the engine itself, even though the engine backs that component.
        @Test
        void returnsAnEngineThatIsItsOwnSnapshotStoreAsIsEvenWhenTheSnapshotStoreIsDecorated() {
            SnapshotResolvingEngine snapshotResolvingEngine = new SnapshotResolvingEngine();
            SnapshotStore decoratedView = new DecoratingSnapshotStore(snapshotResolvingEngine);

            EventStorageEngine result =
                    SnapshotCapableEventStorageEngine.decorate(snapshotResolvingEngine, decoratedView);

            assertThat(result).isSameAs(snapshotResolvingEngine);
        }

        // A module registry receives the copied decorator definition and re-runs the enhancer that registers it, so the
        // same engine is composed twice. The second composition must not add a second snapshot load.
        @Test
        void returnsAnAlreadyDecoratedEngineAsIs() {
            EventStorageEngine once = SnapshotCapableEventStorageEngine.decorate(delegate, snapshotStore);

            EventStorageEngine twice = SnapshotCapableEventStorageEngine.decorate(once, new InMemorySnapshotStore());

            assertThat(twice).isSameAs(once);
        }

        @Test
        void rejectsANullEngine() {
            assertThatThrownBy(() -> SnapshotCapableEventStorageEngine.decorate(null, snapshotStore))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("The engine parameter cannot be null.");
        }

        @Test
        void rejectsANullSnapshotStore() {
            assertThatThrownBy(() -> SnapshotCapableEventStorageEngine.decorate(delegate, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("The snapshotStore parameter cannot be null.");
        }
    }

    // An engine that is its own snapshot store, as PostgresqlEventStorageEngine is.
    private static class SnapshotResolvingEngine extends InMemoryEventStorageEngine implements SnapshotStore {

        @Override
        public CompletableFuture<Void> store(QualifiedName qn, Object id, Snapshot s, ProcessingContext context) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<SnapshotMatch> load(List<SnapshotCandidate> candidates, Object id,
                                                         ProcessingContext context) {
            return CompletableFuture.completedFuture(null);
        }

        // temporary: SnapshotStore keeps this deprecated method so Axoniq Framework keeps compiling
        @Override
        public CompletableFuture<Snapshot> load(QualifiedName qn, Object id, ProcessingContext context) {
            return CompletableFuture.completedFuture(null);
        }
    }

    /** A decorator exposing its delegate via {@link DecoratingComponent}, mirroring {@code TracingSnapshotStore}. */
    private static class DecoratingSnapshotStore implements SnapshotStore, DecoratingComponent {

        private final SnapshotStore delegate;

        DecoratingSnapshotStore(SnapshotStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public CompletableFuture<Void> store(QualifiedName qn, Object id, Snapshot s, ProcessingContext context) {
            return delegate.store(qn, id, s, context);
        }

        @Override
        public CompletableFuture<SnapshotMatch> load(List<SnapshotCandidate> candidates, Object id,
                                                         ProcessingContext context) {
            return delegate.load(candidates, id, context);
        }

        // temporary: SnapshotStore keeps this deprecated method so Axoniq Framework keeps compiling
        @Override
        public CompletableFuture<Snapshot> load(QualifiedName qn, Object id, ProcessingContext context) {
            return delegate.load(qn, id, context);
        }

        @Override
        public Object decoratedDelegate() {
            return delegate;
        }

        @Override
        public void describeTo(ComponentDescriptor descriptor) {
            descriptor.describeWrapperOf(delegate);
        }
    }

    private static SnapshotStore failingSnapshotStore() {
        return new SnapshotStore() {

            @Override
            public CompletableFuture<Void> store(QualifiedName qn, Object id, Snapshot s,
                                                 ProcessingContext context) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CompletableFuture<SnapshotMatch> load(List<SnapshotCandidate> candidates, Object id,
                                                         ProcessingContext context) {
                return CompletableFuture.failedFuture(new RuntimeException("snapshot store failure"));
            }

            // temporary: SnapshotStore keeps this deprecated method so Axoniq Framework keeps compiling
            @Override
            public CompletableFuture<Snapshot> load(QualifiedName qn, Object id, ProcessingContext context) {
                return CompletableFuture.failedFuture(new RuntimeException("snapshot store failure"));
            }

            @Override
            public void describeTo(ComponentDescriptor descriptor) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
