package org.axonframework.eventsourcing.eventstore;

import org.axonframework.eventsourcing.eventstore.EventStorageEngine.AppendTransaction;
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine;
import org.axonframework.eventsourcing.snapshot.api.Snapshot;
import org.axonframework.eventsourcing.snapshot.inmemory.InMemorySnapshotStore;
import org.axonframework.eventsourcing.snapshot.store.SnapshotStore;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.axonframework.messaging.eventhandling.EventTestUtils.createEvent;

/**
 * Test for {@link SnapshotCapableEventStorageEngine}.
 *
 * @author John Hendrikx
 */
class SnapshotCapableEventStorageEngineTest {

    private static final Tag AGGREGATE_TAG = Tag.of("aggregateId", "order-1");
    private static final String AGGREGATE_ID = "order-1";
    private static final MessageType SNAPSHOT_TYPE = new MessageType("Order");
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
            new SourcingStrategy.Snapshot(SNAPSHOT_TYPE, AGGREGATE_ID),
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
        snapshotStore.store(
            SNAPSHOT_TYPE.qualifiedName(),
            AGGREGATE_ID,
            new Snapshot(position, "1.0", payload, Instant.now(), Map.of())
        ).join();
    }

    private static List<EventMessage> sourceMessages(MessageStream<? extends EventMessage> stream) {
        return stream.filter(entry -> entry.getResource(ConsistencyMarker.RESOURCE_KEY) == null)
            .<List<EventMessage>>reduce(new ArrayList<>(), (list, entry) -> {
                list.add(entry.message());
                return list;
            })
            .join();
    }

    private static SnapshotStore failingSnapshotStore() {
        return new SnapshotStore() {
            @Override
            public CompletableFuture<Void> store(QualifiedName qn, Object id, Snapshot s) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CompletableFuture<Snapshot> load(QualifiedName qn, Object id) {
                return CompletableFuture.failedFuture(new RuntimeException("snapshot store failure"));
            }
        };
    }
}
