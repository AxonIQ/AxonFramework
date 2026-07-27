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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the Dynamic Consistency Boundary rules the reference model encodes, one case per rule.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
class DcbStoreModelTest {

    private static final ModelTag STUDENT_1 = ModelTag.of("student", "s-1");
    private static final ModelTag STUDENT_2 = ModelTag.of("student", "s-2");
    private static final ModelTag COURSE_1 = ModelTag.of("course", "c-1");

    private static final String ENROLLED = "StudentEnrolled";
    private static final String REGISTERED = "CourseRegistered";

    private final DcbStoreModel testSubject = new DcbStoreModel();

    private static ModelEvent event(String id, String type, ModelTag... tags) {
        return new ModelEvent(id, type, Set.of(tags));
    }

    private DcbStoreModel.AppendVerdict append(ModelAppendCondition condition, ModelEvent... events) {
        return testSubject.append(condition, List.of(events));
    }

    @Nested
    class TagMatching {

        @Test
        void allTagsOfACriterionMustBePresentForItToMatch() {
            // C1: an append whose criteria match an event stored after its marker is rejected.
            // given a stored event carrying both tags
            append(ModelAppendCondition.none(), event("e-0", ENROLLED, STUDENT_1, COURSE_1));

            // when appending under a criterion naming both tags, anchored at the origin
            DcbStoreModel.AppendVerdict both = testSubject.append(
                    ModelAppendCondition.withCriteria(Set.of(ModelCriterion.havingTags(STUDENT_1, COURSE_1))),
                    List.of(event("e-1", ENROLLED, STUDENT_1))
            );

            // then the stored event matches, so the append conflicts
            assertThat(both.accepted()).isFalse();
            assertThat(both.conflictingPosition()).isEqualTo(0L);
        }

        @Test
        void aCriterionNamingATagTheEventDoesNotCarryDoesNotMatch() {
            // C1: the conflict scan matches only events carrying every tag the criterion names.
            // given a stored event carrying only one of the two tags
            append(ModelAppendCondition.none(), event("e-0", ENROLLED, STUDENT_1));

            // when appending under a criterion naming both tags
            DcbStoreModel.AppendVerdict verdict = testSubject.append(
                    ModelAppendCondition.withCriteria(Set.of(ModelCriterion.havingTags(STUDENT_1, COURSE_1))),
                    List.of(event("e-1", ENROLLED, STUDENT_1, COURSE_1))
            );

            // then a partial tag match is not a match, so the append is legal
            assertThat(verdict.accepted()).isTrue();
        }

        @Test
        void anEventCarryingMoreTagsThanTheCriterionNamesStillMatches() {
            // C1: matching is containment, not equality.
            // given a stored event carrying an extra tag
            append(ModelAppendCondition.none(), event("e-0", ENROLLED, STUDENT_1, COURSE_1));

            // when appending under a criterion naming only one of them
            DcbStoreModel.AppendVerdict verdict = testSubject.append(
                    ModelAppendCondition.withCriteria(Set.of(ModelCriterion.havingTags(STUDENT_1))),
                    List.of(event("e-1", ENROLLED, STUDENT_1))
            );

            // then the extra tag does not prevent the match
            assertThat(verdict.accepted()).isFalse();
        }
    }

    @Nested
    class CriteriaCombination {

        @Test
        void criteriaAreCombinedWithOrSoMatchingEitherOneIsAConflict() {
            // C7: after sourcing tags A then B, the append criteria are the OR of both.
            // given a stored event matching only the second criterion
            append(ModelAppendCondition.none(), event("e-0", ENROLLED, STUDENT_2));

            // when appending under a boundary naming both students
            DcbStoreModel.AppendVerdict verdict = testSubject.append(
                    ModelAppendCondition.withCriteria(Set.of(ModelCriterion.havingTags(STUDENT_1),
                                                             ModelCriterion.havingTags(STUDENT_2))),
                    List.of(event("e-1", ENROLLED, STUDENT_1))
            );

            // then matching either criterion is enough to conflict
            assertThat(verdict.accepted()).isFalse();
            assertThat(verdict.conflictingPosition()).isEqualTo(0L);
        }

        @Test
        void aTypeRestrictedCriterionMatchesOnlyTheNamedTypes() {
            // C1: narrowing a criterion by type narrows the boundary, avoiding a false conflict.
            // given a stored event of a type the criterion does not name
            append(ModelAppendCondition.none(), event("e-0", REGISTERED, STUDENT_1));

            // when appending under a criterion restricted to another type
            DcbStoreModel.AppendVerdict verdict = testSubject.append(
                    ModelAppendCondition.withCriteria(Set.of(
                            ModelCriterion.havingTagsAndTypes(Set.of(STUDENT_1), Set.of(ENROLLED)))),
                    List.of(event("e-1", ENROLLED, STUDENT_1))
            );

            // then the type restriction excludes the stored event
            assertThat(verdict.accepted()).isTrue();
        }

        @Test
        void aCriterionNamingNoTypesAcceptsEveryType() {
            // C1: the type restriction is optional and defaults to all types.
            // given a stored event of an arbitrary type
            append(ModelAppendCondition.none(), event("e-0", REGISTERED, STUDENT_1));

            // when appending under a criterion naming tags only
            DcbStoreModel.AppendVerdict verdict = testSubject.append(
                    ModelAppendCondition.withCriteria(Set.of(ModelCriterion.havingTags(STUDENT_1))),
                    List.of(event("e-1", ENROLLED, STUDENT_1))
            );

            // then the type is not consulted and the tag match stands
            assertThat(verdict.accepted()).isFalse();
        }

        @Test
        void anEmptyBoundaryMatchesEveryEvent() {
            // C2: the no-criteria factory matches everything; only the marker keeps such an append legal.
            // given a stored event with no relation to the appender
            append(ModelAppendCondition.none(), event("e-0", REGISTERED, COURSE_1));

            // when appending under an empty boundary anchored at the origin
            DcbStoreModel.AppendVerdict verdict = testSubject.append(
                    ModelAppendCondition.withCriteria(Set.of()),
                    List.of(event("e-1", ENROLLED, STUDENT_1))
            );

            // then every stored event is in scope and the append conflicts
            assertThat(verdict.accepted()).isFalse();
        }
    }

    @Nested
    class MarkerSemantics {

        @Test
        void theOriginMarkerPutsEveryStoredEventInScope() {
            // C3: under the origin marker every event already in the store that matches the criteria is a conflict.
            // given
            append(ModelAppendCondition.none(), event("e-0", ENROLLED, STUDENT_1));

            // when
            DcbStoreModel.AppendVerdict verdict = testSubject.append(
                    new ModelAppendCondition(DcbStoreModel.ORIGIN,
                                             Set.of(ModelCriterion.havingTags(STUDENT_1))),
                    List.of(event("e-1", ENROLLED, STUDENT_1))
            );

            // then
            assertThat(verdict.accepted()).isFalse();
            assertThat(verdict.conflictingPosition()).isEqualTo(0L);
        }

        @Test
        void theInfinityMarkerDisablesConflictDetectionEntirely() {
            // C2: an append with no condition is never rejected as conflicting.
            // given a store already holding a matching event
            append(ModelAppendCondition.none(), event("e-0", ENROLLED, STUDENT_1));

            // when appending under the no-condition marker
            DcbStoreModel.AppendVerdict verdict = testSubject.append(
                    new ModelAppendCondition(DcbStoreModel.INFINITY,
                                             Set.of(ModelCriterion.havingTags(STUDENT_1))),
                    List.of(event("e-1", ENROLLED, STUDENT_1))
            );

            // then the scan is skipped altogether
            assertThat(verdict.accepted()).isTrue();
            assertThat(verdict.rule()).isEqualTo(DcbStoreModel.Rule.MARKER_INFINITY_BYPASSES_CONFLICT_CHECK);
        }

        @Test
        void aMarkerTakenFromASourcingExcludesEverythingThatSourcingAlreadySaw() {
            // C5: the append marker is derived from the sourcing stream's terminal marker.
            // given two stored events and a sourcing that read them both
            append(ModelAppendCondition.none(),
                   event("e-0", ENROLLED, STUDENT_1),
                   event("e-1", ENROLLED, STUDENT_1));
            DcbStoreModel.SourceResult sourced = testSubject.source(
                    ModelSourcingCondition.conditionFor(Set.of(ModelCriterion.havingTags(STUDENT_1))));

            // when appending anchored at the marker the sourcing reported
            DcbStoreModel.AppendVerdict verdict = testSubject.append(
                    new ModelAppendCondition(sourced.marker(), Set.of(ModelCriterion.havingTags(STUDENT_1))),
                    List.of(event("e-2", ENROLLED, STUDENT_1))
            );

            // then the events the sourcing already accounted for are not conflicts
            assertThat(sourced.marker()).isEqualTo(2L);
            assertThat(verdict.accepted()).isTrue();
        }

        @Test
        void anEventAppendedAfterTheMarkerWasTakenIsAConflict() {
            // C1: appending fails when a matching event landed after the marker.
            // given a sourcing whose marker was taken before a competing append landed
            append(ModelAppendCondition.none(), event("e-0", ENROLLED, STUDENT_1));
            DcbStoreModel.SourceResult sourced = testSubject.source(
                    ModelSourcingCondition.conditionFor(Set.of(ModelCriterion.havingTags(STUDENT_1))));
            append(ModelAppendCondition.none(), event("e-competitor", ENROLLED, STUDENT_1));

            // when appending against the now-stale marker
            DcbStoreModel.AppendVerdict verdict = testSubject.append(
                    new ModelAppendCondition(sourced.marker(), Set.of(ModelCriterion.havingTags(STUDENT_1))),
                    List.of(event("e-2", ENROLLED, STUDENT_1))
            );

            // then the competing event is found and the append is rejected
            assertThat(verdict.accepted()).isFalse();
            assertThat(verdict.conflictingPosition()).isEqualTo(1L);
        }

        @Test
        void takingTheLowerOfTwoSourcingMarkersKeepsBothSourcingsProtected() {
            // C6: when one transaction sources twice, the append marker is the lower bound of the two.
            // given two sourcings taken at different heights
            append(ModelAppendCondition.none(), event("e-0", ENROLLED, STUDENT_1));
            long firstMarker = testSubject.source(
                    ModelSourcingCondition.conditionFor(Set.of(ModelCriterion.havingTags(STUDENT_1)))).marker();
            append(ModelAppendCondition.none(), event("e-1", REGISTERED, COURSE_1));
            long secondMarker = testSubject.source(
                    ModelSourcingCondition.conditionFor(Set.of(ModelCriterion.havingTags(COURSE_1)))).marker();

            // when appending anchored at the lower of the two, under the OR of both boundaries
            DcbStoreModel.AppendVerdict verdict = testSubject.append(
                    new ModelAppendCondition(Math.min(firstMarker, secondMarker),
                                             Set.of(ModelCriterion.havingTags(STUDENT_1),
                                                    ModelCriterion.havingTags(COURSE_1))),
                    List.of(event("e-2", ENROLLED, STUDENT_1))
            );

            // then the event that landed between the two sourcings is still in scope and conflicts
            assertThat(firstMarker).isEqualTo(1L);
            assertThat(secondMarker).isEqualTo(2L);
            assertThat(verdict.accepted()).isFalse();
            assertThat(verdict.conflictingPosition()).isEqualTo(1L);
        }
    }

    @Nested
    class Storage {

        @Test
        void anAppendAgainstAnEmptyStoreStartsAtPositionZero() {
            // C10: events are stored in the order they are offered.
            // given an empty store
            assertThat(testSubject.head()).isZero();

            // when
            DcbStoreModel.AppendVerdict verdict = append(ModelAppendCondition.none(),
                                                         event("e-0", ENROLLED, STUDENT_1));

            // then
            assertThat(verdict.positions()).containsExactly(0L);
            assertThat(verdict.marker()).isEqualTo(1L);
        }

        @Test
        void anAppendAgainstAnEmptyStoreUnderTheOriginMarkerIsLegal() {
            // C3: the origin marker scans the whole store, which is empty, so nothing conflicts.
            // given an empty store

            // when
            DcbStoreModel.AppendVerdict verdict = testSubject.append(
                    ModelAppendCondition.withCriteria(Set.of(ModelCriterion.havingTags(STUDENT_1))),
                    List.of(event("e-0", ENROLLED, STUDENT_1))
            );

            // then
            assertThat(verdict.accepted()).isTrue();
        }

        @Test
        void aBatchTakesConsecutivePositionsInOfferOrder() {
            // C10: events will be appended in the order that they are offered in.
            // given / when
            DcbStoreModel.AppendVerdict verdict = append(ModelAppendCondition.none(),
                                                         event("e-0", ENROLLED, STUDENT_1),
                                                         event("e-1", ENROLLED, STUDENT_1),
                                                         event("e-2", REGISTERED, COURSE_1));

            // then
            assertThat(verdict.positions()).containsExactly(0L, 1L, 2L);
            assertThat(testSubject.events()).extracting(ModelEvent::id).containsExactly("e-0", "e-1", "e-2");
            assertThat(verdict.marker()).isEqualTo(3L);
        }

        @Test
        void anEmptyBatchStoresNothingAndReportsTheOriginMarker() {
            // C10: an append with no events has nothing to order and nothing to report.
            // given / when
            DcbStoreModel.AppendVerdict verdict = testSubject.append(ModelAppendCondition.none(), List.of());

            // then
            assertThat(verdict.accepted()).isTrue();
            assertThat(verdict.positions()).isEmpty();
            assertThat(verdict.marker()).isEqualTo(DcbStoreModel.ORIGIN);
            assertThat(testSubject.head()).isZero();
        }

        @Test
        void aRejectedAppendStoresNoneOfItsBatch() {
            // C9: a rejected append must leave zero of its events in the store, not a partial batch.
            // given a store that will conflict with the next append
            append(ModelAppendCondition.none(), event("e-0", ENROLLED, STUDENT_1));

            // when
            DcbStoreModel.AppendVerdict verdict = testSubject.append(
                    ModelAppendCondition.withCriteria(Set.of(ModelCriterion.havingTags(STUDENT_1))),
                    List.of(event("e-1", ENROLLED, STUDENT_1), event("e-2", ENROLLED, STUDENT_1))
            );

            // then
            assertThat(verdict.accepted()).isFalse();
            assertThat(testSubject.events()).extracting(ModelEvent::id).containsExactly("e-0");
            assertThat(testSubject.head()).isEqualTo(1L);
        }
    }

    @Nested
    class Sourcing {

        @Test
        void sourcingReturnsOnlyMatchingEventsInPositionOrder() {
            // C1: the boundary filters what a sourcing reads.
            // given
            append(ModelAppendCondition.none(),
                   event("e-0", ENROLLED, STUDENT_1),
                   event("e-1", REGISTERED, COURSE_1),
                   event("e-2", ENROLLED, STUDENT_1));

            // when
            DcbStoreModel.SourceResult sourced = testSubject.source(
                    ModelSourcingCondition.conditionFor(Set.of(ModelCriterion.havingTags(STUDENT_1))));

            // then
            assertThat(sourced.eventIds()).containsExactly("e-0", "e-2");
        }

        @Test
        void theSourcingMarkerIsTheStoreHeadRatherThanThePositionOfTheLastMatch() {
            // C5: the marker comes from the stream's terminal entry, which reflects the store, not the match set.
            // given a matching event followed by a non-matching one
            append(ModelAppendCondition.none(),
                   event("e-0", ENROLLED, STUDENT_1),
                   event("e-1", REGISTERED, COURSE_1));

            // when
            DcbStoreModel.SourceResult sourced = testSubject.source(
                    ModelSourcingCondition.conditionFor(Set.of(ModelCriterion.havingTags(STUDENT_1))));

            // then
            assertThat(sourced.eventIds()).containsExactly("e-0");
            assertThat(sourced.marker()).isEqualTo(2L);
        }

        @Test
        void sourcingAnEmptyStoreReportsTheHeadOfAnEmptyStore() {
            // C30: a sourcing always ends with a marker, even when it read nothing.
            // given an empty store

            // when
            DcbStoreModel.SourceResult sourced = testSubject.source(
                    ModelSourcingCondition.conditionFor(Set.of(ModelCriterion.havingTags(STUDENT_1))));

            // then
            assertThat(sourced.events()).isEmpty();
            assertThat(sourced.marker()).isZero();
        }

        @Test
        void sourcingFromAPositionSkipsEverythingBeforeIt() {
            // C1: a sourcing may resume from a position rather than from the start of the stream.
            // given
            append(ModelAppendCondition.none(),
                   event("e-0", ENROLLED, STUDENT_1),
                   event("e-1", ENROLLED, STUDENT_1));

            // when
            DcbStoreModel.SourceResult sourced = testSubject.source(
                    new ModelSourcingCondition(1L, Set.of(ModelCriterion.havingTags(STUDENT_1))));

            // then
            assertThat(sourced.eventIds()).containsExactly("e-1");
        }
    }

    @Nested
    class HistoryCodec {

        @Test
        void anAppendSurvivesBeingRenderedIntoAHistoryRecordAndReadBack() {
            // given
            ModelAppendCondition condition = new ModelAppendCondition(
                    7L,
                    Set.of(ModelCriterion.havingTagsAndTypes(Set.of(STUDENT_1), Set.of(ENROLLED)),
                           ModelCriterion.havingTags(COURSE_1)));
            List<ModelEvent> batch = List.of(event("e-0", ENROLLED, STUDENT_1), event("e-1", REGISTERED, COURSE_1));

            // when
            var rendered = DcbHistoryCodec.encodeAppend(condition, batch);

            // then
            assertThat(DcbHistoryCodec.decodeCondition(rendered)).isEqualTo(condition);
            assertThat(DcbHistoryCodec.decodeEvents(rendered)).isEqualTo(batch);
        }

        @Test
        void aNoConditionAppendSurvivesTheRoundTripWithItsInfinityMarker() {
            // given
            ModelAppendCondition condition = ModelAppendCondition.none();

            // when
            var rendered = DcbHistoryCodec.encodeAppend(condition, List.of());

            // then
            assertThat(DcbHistoryCodec.decodeCondition(rendered).marker()).isEqualTo(DcbStoreModel.INFINITY);
            assertThat(DcbHistoryCodec.decodeCondition(rendered).criteria()).isEmpty();
        }
    }
}
