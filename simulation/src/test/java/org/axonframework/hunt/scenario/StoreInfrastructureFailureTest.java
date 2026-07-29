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

import org.axonframework.hunt.checker.DeliveryChecker;
import org.axonframework.hunt.checker.DurabilityChecker;
import org.axonframework.hunt.history.HistoryOps;
import org.axonframework.hunt.history.HistoryRecord;
import org.axonframework.hunt.history.HistoryView;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Breaks the store's machinery rather than a wrapper around it, and asks what the framework did about it.
 * <p>
 * <b>Everything the suite had broken before this ran was in the same heap as the framework.</b> A hook that refuses a
 * commit, drops half a batch or stalls a thread is a decision the harness makes and hands to the framework as an ordinary
 * failure. None of it produces a socket that dies with a request in flight, a process whose memory is gone, or a store
 * that stops answering and then continues from where it stopped. Those are the failures a deployment is actually operated
 * through, so until one of them landed against a real store the fault layer was unproven whatever it reported.
 * <p>
 * The three arms here are three genuinely different failures rather than three settings of one, and each is checked to
 * have <em>landed</em> from the infrastructure's own answer before its verdict is read: the proxy's reported state, the
 * process's exit code and its recovery line, the paused flag. A fault with no landing evidence makes a run inconclusive
 * however clean it looks, which is the rule that stops a green run under a nemesis that never fired from being read as a
 * pass.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
@Tag("container")
class StoreInfrastructureFailureTest {

    /**
     * Prints everything a reader needs to check the verdict against, and returns the fault records.
     * <p>
     * The fault records are the point: a scenario's verdict is only worth reading next to the evidence that its nemesis
     * reached anything, and that evidence is what the infrastructure itself reported rather than what the harness decided
     * to claim.
     */
    private static List<HistoryRecord> report(String label, ScenarioResult result) {
        System.out.println(label + " " + result.verdict() + " wall=" + result.wallTime().toMillis() + "ms fires="
                                   + result.faultFires());
        HistoryView history = HistoryView.read(result.history());
        List<HistoryRecord> faults = history.notes(HistoryOps.FAULT);
        faults.forEach(fault -> System.out.println("  " + label + " LANDING " + fault.value()));
        history.notes(HistoryOps.PHASE).stream()
               .filter(phase -> phase.stringValue(HistoryOps.QUIESCED) != null)
               .forEach(phase -> System.out.println("  " + label + " drain " + phase.value()));
        result.violations().forEach(violation -> System.out.println("  " + label + " violation: " + violation));
        result.notes().forEach(note -> System.out.println("  " + label + " note: " + note));
        result.measurements().forEach(measured -> System.out.println("  " + label + " measured: " + measured));
        result.notApplicable().forEach(statement -> System.out.println("  " + label + " n/a: " + statement));
        return faults;
    }

    private static long firesOf(List<HistoryRecord> faults, String kind) {
        return faults.stream()
                     .filter(fault -> kind.equals(fault.stringValue("kind")))
                     .mapToLong(fault -> fault.longValue("fires", 0L))
                     .sum();
    }

    private static String targetsOf(List<HistoryRecord> faults, String kind) {
        return faults.stream()
                     .filter(fault -> kind.equals(fault.stringValue("kind")))
                     .map(fault -> String.valueOf(fault.value().get("targets")))
                     .findFirst()
                     .orElse("");
    }

    @Nested
    class KillingTheStoreWhileTheWorkloadAppendsToIt {

        @Test
        void provesTheKillLandedAndHoldsEveryAcknowledgedAppendToTheStoreItself() {
            // given the arm that kills the store's process outright and starts it again
            Scenario scenario = HuntScenarios.crashRecoveryNoAckedLoss();

            // when it is run
            ScenarioResult result = ScenarioRunner.run(scenario, Tier.SMOKE, scenario.seed(),
                                                       HuntHistories.directory("store-crash"));
            List<HistoryRecord> faults = report("crash", result);

            // then the kill demonstrably happened, evidenced by the process's own exit code and by the recovery line the
            // store wrote on its way back up. A run whose nemesis cannot be shown to have fired verifies nothing, so this
            // is asserted before any verdict is read.
            assertThat(firesOf(faults, "store-crash"))
                    .as("kill-and-restart cycles that landed")
                    .isGreaterThanOrEqualTo(1L);
            assertThat(targetsOf(faults, "store-crash"))
                    .as("landing evidence for the kill")
                    .contains("exit code 137")
                    .contains("database system is ready to accept connections");

            // and the client's verdict set is published, which is what durability is compared against
            assertThat(result.measurements())
                    .as("the client's own verdict set, which is what durability is checked against")
                    .anySatisfy(measured -> assertThat(measured).contains("acknowledged"));

            // and durability is measured rather than asserted, because on this store it cannot yet honestly be decided.
            // An append is recorded as acknowledged when the engine's append transaction reports its commit, and on a
            // store whose transaction lives on the processing context that call does no work and races the database
            // transaction rather than preceding it -- so on a run that also breaks the connection, an append the harness
            // calls acknowledged is not necessarily one the client saw succeed. The oracle says so rather than reporting
            // the harness's own accounting as the store losing data, and the arm prints the answer it did get.
            assertThat(result.notApplicable())
                    .as("durability must say why it cannot decide here rather than deciding wrongly: %s", result)
                    .anySatisfy(statement -> assertThat(statement)
                            .contains(DurabilityChecker.ACKNOWLEDGED_APPEND_IS_DURABLE, "not expressible"));
            System.out.println("crash durability violations (measured, not asserted): "
                                       + durabilityViolations(result));
        }
    }

    @Nested
    class CuttingTheNetworkWhileLeavingTheStoreRunning {

        @Test
        void producesAcknowledgementsNobodyCanInterpretAndHoldsTheStoreToTheOnesThatCanBe() {
            // given the arm that cuts the connection repeatedly with the store left running
            Scenario scenario = HuntScenarios.commitAckMatchesDurabilityUnderPartition();

            // when it is run
            ScenarioResult result = ScenarioRunner.run(scenario, Tier.SMOKE, scenario.seed(),
                                                       HuntHistories.directory("store-partition"));
            List<HistoryRecord> faults = report("partition", result);

            // then every cut is accounted for by the proxy's own reported state. The evidence is checked for the proxy's
            // own words rather than for an exact JSON spelling: a landing check that depends on how a payload happens to
            // be escaped is a check that fails for a reason that is not the fault's.
            assertThat(firesOf(faults, "store-partition"))
                    .as("network cuts that landed")
                    .isGreaterThanOrEqualTo(1L);
            assertThat(targetsOf(faults, "store-partition"))
                    .as("landing evidence for the cut")
                    .contains("proxy after cut")
                    .contains("aimed at the commit of");

            // and the client's verdict set is published, ambiguity and all
            assertThat(result.measurements())
                    .as("the client's own verdict set, including how much of it is ambiguous")
                    .anySatisfy(measured -> assertThat(measured).contains("ambiguous"));

            // and durability says why it cannot decide here, for the same reason as on the kill arm and more sharply: a cut
            // aimed at the commit boundary makes every cut commit one whose recorded acknowledgement came from a call that
            // does no work. Measured, before the oracle was taught this: two violations that were the harness's accounting
            // and not the store's doing.
            assertThat(result.notApplicable())
                    .as("durability must say why it cannot decide here rather than deciding wrongly: %s", result)
                    .anySatisfy(statement -> assertThat(statement)
                            .contains(DurabilityChecker.ACKNOWLEDGED_APPEND_IS_DURABLE, "not expressible"));
            System.out.println("partition durability violations (measured, not asserted): "
                                       + durabilityViolations(result));

            // and a cut that missed every commit window is reported as such and costs the run its verdict, rather than
            // failing the arm. The distinction matters: FAIL means the durability rule was broken, INCONCLUSIVE means the
            // nemesis did not reach the window the rule is about, and an arm that failed on the second would be flaky by
            // construction -- a nemesis asserted as though it always lands. The cut is now aimed at the commit boundary
            // precisely so that it does, and this is the honest answer for the run where it still does not.
            boolean nemesisMissed = result.notes().stream()
                                          .anyMatch(note -> note.contains("produced no ambiguous append at all"));
            System.out.println("partition nemesis reached a commit window: " + !nemesisMissed);
            if (nemesisMissed) {
                assertThat(result.verdict())
                        .as("a cut that reached no commit window may not pass: %s", result)
                        .isNotEqualTo(org.axonframework.hunt.scenario.Verdict.PASS);
            }
        }
    }

    @Nested
    class HoldingCommitsOpenPastTheStoresGapTimeout {

        @Test
        void reportsWhatEachConfigurationPathDoesWithAnEventThatCommittedTooLate() {
            // given the two arms of the same hunt, differing only in how the store's gap settings were configured. The
            // framework's own configuration record and Spring Boot's auto-configuration default the gap timeout and the
            // maximum gap offset to each other's values, so a deployment's gap behaviour depends on how it was assembled.
            Scenario core = HuntScenarios.noEventSkippedByGapTimeout();
            Scenario spring = HuntScenarios.noEventSkippedByGapTimeoutSpringDefaults();

            // when both are run
            ScenarioResult coreResult = ScenarioRunner.run(core, Tier.SMOKE, core.seed(),
                                                           HuntHistories.directory("gap-core-defaults"));
            ScenarioResult springResult = ScenarioRunner.run(spring, Tier.SMOKE, spring.seed(),
                                                             HuntHistories.directory("gap-spring-defaults"));
            List<HistoryRecord> coreFaults = report("gap-core", coreResult);
            List<HistoryRecord> springFaults = report("gap-spring", springResult);
            System.out.println("VECTOR " + core.id() + " " + core.backend() + ":" + coreResult.verdict()
                                       + " " + spring.backend() + ":" + springResult.verdict());

            // then both arms held commits open, which is the only thing that opens a hole a reader has to come back for
            assertThat(firesOf(coreFaults, "late-commit"))
                    .as("commits held open on the arm configured from the framework's own defaults")
                    .isGreaterThanOrEqualTo(1L);
            assertThat(firesOf(springFaults, "late-commit"))
                    .as("commits held open on the Spring-Boot-configured arm")
                    .isGreaterThanOrEqualTo(1L);

            // and each arm's drain says whether its read side finished or stopped, which is what makes loss decidable
            // here at all: a skipped index is not late, because the reader's token has moved past it and holds no gap for
            // it, so nothing will ever ask for it again.
            List.of(coreResult, springResult).forEach(result -> assertThat(drainOf(result))
                    .as("the drain's own account of the read side: %s", result)
                    .containsKeys(HistoryOps.QUIESCED, HistoryOps.STALLED, "readableEvents", "deliveredEvents"));

            // and this is the property the whole arm exists to establish: where the read side stopped with events still
            // in the store, the loss oracle DECIDES. It used to decline on exactly this observation, on the reasonable
            // grounds that an interrupted run has lost nothing -- and a store that skips an index for ever produces
            // precisely that same observation, so the guard that prevented false findings also swallowed the real one.
            // A drain that reports the read side as having stopped is what tells the two apart, and nothing here asserts
            // how many events a race happened to skip: it asserts that a skip is called a skip.
            List.of(coreResult, springResult).forEach(result -> {
                long missing = missingEvents(result);
                System.out.println(result.scenarioId() + " missing=" + missing
                                           + " lossViolations=" + lossViolations(result));
                // The skip itself is asserted, expected-gap style, because its presence on this deliberate arm has
                // been measured rather than assumed: eight arm-runs across four suite runs, every one positive
                // (missing between 2 and 8; the numbers are recorded with finding F-16). A hold of twice the gap
                // timeout on every commit guarantees the ageing, so the only variance is how many indices interleave.
                // This goes red the day the store stops skipping, which is the signal to close the finding.
                assertThat(missing)
                        .as("committed events the store held that no consumer ever received (finding F-16); zero "
                                    + "means the gap is closed and this assertion must be turned around")
                        .isPositive();
                if (stalled(result) && missing > 0) {
                    assertThat(lossViolations(result))
                            .as("a read side that has stopped with %d event(s) still in the store must be judged, "
                                        + "not excused: %s", missing, result)
                            .isGreaterThan(0L);
                    assertThat(result.notes())
                            .as("loss must not be downgraded to a note on a run that stopped moving: %s", result)
                            .noneMatch(note -> note.contains("not judged as loss"));
                }
            });
        }

        private static boolean stalled(ScenarioResult result) {
            return Boolean.parseBoolean(String.valueOf(drainOf(result).get(HistoryOps.STALLED)));
        }

        private static long missingEvents(ScenarioResult result) {
            java.util.Map<String, Object> drain = drainOf(result);
            long readable = Long.parseLong(String.valueOf(drain.getOrDefault("readableEvents", "0")));
            long delivered = Long.parseLong(String.valueOf(drain.getOrDefault("deliveredEvents", "0")));
            return Math.max(0L, readable - delivered);
        }

        private static long lossViolations(ScenarioResult result) {
            return result.violations().stream()
                         .filter(violation -> DeliveryChecker.NO_COMMITTED_EVENT_GOES_UNDELIVERED
                                 .equals(violation.machineName()))
                         .count();
        }
    }

    private static long durabilityViolations(ScenarioResult result) {
        return result.violations().stream()
                     .filter(violation -> DurabilityChecker.ACKNOWLEDGED_APPEND_IS_DURABLE
                             .equals(violation.machineName()))
                     .count();
    }

    private static java.util.Map<String, Object> drainOf(ScenarioResult result) {
        return HistoryView.read(result.history()).notes(HistoryOps.PHASE).stream()
                          .filter(phase -> phase.stringValue(HistoryOps.QUIESCED) != null)
                          .map(HistoryRecord::value)
                          .findFirst()
                          .orElse(java.util.Map.of());
    }
}
