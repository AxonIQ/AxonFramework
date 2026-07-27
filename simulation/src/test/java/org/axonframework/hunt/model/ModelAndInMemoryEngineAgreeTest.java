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

package org.axonframework.hunt.model;

import org.axonframework.eventsourcing.eventstore.AppendCondition;
import org.axonframework.eventsourcing.eventstore.AppendEventsTransactionRejectedException;
import org.axonframework.eventsourcing.eventstore.ConsistencyMarker;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.GenericTaggedEventMessage;
import org.axonframework.eventsourcing.eventstore.GlobalIndexConsistencyMarker;
import org.axonframework.eventsourcing.eventstore.GlobalIndexPosition;
import org.axonframework.eventsourcing.eventstore.SourcingCondition;
import org.axonframework.eventsourcing.eventstore.TaggedEventMessage;
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives seeded random operation sequences against both the reference model and the real in-memory storage engine and
 * asserts they reach the same verdicts and the same visible state.
 * <p>
 * This is the check that keeps the reference model honest. A property checker only catches the ways of being wrong
 * that somebody enumerated; comparing the model against the engine catches the rules the model got wrong, and,
 * equally, the rules the engine got wrong.
 * <p>
 * The comparison is deliberately sequential. Operations are applied one at a time to both sides, so a disagreement is
 * a disagreement about the protocol rather than about interleaving. What a concurrent reader may observe while a
 * batch is being committed is a separate property, out of this test's reach by construction.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
class ModelAndInMemoryEngineAgreeTest {

    private static final List<String> TAG_KEYS = List.of("student", "course");
    private static final List<String> TAG_VALUES = List.of("v-1", "v-2", "v-3");
    private static final List<String> TYPES = List.of("Enrolled", "Registered", "Cancelled");
    private static final int OPS_PER_RUN = 60;

    @Nested
    class Differential {

        @ParameterizedTest
        @ValueSource(longs = {1L, 2L, 3L, 5L, 8L, 13L, 21L, 34L, 55L, 89L, 144L, 233L})
        void modelAndEngineReachTheSameVerdictsAndTheSameVisibleState(long seed) {
            // given
            Random random = new Random(seed);
            DcbStoreModel model = new DcbStoreModel();
            EventStorageEngine engine = new InMemoryEventStorageEngine();
            List<Long> capturedMarkers = new ArrayList<>(List.of(DcbStoreModel.ORIGIN));
            int eventCounter = 0;
            int accepted = 0;
            int rejected = 0;

            for (int op = 0; op < OPS_PER_RUN; op++) {
                Set<ModelCriterion> boundary = randomBoundary(random);

                // when a sourcing read is compared
                ModelSourcingCondition sourcing = ModelSourcingCondition.conditionFor(boundary);
                DcbStoreModel.SourceResult modelSourced = model.source(sourcing);
                Sourced engineSourced = sourceFromEngine(engine, boundary);

                // then both must see the same events and report the same marker
                assertThat(engineSourced.eventIds())
                        .as("sourced events at op %d of seed %d", op, seed)
                        .isEqualTo(modelSourced.eventIds());
                assertThat(engineSourced.marker())
                        .as("sourcing marker at op %d of seed %d", op, seed)
                        .isEqualTo(modelSourced.marker());
                capturedMarkers.add(modelSourced.marker());

                // when an append is compared
                ModelAppendCondition condition = randomCondition(random, boundary, capturedMarkers);
                List<ModelEvent> batch = new ArrayList<>();
                int batchSize = 1 + random.nextInt(3);
                for (int event = 0; event < batchSize; event++) {
                    batch.add(randomEvent(random, "e-" + eventCounter++));
                }
                DcbStoreModel.AppendVerdict modelVerdict = model.append(condition, batch);
                boolean engineAccepted = appendToEngine(engine, condition, batch);

                // then both must accept or both must reject
                assertThat(engineAccepted)
                        .as("append verdict at op %d of seed %d, condition %s", op, seed, condition)
                        .isEqualTo(modelVerdict.accepted());

                // and the whole store must look the same afterwards
                assertThat(sourceFromEngine(engine, Set.of()).eventIds())
                        .as("store contents after op %d of seed %d", op, seed)
                        .isEqualTo(model.events().stream().map(ModelEvent::id).toList());

                accepted += modelVerdict.accepted() ? 1 : 0;
                rejected += modelVerdict.accepted() ? 0 : 1;
            }

            // and the run must have exercised both verdicts, or the agreement means nothing
            assertThat(accepted).as("accepted appends in seed %d", seed).isPositive();
            assertThat(rejected).as("rejected appends in seed %d", seed).isPositive();
        }
    }

    @Nested
    class KnownEdges {

        @Test
        void bothRejectAStaleMarkerAppendAfterACompetingAppendLanded() {
            // given
            DcbStoreModel model = new DcbStoreModel();
            EventStorageEngine engine = new InMemoryEventStorageEngine();
            Set<ModelCriterion> boundary = Set.of(ModelCriterion.havingTags(ModelTag.of("student", "v-1")));
            ModelEvent first = new ModelEvent("e-0", "Enrolled", Set.of(ModelTag.of("student", "v-1")));
            ModelEvent competitor = new ModelEvent("e-1", "Enrolled", Set.of(ModelTag.of("student", "v-1")));
            ModelEvent late = new ModelEvent("e-2", "Enrolled", Set.of(ModelTag.of("student", "v-1")));

            model.append(ModelAppendCondition.none(), List.of(first));
            appendToEngine(engine, ModelAppendCondition.none(), List.of(first));
            long marker = model.source(ModelSourcingCondition.conditionFor(boundary)).marker();
            model.append(ModelAppendCondition.none(), List.of(competitor));
            appendToEngine(engine, ModelAppendCondition.none(), List.of(competitor));

            // when appending against the now-stale marker
            ModelAppendCondition stale = new ModelAppendCondition(marker, boundary);

            // then
            assertThat(model.append(stale, List.of(late)).accepted()).isFalse();
            assertThat(appendToEngine(engine, stale, List.of(late))).isFalse();
        }

        @Test
        void bothLeaveTheStoreUntouchedAfterARejectedMultiEventBatch() {
            // given
            DcbStoreModel model = new DcbStoreModel();
            EventStorageEngine engine = new InMemoryEventStorageEngine();
            Set<ModelCriterion> boundary = Set.of(ModelCriterion.havingTags(ModelTag.of("student", "v-1")));
            ModelEvent blocker = new ModelEvent("e-0", "Enrolled", Set.of(ModelTag.of("student", "v-1")));
            model.append(ModelAppendCondition.none(), List.of(blocker));
            appendToEngine(engine, ModelAppendCondition.none(), List.of(blocker));

            // when a three-event batch is rejected
            ModelAppendCondition condition = ModelAppendCondition.withCriteria(boundary);
            List<ModelEvent> batch = List.of(
                    new ModelEvent("e-1", "Enrolled", Set.of(ModelTag.of("student", "v-1"))),
                    new ModelEvent("e-2", "Enrolled", Set.of(ModelTag.of("student", "v-1"))),
                    new ModelEvent("e-3", "Enrolled", Set.of(ModelTag.of("student", "v-1"))));
            model.append(condition, batch);
            appendToEngine(engine, condition, batch);

            // then
            assertThat(sourceFromEngine(engine, Set.of()).eventIds()).containsExactly("e-0");
            assertThat(model.events()).extracting(ModelEvent::id).containsExactly("e-0");
        }
    }

    private static Set<ModelCriterion> randomBoundary(Random random) {
        int criterionCount = 1 + random.nextInt(2);
        Set<ModelCriterion> boundary = new LinkedHashSet<>();
        for (int criterion = 0; criterion < criterionCount; criterion++) {
            Set<ModelTag> tags = new LinkedHashSet<>();
            int tagCount = 1 + random.nextInt(2);
            for (int tag = 0; tag < tagCount; tag++) {
                tags.add(ModelTag.of(TAG_KEYS.get(random.nextInt(TAG_KEYS.size())),
                                     TAG_VALUES.get(random.nextInt(TAG_VALUES.size()))));
            }
            Set<String> types = random.nextInt(3) == 0
                    ? Set.of(TYPES.get(random.nextInt(TYPES.size())))
                    : Set.of();
            boundary.add(new ModelCriterion(types, tags));
        }
        return Set.copyOf(boundary);
    }

    private static ModelAppendCondition randomCondition(Random random,
                                                        Set<ModelCriterion> boundary,
                                                        List<Long> capturedMarkers) {
        int choice = random.nextInt(10);
        if (choice < 1) {
            // Control arm: no condition at all, which must never be rejected.
            return ModelAppendCondition.none();
        }
        if (choice < 3) {
            // Anchored at the origin, so every matching event in the store is a conflict.
            return ModelAppendCondition.withCriteria(boundary);
        }
        if (choice < 5) {
            // A deliberately stale marker, reproducing a concurrent writer that read too early.
            return new ModelAppendCondition(capturedMarkers.get(random.nextInt(capturedMarkers.size())), boundary);
        }
        // The common case: anchored at the marker the sourcing just reported.
        return new ModelAppendCondition(capturedMarkers.getLast(), boundary);
    }

    private static ModelEvent randomEvent(Random random, String id) {
        Set<ModelTag> tags = new LinkedHashSet<>();
        int tagCount = 1 + random.nextInt(2);
        for (int tag = 0; tag < tagCount; tag++) {
            tags.add(ModelTag.of(TAG_KEYS.get(random.nextInt(TAG_KEYS.size())),
                                 TAG_VALUES.get(random.nextInt(TAG_VALUES.size()))));
        }
        return new ModelEvent(id, TYPES.get(random.nextInt(TYPES.size())), tags);
    }

    private static boolean appendToEngine(EventStorageEngine engine,
                                          ModelAppendCondition condition,
                                          List<ModelEvent> batch) {
        List<TaggedEventMessage<?>> events = batch.stream()
                                                  .map(ModelAndInMemoryEngineAgreeTest::toTaggedEvent)
                                                  .toList();
        try {
            EventStorageEngine.AppendTransaction<?> transaction =
                    engine.appendEvents(toAppendCondition(condition), null, events)
                          .orTimeout(10, TimeUnit.SECONDS)
                          .join();
            transaction.commit().orTimeout(10, TimeUnit.SECONDS).join();
            return true;
        } catch (CompletionException e) {
            if (e.getCause() instanceof AppendEventsTransactionRejectedException) {
                return false;
            }
            throw e;
        }
    }

    private static AppendCondition toAppendCondition(ModelAppendCondition condition) {
        if (condition.marker() == DcbStoreModel.INFINITY && condition.criteria().isEmpty()) {
            return AppendCondition.none();
        }
        AppendCondition withCriteria = AppendCondition.withCriteria(toCriteria(condition.criteria()));
        if (condition.marker() == DcbStoreModel.ORIGIN) {
            return withCriteria;
        }
        if (condition.marker() == DcbStoreModel.INFINITY) {
            return withCriteria.withMarker(ConsistencyMarker.INFINITY);
        }
        return withCriteria.withMarker(new GlobalIndexConsistencyMarker(condition.marker()));
    }

    private static EventCriteria toCriteria(Set<ModelCriterion> criteria) {
        if (criteria.isEmpty()) {
            return EventCriteria.havingAnyTag();
        }
        List<EventCriteria> parts = criteria.stream().map(ModelAndInMemoryEngineAgreeTest::toCriterion).toList();
        return parts.size() == 1 ? parts.getFirst() : EventCriteria.either(parts);
    }

    private static EventCriteria toCriterion(ModelCriterion criterion) {
        Set<Tag> tags = criterion.tags().stream()
                                 .map(tag -> new Tag(tag.key(), tag.value()))
                                 .collect(java.util.stream.Collectors.toUnmodifiableSet());
        var tagged = EventCriteria.havingTags(tags);
        if (criterion.types().isEmpty()) {
            return tagged;
        }
        return tagged.andBeingOneOfTypes(criterion.types().stream()
                                                  .map(QualifiedName::new)
                                                  .collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }

    private static TaggedEventMessage<?> toTaggedEvent(ModelEvent event) {
        Set<Tag> tags = event.tags().stream()
                             .map(tag -> new Tag(tag.key(), tag.value()))
                             .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new GenericTaggedEventMessage<>(
                new GenericEventMessage(event.id(), new MessageType(event.type()), "payload", Map.of(), Instant.now()),
                tags);
    }

    private static Sourced sourceFromEngine(EventStorageEngine engine, Set<ModelCriterion> criteria) {
        SourcingCondition condition = SourcingCondition.conditionFor(toCriteria(criteria));
        List<String> eventIds = new ArrayList<>();
        long marker = DcbStoreModel.ORIGIN;
        MessageStream<EventMessage> stream = engine.source(condition, null);
        try {
            for (var entry = stream.next(); entry.isPresent(); entry = stream.next()) {
                ConsistencyMarker consistencyMarker = entry.get().getResource(ConsistencyMarker.RESOURCE_KEY);
                if (consistencyMarker != null) {
                    marker = GlobalIndexPosition.toIndex(consistencyMarker.position());
                } else {
                    eventIds.add(entry.get().message().identifier());
                }
            }
        } finally {
            stream.close();
        }
        return new Sourced(List.copyOf(eventIds), marker);
    }

    private record Sourced(List<String> eventIds, long marker) {

    }
}
