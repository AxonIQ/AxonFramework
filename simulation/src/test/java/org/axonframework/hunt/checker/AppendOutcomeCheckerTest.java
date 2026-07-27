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
 * Plants each way an append's outcome can be wrong and checks the oracle catches it.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
class AppendOutcomeCheckerTest {

    private static final ModelTag STUDENT = ModelTag.of("student", "s-1");
    private static final Set<ModelCriterion> BOUNDARY = Set.of(ModelCriterion.havingTags(STUDENT));

    @TempDir
    Path directory;

    private final AppendOutcomeChecker checker = new AppendOutcomeChecker();

    private static ModelEvent event(String id) {
        return new ModelEvent(id, "StudentEnrolled", Set.of(STUDENT));
    }

    @Nested
    class SoundHistory {

        @Test
        void unconditionalAppendsThatSucceededAndRejectionsThatStoredNothingHold() {
            // given an unconditional append that landed and a conditioned one that was rejected
            SyntheticHistory history = new SyntheticHistory(directory, "sound-appends");
            history.appendOk(ModelAppendCondition.none(), event("e-0"));
            history.appendRejected(ModelAppendCondition.withCriteria(BOUNDARY), event("e-1"));
            history.scan("e-0");

            // when
            CheckResult result = checker.check(history.view());

            // then
            assertThat(result.holds()).as("%s", result).isTrue();
            assertThat(result.inconclusive()).as("%s", result).isFalse();
        }
    }

    @Nested
    class PlantedViolations {

        @Test
        void catchesAnUnconditionalAppendThatWasRejectedAsConflicting() {
            // given an append claiming no boundary at all, rejected by the store's consistency check
            SyntheticHistory history = new SyntheticHistory(directory, "unconditional-rejected");
            history.appendRejected(ModelAppendCondition.none(), event("e-0"));
            history.scan();

            // when
            CheckResult result = checker.check(history.view());

            // then
            assertThat(result.violations()).extracting(Violation::machineName)
                                           .containsOnly(AppendOutcomeChecker.UNCONDITIONAL_APPEND_NEVER_REJECTED);
        }

        @Test
        void catchesARejectedAppendWhoseEventsAreStillInTheStore() {
            // given a rejected append one of whose events turns up in the authoritative scan
            SyntheticHistory history = new SyntheticHistory(directory, "leaked");
            history.appendRejected(ModelAppendCondition.withCriteria(BOUNDARY), event("e-0"), event("e-1"));
            history.scan("e-1");

            // when
            CheckResult result = checker.check(history.view());

            // then
            assertThat(result.violations()).extracting(Violation::machineName)
                                           .containsOnly(AppendOutcomeChecker.REJECTED_APPEND_LEAVES_NO_EVENTS);
            assertThat(result.violations().getFirst().detail()).contains("e-1");
        }
    }

    @Nested
    class AmbiguousHistory {

        @Test
        void saysSoWhenTheRunNeverScannedTheStore() {
            // given a run that ended without an authoritative scan
            SyntheticHistory history = new SyntheticHistory(directory, "unscanned");
            history.appendRejected(ModelAppendCondition.withCriteria(BOUNDARY), event("e-0"));

            // when
            CheckResult result = checker.check(history.view());

            // then what the rejection left behind is unknown, and the checker says so rather than assuming
            assertThat(result.holds()).isTrue();
            assertThat(result.inconclusive()).isTrue();
        }
    }
}
