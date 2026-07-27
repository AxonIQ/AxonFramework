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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that the visibility oracle passes a sound history and catches every way of breaking each of the two
 * invariants it enforces.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
class VisibilityCheckerTest {

    @TempDir
    Path directory;

    private final VisibilityChecker testSubject = new VisibilityChecker();

    @Nested
    class SoundHistory {

        @Test
        void deliveryAfterCommitOfACommittedEventPasses() {
            // given
            SyntheticHistory history = new SyntheticHistory(directory, "sound");
            history.commit("e-0", "e-1");
            history.deliver("e-0");
            history.deliver("e-1");
            history.scan("e-0", "e-1");

            // when
            CheckResult result = testSubject.check(history.view());

            // then
            assertThat(result.holds()).isTrue();
            assertThat(result.inconclusive()).isFalse();
        }

        @Test
        void aRolledBackEventThatIsNeitherDeliveredNorScannedPasses() {
            // given
            SyntheticHistory history = new SyntheticHistory(directory, "clean-rollback");
            history.commit("e-0");
            history.rollback("e-1");
            history.deliver("e-0");
            history.scan("e-0");

            // when
            CheckResult result = testSubject.check(history.view());

            // then
            assertThat(result.holds()).isTrue();
        }
    }

    @Nested
    class PlantedViolationsOfNoVisibilityBeforeCommit {

        @Test
        void catchesADeliveryRecordedBeforeItsCommit() {
            // given
            SyntheticHistory history = new SyntheticHistory(directory, "delivered-early");
            history.deliver("e-0");
            history.commit("e-0");

            // when
            CheckResult result = testSubject.check(history.view());

            // then
            assertThat(result.holds()).isFalse();
            assertThat(result.violations()).singleElement().satisfies(violation -> {
                assertThat(violation.machineName()).isEqualTo(VisibilityChecker.NO_VISIBILITY_BEFORE_COMMIT);
                assertThat(violation.detail()).contains("before its commit");
                assertThat(violation.reproduceCommand()).contains("-Dhunt.seed=7");
            });
        }

        @Test
        void catchesADeliveryOfAnEventThatWasNeverCommitted() {
            // given
            SyntheticHistory history = new SyntheticHistory(directory, "never-committed");
            history.commit("e-0");
            history.deliver("e-ghost");

            // when
            CheckResult result = testSubject.check(history.view());

            // then
            assertThat(result.holds()).isFalse();
            assertThat(result.violations()).singleElement()
                                           .satisfies(violation -> assertThat(violation.detail())
                                                   .contains("never committed"));
        }
    }

    @Nested
    class PlantedViolationsOfRolledBackEventsNeverObservable {

        @Test
        void catchesADeliveryOfARolledBackEvent() {
            // given
            SyntheticHistory history = new SyntheticHistory(directory, "delivered-rolled-back");
            history.commit("e-0");
            history.rollback("e-0");
            history.deliver("e-0");

            // when
            CheckResult result = testSubject.check(history.view());

            // then
            assertThat(result.holds()).isFalse();
            assertThat(result.violations()).extracting(Violation::machineName)
                                           .containsExactly(VisibilityChecker.ROLLED_BACK_EVENTS_NEVER_OBSERVABLE);
        }

        @Test
        void catchesARolledBackEventLeftBehindInAPostRunScan() {
            // given
            SyntheticHistory history = new SyntheticHistory(directory, "scanned-rolled-back");
            history.rollback("e-0");
            history.scan("e-0");

            // when
            CheckResult result = testSubject.check(history.view());

            // then
            assertThat(result.holds()).isFalse();
            assertThat(result.violations()).singleElement().satisfies(violation -> {
                assertThat(violation.machineName())
                        .isEqualTo(VisibilityChecker.ROLLED_BACK_EVENTS_NEVER_OBSERVABLE);
                assertThat(violation.detail()).contains("post-run scan");
            });
        }
    }

    @Nested
    class AmbiguousHistory {

        @Test
        void aDeliveryOfAnEventWhoseCommitOutcomeIsUnknownIsReportedRatherThanAsserted() {
            // given
            SyntheticHistory history = new SyntheticHistory(directory, "unknown-commit");
            history.commitUnknown("e-0");
            history.deliver("e-0");

            // when
            CheckResult result = testSubject.check(history.view());

            // then
            assertThat(result.holds()).isTrue();
            assertThat(result.inconclusive()).isTrue();
            assertThat(result.notes()).anySatisfy(note -> assertThat(note).contains("commit outcome is unknown"));
        }

        @Test
        void aCommitLeftOpenAtTheEndOfTheRunDoesNotTurnItsDeliveriesIntoViolations() {
            // given a run that ended before the commit completed
            SyntheticHistory history = new SyntheticHistory(directory, "open-commit");
            history.writer().invoke("commit",
                                    null,
                                    java.util.Map.of("eventIds", java.util.List.of("e-0")));
            history.deliver("e-0");

            // when
            CheckResult result = testSubject.check(history.view());

            // then
            assertThat(result.holds()).isTrue();
            assertThat(result.inconclusive()).isTrue();
        }
    }
}
