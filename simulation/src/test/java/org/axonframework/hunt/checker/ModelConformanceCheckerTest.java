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

package org.axonframework.hunt.checker;

import org.axonframework.hunt.model.DcbStoreModel;
import org.axonframework.hunt.model.ModelAppendCondition;
import org.axonframework.hunt.model.ModelCriterion;
import org.axonframework.hunt.model.ModelEvent;
import org.axonframework.hunt.model.ModelTag;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that the model-conformance oracle both passes a sound history and catches every way of breaking the rule it
 * enforces. An oracle with no demonstrated failure mode is decoration.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
class ModelConformanceCheckerTest {

    private static final ModelTag STUDENT = ModelTag.of("student", "s-1");
    private static final Set<ModelCriterion> BOUNDARY = Set.of(ModelCriterion.havingTags(STUDENT));

    @TempDir
    Path directory;

    private final ModelConformanceChecker testSubject = new ModelConformanceChecker();

    private static ModelEvent event(String id) {
        return new ModelEvent(id, "StudentEnrolled", Set.of(STUDENT));
    }

    @Nested
    class SoundHistory {

        @Test
        void aHistoryWhoseAppendsAllAgreeWithTheModelPasses() {
            // given a first append that claims the boundary, then a correctly-rejected stale one, then a
            // correctly-accepted one anchored past the first
            SyntheticHistory history = new SyntheticHistory(directory, "sound");
            history.appendOk(ModelAppendCondition.withCriteria(BOUNDARY), event("e-0"));
            history.appendRejected(ModelAppendCondition.withCriteria(BOUNDARY), event("e-1"));
            history.appendOk(new ModelAppendCondition(1L, BOUNDARY), event("e-2"));

            // when
            CheckResult result = testSubject.check(history.view());

            // then
            assertThat(result.holds()).isTrue();
            assertThat(result.inconclusive()).isFalse();
        }

        @Test
        void appendsWithNoConditionAreNeverExpectedToBeRejected() {
            // given
            SyntheticHistory history = new SyntheticHistory(directory, "no-condition");
            history.appendOk(ModelAppendCondition.none(), event("e-0"));
            history.appendOk(ModelAppendCondition.none(), event("e-1"));

            // when
            CheckResult result = testSubject.check(history.view());

            // then
            assertThat(result.holds()).isTrue();
        }
    }

    @Nested
    class PlantedViolations {

        @Test
        void catchesAnAppendRecordedAsSuccessfulThatTheModelRejects() {
            // given a conflict-check bypass: two appends claiming the same boundary from the origin both succeed
            SyntheticHistory history = new SyntheticHistory(directory, "conflict-bypassed");
            history.appendOk(ModelAppendCondition.withCriteria(BOUNDARY), event("e-0"));
            history.appendOk(ModelAppendCondition.withCriteria(BOUNDARY), event("e-1"));

            // when
            CheckResult result = testSubject.check(history.view());

            // then
            assertThat(result.holds()).isFalse();
            assertThat(result.violations()).singleElement().satisfies(violation -> {
                assertThat(violation.machineName())
                        .isEqualTo(ModelConformanceChecker.APPEND_CONFORMS_TO_DCB_MODEL);
                assertThat(violation.detail()).contains("recorded as successful");
                assertThat(violation.records()).hasSize(2);
                assertThat(violation.seed()).isEqualTo(7L);
                assertThat(violation.reproduceCommand()).contains("-Dhunt.seed=7");
            });
        }

        @Test
        void catchesAnAppendRecordedAsRejectedThatTheModelAccepts() {
            // given a spurious rejection: nothing in the store can conflict with this boundary
            SyntheticHistory history = new SyntheticHistory(directory, "spurious-rejection");
            history.appendRejected(ModelAppendCondition.withCriteria(BOUNDARY), event("e-0"));

            // when
            CheckResult result = testSubject.check(history.view());

            // then
            assertThat(result.holds()).isFalse();
            assertThat(result.violations()).singleElement()
                                           .satisfies(violation -> assertThat(violation.detail())
                                                   .contains("recorded as rejected"));
        }

        @Test
        void catchesAnAppendAcceptedAgainstAMarkerThatShouldHaveSeenTheConflict() {
            // given an append anchored before a competing event that matches its boundary
            SyntheticHistory history = new SyntheticHistory(directory, "stale-marker-accepted");
            history.appendOk(ModelAppendCondition.none(), event("e-0"));
            history.appendOk(new ModelAppendCondition(DcbStoreModel.ORIGIN, BOUNDARY), event("e-1"));

            // when
            CheckResult result = testSubject.check(history.view());

            // then
            assertThat(result.holds()).isFalse();
        }
    }

    @Nested
    class AmbiguousHistory {

        @Test
        void anAppendWithAnUnknownOutcomeIsNotTreatedAsAViolationEitherWay() {
            // given
            SyntheticHistory history = new SyntheticHistory(directory, "unknown-append");
            history.appendUnknown(ModelAppendCondition.withCriteria(BOUNDARY), event("e-0"));

            // when
            CheckResult result = testSubject.check(history.view());

            // then
            assertThat(result.holds()).isTrue();
            assertThat(result.inconclusive()).isTrue();
            assertThat(result.notes()).anySatisfy(note -> assertThat(note).contains("unknown outcome"));
        }

        @Test
        void aMismatchAfterAnUnknownAppendIsReportedAsUndecidedRatherThanAsAViolation() {
            // given an unknown append followed by one the replayed state would reject
            SyntheticHistory history = new SyntheticHistory(directory, "undecidable");
            history.appendUnknown(ModelAppendCondition.withCriteria(BOUNDARY), event("e-0"));
            history.appendOk(ModelAppendCondition.withCriteria(BOUNDARY), event("e-1"));

            // when
            CheckResult result = testSubject.check(history.view());

            // then the state after the unknown append is not known, so no assertion is made
            assertThat(result.holds()).isTrue();
            assertThat(result.notes()).anySatisfy(note -> assertThat(note).contains("Undecidable"));
        }

        @Test
        void anOperationLeftOpenAtTheEndOfTheRunIsTreatedAsUnknownRatherThanDropped() {
            // given a run that ended with an append still in flight
            SyntheticHistory history = new SyntheticHistory(directory, "open-at-end");
            history.appendOk(ModelAppendCondition.withCriteria(BOUNDARY), event("e-0"));
            history.writer().invoke("append",
                                    null,
                                    org.axonframework.hunt.model.DcbHistoryCodec.encodeAppend(
                                            ModelAppendCondition.withCriteria(BOUNDARY),
                                            java.util.List.of(event("e-1"))));

            // when
            CheckResult result = testSubject.check(history.view());

            // then
            assertThat(result.holds()).isTrue();
            assertThat(result.inconclusive()).isTrue();
        }
    }
}
