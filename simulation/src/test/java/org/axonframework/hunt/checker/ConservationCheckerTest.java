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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plants each way the ledger's arithmetic can be wrong and checks the oracle catches it.
 * <p>
 * A checker that has never been shown to fail is decoration. Every rule this one enforces gets a history where that
 * rule is broken on purpose, and one where nothing is.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
class ConservationCheckerTest {

    @TempDir
    Path directory;

    private final ConservationChecker checker = new ConservationChecker();

    @Nested
    class SoundHistory {

        @Test
        void aLedgerWhoseProjectionMatchesItsCommittedTransfersHolds() {
            // given two accounts opening at 100 each, and one committed transfer of 30
            SyntheticHistory history = new SyntheticHistory(directory, "sound-ledger");
            history.transferOk("a", "b", 30L);
            history.transferFailed("a", "b", 5_000L);
            history.projection(200L, Map.of("a", 70L, "b", 130L));

            // when
            CheckResult result = checker.check(history.view());

            // then
            assertThat(result.holds()).as("%s", result).isTrue();
            assertThat(result.inconclusive()).as("%s", result).isFalse();
        }

        @Test
        void aHistoryWithNoLedgerInItProducesNoVerdictAndNoNoise() {
            // given a history that never touched the ledger
            SyntheticHistory history = new SyntheticHistory(directory, "no-ledger");
            history.deliver("e-0");

            // when
            CheckResult result = checker.check(history.view());

            // then
            assertThat(result.holds()).isTrue();
            assertThat(result.notes()).isEmpty();
        }
    }

    @Nested
    class PlantedViolations {

        @Test
        void catchesMoneyThatVanishedBetweenTheWriteSideAndTheReadSide() {
            // given a projection that lost 30 of the ledger's 200
            SyntheticHistory history = new SyntheticHistory(directory, "money-lost");
            history.transferOk("a", "b", 30L);
            history.projection(200L, Map.of("a", 70L, "b", 100L));

            // when
            CheckResult result = checker.check(history.view());

            // then both the total and the fold must be reported broken
            assertThat(result.violations()).extracting(Violation::machineName)
                                           .contains(ConservationChecker.LEDGER_CONSERVES_TOTAL_BALANCE,
                                                     ConservationChecker.PROJECTION_MATCHES_FOLD_OF_COMMITTED_EVENTS);
        }

        @Test
        void catchesAnAccountDrivenBelowZeroByCommittedTransfers() {
            // given two committed transfers out of an account that could only cover one
            SyntheticHistory history = new SyntheticHistory(directory, "overdrawn");
            history.transferOk("a", "b", 80L);
            history.transferOk("a", "b", 80L);
            history.projection(200L, Map.of("a", -60L, "b", 260L));

            // when
            CheckResult result = checker.check(history.view());

            // then
            assertThat(result.violations()).extracting(Violation::machineName)
                                           .contains(ConservationChecker.LEDGER_BALANCE_NEVER_NEGATIVE);
        }

        @Test
        void catchesAProjectionThatDivergedWithoutLosingTheTotal() {
            // given a projection whose total is right and whose per-account split is not
            SyntheticHistory history = new SyntheticHistory(directory, "diverged");
            history.transferOk("a", "b", 30L);
            history.projection(200L, Map.of("a", 60L, "b", 140L));

            // when
            CheckResult result = checker.check(history.view());

            // then
            assertThat(result.violations()).extracting(Violation::machineName)
                                           .containsOnly(
                                                   ConservationChecker.PROJECTION_MATCHES_FOLD_OF_COMMITTED_EVENTS);
        }
    }

    @Nested
    class AmbiguousHistory {

        @Test
        void reportsTheSameArithmeticAsUndecidedWhenAFaultPerturbedTheStore() {
            // given the money-losing history, with the harness admitting it broke the store itself
            SyntheticHistory history = new SyntheticHistory(directory, "perturbed");
            history.storePerturbed("vanish");
            history.transferOk("a", "b", 30L);
            history.projection(200L, Map.of("a", 70L, "b", 100L));

            // when
            CheckResult result = checker.check(history.view());

            // then nothing may be blamed on the framework, and the numbers must still be visible
            assertThat(result.violations()).isEmpty();
            assertThat(result.inconclusive()).isTrue();
            assertThat(result.notes()).anyMatch(note -> note.contains("LedgerConservesTotalBalance"));
        }
    }
}
