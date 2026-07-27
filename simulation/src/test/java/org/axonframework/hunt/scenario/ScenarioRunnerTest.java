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

import org.axonframework.hunt.checker.AppendOutcomeChecker;
import org.axonframework.hunt.checker.ConservationChecker;
import org.axonframework.hunt.checker.FaultLandingChecker;
import org.axonframework.hunt.checker.ModelConformanceChecker;
import org.axonframework.hunt.fault.ConflictCheckBypassFault;
import org.axonframework.hunt.fault.FaultSchedule;
import org.axonframework.hunt.fault.FaultWindow;
import org.axonframework.hunt.fault.ParticipantPauseFault;
import org.axonframework.hunt.fault.WriteThenVanishFault;
import org.axonframework.hunt.harness.DeterminismMode;
import org.axonframework.hunt.workload.LedgerWorkload;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives whole scenarios through the runner and checks the verdicts it produces.
 * <p>
 * Four things are being established here, and only the first is about the framework. The runner must judge a clean
 * run as a pass; it must judge a run whose declared fault never fired as undecided rather than a pass; it must go red
 * when the property it enforces is actually broken; and it must be able to run a scenario nobody wrote it for.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
class ScenarioRunnerTest {

    @Nested
    class ContendedAppendsHoldTheProtocol {

        @Test
        void everySmokeSeedPassesWithinItsBudget() {
            // given the shipped scenario at the tier that runs on every change
            Scenario scenario = HuntScenarios.appendRejectedAfterMarker();

            // when every seed the tier declares is run
            long startedAt = System.nanoTime();
            List<ScenarioResult> results =
                    ScenarioRunner.runTier(scenario, Tier.SMOKE, HuntHistories.directory("s1-smoke"));
            Duration wall = Duration.ofNanos(System.nanoTime() - startedAt);
            results.forEach(result -> System.out.println(result));
            System.out.println("S1 smoke total wall time: " + wall.toMillis() + "ms across "
                                       + results.size() + " seed(s)");

            // then every seed must pass, and the tier must fit inside its stated budget
            assertThat(results).hasSize(3);
            assertThat(results).allSatisfy(result -> assertThat(result.verdict())
                    .as("verdict for seed %d: %s", result.seed(), result)
                    .isEqualTo(Verdict.PASS));
            assertThat(wall).isLessThan(Duration.ofSeconds(90));
        }

        @Test
        void theRunActuallyExercisedBothAppendVerdicts() {
            // given a single seed of the shipped scenario
            Scenario scenario = HuntScenarios.appendRejectedAfterMarker();

            // when it is run
            ScenarioResult result =
                    ScenarioRunner.run(scenario, Tier.SMOKE, scenario.seed(), HuntHistories.directory("s1-verdicts"));

            // then the history must contain both accepted and rejected appends, or the agreement means nothing
            var history = org.axonframework.hunt.history.HistoryView.read(result.history());
            var appends = history.operations(org.axonframework.hunt.history.HistoryOps.APPEND);
            long accepted = appends.stream()
                                   .filter(append -> append.outcome() == org.axonframework.hunt.history.Outcome.OK)
                                   .count();
            long rejected = appends.stream()
                                   .filter(append -> append.outcome() == org.axonframework.hunt.history.Outcome.FAIL)
                                   .count();
            System.out.println("appends accepted=" + accepted + " rejected=" + rejected);
            assertThat(accepted).as("accepted appends").isPositive();
            assertThat(rejected).as("rejected appends under contention").isPositive();
        }
    }

    @Nested
    class AFaultThatNeverFires {

        @Test
        void makesTheRunUndecidedRatherThanAPass() {
            // given a scenario declaring a stall against a participant that does not exist in this run, so the
            // fault is armed and can never land
            Scenario scenario = Scenario.builder("pause_that_never_lands",
                                                 "A stall aimed at nothing, to prove an unlanded fault is not a pass")
                                        .claims("C1")
                                        .workload(LedgerWorkload::seedShaped)
                                        .determinism(DeterminismMode.SINGLE_THREADED)
                                        .faults(FaultSchedule.builder()
                                                             .warmup(Duration.ofMillis(50))
                                                             .window(FaultWindow.immediately(
                                                                     "stall",
                                                                     Duration.ofMillis(50),
                                                                     new ParticipantPauseFault(Duration.ofMillis(1),
                                                                                               0)))
                                                             .heal(Duration.ofMillis(20))
                                                             .settle(Duration.ofSeconds(20))
                                                             .build())
                                        .oracles(FaultLandingChecker.DECLARED_FAULTS_LAND)
                                        .seed(11L)
                                        .budget(Tier.SMOKE, new TierBudget(1, 1, Duration.ofSeconds(60)))
                                        .build();

            // when the run finishes with the workload having issued its single command before the stall was armed
            ScenarioResult result =
                    ScenarioRunner.run(scenario, Tier.SMOKE, scenario.seed(), HuntHistories.directory("unlanded"));
            System.out.println(result);

            // then nothing is broken, and the run is still not a pass
            assertThat(result.violations()).isEmpty();
            assertThat(result.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
            assertThat(result.faultFires()).containsEntry("participant-pause", 0L);
            assertThat(result.notes()).anyMatch(note -> note.contains("never fired"));
        }
    }

    @Nested
    class AStoreThatSkipsItsConflictCheck {

        @Test
        void isCaughtAsAViolationWithACommandThatReplaysIt() {
            // given a store whose consistency check is broken, under enough contention for it to matter
            Scenario scenario = Scenario.builder("conflict_check_bypass_canary",
                                                 "A store that forgets to enforce the append condition")
                                        .claims("C1", "C8")
                                        .workload(LedgerWorkload::hotKey)
                                        .faults(FaultSchedule.builder()
                                                             .warmup(Duration.ofMillis(10))
                                                             .window(FaultWindow.immediately(
                                                                     "broken-check",
                                                                     Duration.ofMillis(500),
                                                                     new ConflictCheckBypassFault(1)))
                                                             .heal(Duration.ofMillis(50))
                                                             .settle(Duration.ofSeconds(20))
                                                             .build())
                                        .oracles(ModelConformanceChecker.APPEND_CONFORMS_TO_DCB_MODEL)
                                        .seed(3L)
                                        .budget(Tier.SMOKE, new TierBudget(4_000, 1, Duration.ofSeconds(60)))
                                        .build();

            // when the run is judged
            ScenarioResult result =
                    ScenarioRunner.run(scenario, Tier.SMOKE, scenario.seed(), HuntHistories.directory("canary"));
            System.out.println(result);

            // then the model-conformance oracle must have caught it, and must say how to see it again
            assertThat(result.verdict()).isEqualTo(Verdict.FAIL);
            assertThat(result.violations()).isNotEmpty();
            assertThat(result.violations())
                    .anyMatch(violation -> violation.machineName()
                                                    .equals(ModelConformanceChecker.APPEND_CONFORMS_TO_DCB_MODEL));
            assertThat(result.reproduceCommand()).contains("-Dhunt.scenario=conflict_check_bypass_canary")
                                                 .contains("-Dhunt.seed=3");
        }
    }

    @Nested
    class AStoreThatLosesHalfABatch {

        @Test
        void leavesTheLedgerUndecidedRatherThanBlamingTheFramework() {
            // given a store that silently drops commits it reported as successful
            Scenario scenario = Scenario.builder("write_then_vanish_probe",
                                                 "A store that acknowledges a commit it did not make")
                                        .claims("C4", "C29")
                                        .workload(LedgerWorkload::seedShaped)
                                        .faults(FaultSchedule.builder()
                                                             .warmup(Duration.ofMillis(10))
                                                             .window(FaultWindow.immediately(
                                                                     "lossy-store",
                                                                     Duration.ofMillis(500),
                                                                     new WriteThenVanishFault(3)))
                                                             .heal(Duration.ofMillis(50))
                                                             .settle(Duration.ofSeconds(5))
                                                             .build())
                                        .oracles(ConservationChecker.LEDGER_CONSERVES_TOTAL_BALANCE,
                                                 FaultLandingChecker.DECLARED_FAULTS_LAND)
                                        .seed(5L)
                                        .budget(Tier.SMOKE, new TierBudget(2_000, 1, Duration.ofSeconds(60)))
                                        .build();

            // when the run is judged
            ScenarioResult result =
                    ScenarioRunner.run(scenario, Tier.SMOKE, scenario.seed(), HuntHistories.directory("vanish"));
            System.out.println(result);

            // then the fault must have landed, and the missing money must be attributed to it rather than reported
            // as a defect in a framework that was told the store had committed
            assertThat(result.faultFires().get("write-then-vanish")).isPositive();
            assertThat(result.violations()).isEmpty();
            assertThat(result.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
            assertThat(result.notes()).anyMatch(note -> note.contains("other than what was offered"));
        }
    }

    @Nested
    class ANewScenario {

        @Test
        void runsThroughTheSameRunnerWithoutTouchingTheHarness() {
            // given a scenario declared here and nowhere else: no registry entry, no runner change, no new class
            Scenario scenario = Scenario.builder("uncontended_transfers_conserve_balance",
                                                 "A small uncontended ledger, declared entirely at the call site")
                                        .claims("C1")
                                        .workload(LedgerWorkload::seedShaped)
                                        .determinism(DeterminismMode.SINGLE_THREADED)
                                        .faults(FaultSchedule.none(Duration.ofSeconds(20)))
                                        .oracles(ConservationChecker.LEDGER_CONSERVES_TOTAL_BALANCE,
                                                 AppendOutcomeChecker.REJECTED_APPEND_LEAVES_NO_EVENTS)
                                        .seed(42L)
                                        .budget(Tier.SMOKE, new TierBudget(120, 1, Duration.ofSeconds(60)))
                                        .build();

            // when it is handed to the runner every other scenario uses
            ScenarioResult result =
                    ScenarioRunner.run(scenario, Tier.SMOKE, scenario.seed(), HuntHistories.directory("new"));
            System.out.println(result);

            // then it produces a verdict like any other
            assertThat(result.verdict()).isEqualTo(Verdict.PASS);
            assertThat(result.scenarioId()).isEqualTo("uncontended_transfers_conserve_balance");
        }
    }

    @Nested
    class TheFaultCompositionPolicy {

        @Test
        void refusesToRunAPairOfFaultsAtTheTierThatAllowsOne() {
            // given a scenario declaring two simultaneous faults and, wrongly, a smoke budget for them
            Scenario scenario = Scenario.builder("two_faults_at_once", "A pair of faults in one window")
                                        .claims("C1")
                                        .workload(LedgerWorkload::seedShaped)
                                        .faults(FaultSchedule.builder()
                                                             .window(FaultWindow.immediately(
                                                                     "both",
                                                                     Duration.ofMillis(100),
                                                                     new WriteThenVanishFault(5),
                                                                     new ConflictCheckBypassFault(5)))
                                                             .settle(Duration.ofSeconds(5))
                                                             .build())
                                        .seed(1L)
                                        .budget(Tier.SMOKE, new TierBudget(10, 1, Duration.ofSeconds(30)))
                                        .budget(Tier.HARDENING, new TierBudget(10, 1, Duration.ofSeconds(30)))
                                        .build();

            // then the smoke tier must refuse it before running anything, because a compound failure cannot be
            // attributed, and the hardening tier must accept exactly the same schedule
            assertThat(scenario.faults().maxConcurrentFaults()).isEqualTo(2);
            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> ScenarioRunner.run(scenario, Tier.SMOKE, 1L,
                                                                 HuntHistories.directory("composition")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("simultaneous faults")
                    .hasMessageContaining("SMOKE tier caps at 1");
            assertThat(Tier.HARDENING.maxConcurrentFaults()).isGreaterThanOrEqualTo(2);
        }

        @Test
        void refusesATierTheScenarioDeclaredNoBudgetFor() {
            // given the shipped faulted arm, which declares no smoke budget on purpose
            Scenario scenario = HuntScenarios.appendRejectedAfterMarkerUnderFault();

            // then
            assertThat(scenario.budgets()).doesNotContainKey(Tier.SMOKE);
            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> ScenarioRunner.run(scenario, Tier.SMOKE, 1L,
                                                                 HuntHistories.directory("composition")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("declares no budget");
        }
    }
}
