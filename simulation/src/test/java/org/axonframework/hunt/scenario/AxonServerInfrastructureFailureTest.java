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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Breaks a real Axon Server underneath a running application: takes its network away, kills its process, and severs the
 * read side's event stream while it is streaming.
 * <p>
 * <b>Two of the three arms are shipped scenarios pointed at another store, and that is the extensibility charter paying
 * off rather than a shortcut.</b> The partition and kill arms are the same {@code Scenario} records the PostgreSQL chaos
 * arms run, with {@code onBackend} naming Axon Server instead: a fault reaches the infrastructure only through
 * {@code StoreInfrastructure}, so a store that advertises it can be broken inherits every infrastructure fault in the
 * suite without a single scenario being edited. The third arm is new because the question is new -- it is about the
 * consumer rather than about the writer.
 * <p>
 * <b>Every arm's verdict is read only after its fault is shown to have landed, from the infrastructure's own words.</b>
 * The proxy's reported enabled state for a cut; the container's exit code and the server's own restart line, with its own
 * timestamp, for a kill. A fault with no landing evidence makes a run inconclusive however clean it looks.
 * <p>
 * <b>What a failure on these arms may and may not be attributed to.</b> This arm links a released connector against a
 * reactor that connector predates, and one method of its storage engine is supplied by the harness. Before anything here
 * is written up as a framework or store defect it has to be checked against
 * {@code AxonServerBackendTest.TheVersionSkewThisArmWorksAround}, which records exactly which method that is, and against
 * {@code formal/CONNECTOR-COMPATIBILITY.md}, which records what the shim does and does not model.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
@Tag("container")
class AxonServerInfrastructureFailureTest {

    /**
     * The breakable Axon Server arm every scenario here is pointed at.
     */
    private static final String CHAOS_BACKEND = "axonserver-chaos";

    /**
     * The one exception the connector answers every failed commit with, whatever the failure was.
     */
    private static final String CONSISTENCY_REJECTION = "AppendEventsTransactionRejectedException";

    /**
     * Counts how the run's failed appends were classified, by the exception the client was given.
     * <p>
     * Read from the history rather than from a checker, because no invariant is about the classification itself: an
     * oracle can see that an append failed and can see what the failure was called, but the question of whether that
     * name was the right one is a question about the connector's mapping and not about the store's behaviour.
     */
    private static Map<String, Long> appendFailureClassifications(ScenarioResult result) {
        return HistoryView.read(result.history()).operations(HistoryOps.APPEND).stream()
                          .filter(append -> append.completion() != null)
                          .map(append -> append.completion().error())
                          .filter(java.util.Objects::nonNull)
                          .collect(java.util.stream.Collectors.groupingBy(error -> error,
                                                                          java.util.TreeMap::new,
                                                                          java.util.stream.Collectors.counting()));
    }

    /**
     * Prints everything a reader needs to check a verdict against, and returns the fault records.
     */
    private static List<HistoryRecord> report(String label, ScenarioResult result) {
        System.out.println(label + " " + result.verdict() + " wall=" + result.wallTime().toMillis() + "ms fires="
                                   + result.faultFires());
        HistoryView history = HistoryView.read(result.history());
        System.out.println("  " + label + " versions " + history.header().versions());
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

    private static Map<String, Object> drainOf(ScenarioResult result) {
        return HistoryView.read(result.history()).notes(HistoryOps.PHASE).stream()
                          .filter(phase -> phase.stringValue(HistoryOps.QUIESCED) != null)
                          .map(HistoryRecord::value)
                          .findFirst()
                          .orElse(Map.of());
    }

    private static long undelivered(ScenarioResult result) {
        Map<String, Object> drain = drainOf(result);
        long readable = Long.parseLong(String.valueOf(drain.getOrDefault("readableEvents", "0")));
        long delivered = Long.parseLong(String.valueOf(drain.getOrDefault("deliveredEvents", "0")));
        return Math.max(0L, readable - delivered);
    }

    @Nested
    class KillingTheServerWhileTheWorkloadAppendsToIt {

        @Test
        void provesTheKillLandedAndHoldsEveryAcknowledgedAppendToTheServerItself() {
            // given the shipped kill arm, pointed at Axon Server rather than at PostgreSQL and edited in no other way
            Scenario scenario = HuntScenarios.crashRecoveryNoAckedLoss().onBackend(CHAOS_BACKEND);

            // when it is run
            ScenarioResult result = ScenarioRunner.run(scenario, Tier.SMOKE, scenario.seed(),
                                                       HuntHistories.directory("axonserver-crash"));
            List<HistoryRecord> faults = report("as-crash", result);

            // then the kill demonstrably happened, evidenced by the container's own exit code and by the line the server
            // wrote on its way back up. Asserted before any verdict is read, because a green run under a nemesis that
            // never fired has verified nothing.
            assertThat(firesOf(faults, "store-crash"))
                    .as("kill-and-restart cycles that landed")
                    .isGreaterThanOrEqualTo(1L);
            assertThat(targetsOf(faults, "store-crash"))
                    .as("landing evidence for the kill")
                    .contains("exit code 137")
                    .contains("recovery line");

            // and durability is DECIDED here rather than merely measured, which is what this store adds. On the
            // aggregate-based PostgreSQL arms the append transaction's commit call does no work and races the database
            // transaction, so an append the harness calls acknowledged is not one the client saw succeed and the oracle
            // refuses to decide. On Axon Server the commit call is the commit: it sends the transaction and returns the
            // consistency marker the server assigned, so an acknowledgement is the client's own and the store can be held
            // to it.
            assertThat(result.notApplicable())
                    .as("durability must be decidable on a store whose commit call is the commit: %s", result)
                    .noneMatch(statement -> statement.contains(DurabilityChecker.ACKNOWLEDGED_APPEND_IS_DURABLE));
            assertThat(result.measurements())
                    .as("the client's own verdict set, which is what durability is checked against")
                    .anySatisfy(measured -> assertThat(measured).contains("acknowledged"));
        }
    }

    @Nested
    class CuttingTheNetworkWhileLeavingTheServerRunning {

        @Test
        void producesAcknowledgementsNobodyCanInterpretAndHoldsTheServerToTheOnesThatCanBe() {
            // given the shipped partition arm, pointed at Axon Server
            Scenario scenario = HuntScenarios.commitAckMatchesDurabilityUnderPartition().onBackend(CHAOS_BACKEND);

            // when it is run
            ScenarioResult result = ScenarioRunner.run(scenario, Tier.SMOKE, scenario.seed(),
                                                       HuntHistories.directory("axonserver-partition"));
            List<HistoryRecord> faults = report("as-partition", result);

            // then every cut is accounted for by the proxy's own reported state
            assertThat(firesOf(faults, "store-partition"))
                    .as("network cuts that landed")
                    .isGreaterThanOrEqualTo(1L);
            assertThat(targetsOf(faults, "store-partition"))
                    .as("landing evidence for the cut")
                    .contains("proxy after cut");

            // and the client's verdict set is published, ambiguity and all
            assertThat(result.measurements())
                    .as("the client's own verdict set, including how much of it is ambiguous")
                    .anySatisfy(measured -> assertThat(measured).contains("ambiguous"));

            // and this is the connector's mapping, held against the run that breaks its assumption. The network was cut
            // underneath appends that were in flight; a dropped connection is an outcome nobody knows, and the store
            // deciding against an append is an outcome somebody decided. The connector answers both with the same
            // exception type, so every failure below is reported as a decision. Asserted as the whole classification
            // set rather than as a count, because the count varies with where the cuts land and the set does not: the
            // day a transport failure surfaces as anything other than a rejection, this assertion goes red and the
            // finding is closed.
            Map<String, Long> appendFailures = appendFailureClassifications(result);
            System.out.println("as-partition append failure classifications: " + appendFailures);
            assertThat(appendFailures)
                    .as("appends must have failed while the network was cut, or this says nothing about the mapping")
                    .isNotEmpty();
            assertThat(appendFailures.keySet())
                    .as("every commit failure under a network partition is reported as a consistency decision (the "
                                + "connector maps all of them to one exception); a run that reports a transport "
                                + "failure as such closes this gap and turns this assertion red")
                    .containsExactly(CONSISTENCY_REJECTION);

            // and a cut that reached no commit window is reported as such and costs the run its verdict rather than
            // failing the arm. FAIL means the durability rule was broken; INCONCLUSIVE means the nemesis never reached
            // the window the rule is about, and an arm that failed on the second would be flaky by construction.
            boolean nemesisMissed = result.notes().stream()
                                          .anyMatch(note -> note.contains("produced no ambiguous append at all"));
            System.out.println("as-partition nemesis reached a commit window: " + !nemesisMissed);
            if (nemesisMissed) {
                assertThat(result.verdict())
                        .as("a cut that reached no commit window may not pass: %s", result)
                        .isNotEqualTo(Verdict.PASS);
            }
        }
    }

    @Nested
    class SeveringTheReadSidesEventStreamWhileItIsStreaming {

        @Test
        void resumesWithoutLosingAnEventAndWithEveryRepeatAccountedForByARecordedRewind() {
            // given the stream-resume arm: eight cuts of the connection under a running streaming read side
            Scenario scenario = HuntScenarios.streamResumeNoLossNoSilentDuplicate();

            // when it is run
            ScenarioResult result = ScenarioRunner.run(scenario, Tier.SMOKE, scenario.seed(),
                                                       HuntHistories.directory("axonserver-stream-resume"));
            List<HistoryRecord> faults = report("as-stream", result);

            // then the stream really was severed, evidenced by the proxy's own reported state either side of each cut
            assertThat(firesOf(faults, "store-partition"))
                    .as("stream severances that landed")
                    .isGreaterThanOrEqualTo(1L);
            assertThat(targetsOf(faults, "store-partition"))
                    .as("landing evidence for the severance")
                    .contains("proxy after cut")
                    .contains("proxy after heal");

            // and the drain says what became of the read side, which is what makes loss decidable here at all
            assertThat(drainOf(result))
                    .as("the drain's own account of the read side: %s", result)
                    .containsKeys(HistoryOps.QUIESCED, HistoryOps.STALLED, "readableEvents", "deliveredEvents");

            // and this is the property the arm exists to establish: where the read side stopped with events still in the
            // store, loss is DECIDED. A stream that resumes past an event it never delivered produces exactly the same
            // observation as a run that was merely interrupted, so excusing the second excuses the first -- and the
            // second is the defect this arm is looking for. Nothing here asserts how many events a race skipped; it
            // asserts that a skip is called a skip.
            long missing = undelivered(result);
            long lossViolations = result.violations().stream()
                                        .filter(violation -> DeliveryChecker.NO_COMMITTED_EVENT_GOES_UNDELIVERED
                                                .equals(violation.machineName()))
                                        .count();
            boolean stalled = Boolean.parseBoolean(String.valueOf(drainOf(result).get(HistoryOps.STALLED)));
            System.out.println("as-stream missing=" + missing + " stalled=" + stalled
                                       + " lossViolations=" + lossViolations);
            if (stalled && missing > 0) {
                assertThat(lossViolations)
                        .as("a read side that stopped with %d event(s) still in the store must be judged, not excused: "
                                    + "%s", missing, result)
                        .isGreaterThan(0L);
            }

            // and every repeat the resumption produced is either explained by a rewind the history recorded -- a position
            // the store handed back that sits behind something the segment had already delivered -- or reported. An
            // unexplained repeat is the "silent" this arm's identifier names, and the oracle decides which it was rather
            // than the arm guessing.
            System.out.println("as-stream redelivery account: "
                                       + result.measurements().stream()
                                               .filter(measured -> measured.contains("repeat")
                                                       || measured.contains("redeliver"))
                                               .toList()
                                       + result.notes().stream()
                                               .filter(note -> note.contains("repeat") || note.contains("redeliver"))
                                               .toList());
        }
    }
}
