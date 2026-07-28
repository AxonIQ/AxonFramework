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

import org.axonframework.hunt.checker.OwnershipChecker;
import org.axonframework.hunt.checker.StoredProgressChecker;
import org.axonframework.hunt.checker.Violation;
import org.axonframework.hunt.history.HistoryOps;
import org.axonframework.hunt.history.HistoryRecord;
import org.axonframework.hunt.history.HistoryView;
import org.axonframework.hunt.history.Operation;
import org.axonframework.hunt.history.Outcome;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Puts several nodes in competition for the same segments and asks who really owned each one, and when.
 * <p>
 * A claim is the framework's only defence against two instances processing the same events, and the whole defence rests
 * on comparing a timestamp one node wrote against another node's reading of the clock. Nothing in the framework states
 * how far those two clocks may disagree. These arms make them disagree by a stated amount and measure what happens,
 * while the tolerance the oracle applies stays at zero, because a tolerance raised to match the perturbation could never
 * report the thing being perturbed.
 * <p>
 * <b>The arms are deliberately not all expected to pass, and what separates them is not what the plan assumed.</b> The
 * skew does not become visible gradually: an owner refreshes its claim long before the claim timeout, so a skew smaller
 * than the margin between the two cannot make a live row look expired at all, and a skew beyond it takes segments away
 * immediately. The middle arm therefore holds and the outer one breaks, and each reports its measurement. A scenario
 * that can only pass measures nothing.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
class SegmentOwnershipUnderSkewTest {

    @Nested
    class EveryClockInStep {

        @Test
        void neverBothHoldOneSegmentAndHandOverMidBatch() {
            // given the contention arm with no skew declared, so any measurable overlap is a defect
            Scenario scenario = HuntScenarios.segmentOwnerWithoutSkew();

            // when every seed the smoke tier declares is run
            long startedAt = System.nanoTime();
            List<ScenarioResult> results =
                    ScenarioRunner.runTier(scenario, Tier.SMOKE, HuntHistories.directory("s4-no-skew"));
            Duration wall = Duration.ofNanos(System.nanoTime() - startedAt);
            results.forEach(System.out::println);
            int widestRewind = 0;
            int totalHandovers = 0;
            for (ScenarioResult result : results) {
                HistoryView history = HistoryView.read(result.history());
                widestRewind = Math.max(widestRewind, StoredProgressChecker.widestHandoverRewind(history));
                totalHandovers += StoredProgressChecker.handovers(history).size();
                System.out.println("S4 no-skew seed " + result.seed() + ": "
                                           + StoredProgressChecker.handovers(history).size() + " handover(s), widest "
                                           + "rewind " + StoredProgressChecker.widestHandoverRewind(history)
                                           + " event(s), widest redelivery "
                                           + StoredProgressChecker.widestHandoverRepeat(history) + " event(s)");
            }
            System.out.println("S4 no-skew total wall time: " + wall.toMillis() + "ms across " + results.size()
                                       + " seed(s); " + totalHandovers + " handover(s), widest rewind " + widestRewind
                                       + " event(s) against a batch of " + org.axonframework.hunt.harness.HuntWorld.PROJECTION_BATCH_SIZE);

            // then ownership must hold on every seed
            assertThat(results).hasSize(3);
            assertThat(results).allSatisfy(result -> assertThat(result.violations())
                    .as("violations for seed %d: %s", result.seed(), result)
                    .isEmpty());
            // and a segment must really have changed hands while its previous holder had work the stored token did not
            // yet cover, or the arm observed only orderly rebalances and says nothing at all about what a stale stored
            // token costs
            assertThat(totalHandovers).as("claim handovers across the tier").isPositive();
            // The rewind is reported rather than required to be positive. A handover that finds nothing uncovered is
            // the guarantee working, not a gap in the arm: a work package stores its progress with every batch it
            // finishes, so a crash landing between two batches legitimately costs nothing. What the number is for is
            // the comparison -- break the one-transaction guarantee and it stops being a handful of events.
            assertThat(widestRewind)
                    .as("the widest rewind a handover caused")
                    .isLessThanOrEqualTo(org.axonframework.hunt.harness.HuntWorld.PROJECTION_BATCH_SIZE);
        }
    }

    @Nested
    class OneClockHalfAClaimTimeoutAhead {

        @Test
        void stealsClaimsEarlyAndStaysInsideTheDeclaredTolerance() {
            // given four nodes over sixteen segments with one clock a second ahead of a two-second claim timeout
            Scenario scenario = HuntScenarios.segmentOwnerWithHalfTimeoutSkew();

            // when one seed is run
            ScenarioResult result =
                    ScenarioRunner.run(scenario, Tier.SMOKE, scenario.seed(), HuntHistories.directory("s4-half-skew"));
            HistoryView history = HistoryView.read(result.history());
            long widest = OwnershipChecker.widestOverlapMillis(history);
            long skew = scenario.timescale().emulatedClockSkew().toMillis();
            Set<String> stealers = nodesThatTookSomebodyElsesSegment(history);
            System.out.println("S4 half-timeout skew: emulated skew " + skew + "ms, widest overlap " + widest
                                       + "ms against a declared tolerance of "
                                       + scenario.timescale().ownershipSkewAllowance().toMillis()
                                       + "ms; segments changed hands to " + stealers);

            // then the overlap must not exceed the skew, which is the prediction the emulation makes and the thing the
            // oracle is judging: a node whose clock reads a delta ahead can take a claim at most a delta before it would
            // have lapsed anyway, so an overlap wider than the delta would mean the claim algebra is not the inequality
            // it appears to be
            assertThat(widest).as("widest overlap in %s", result.history()).isLessThanOrEqualTo(skew);
            assertThat(result.violations()).as("violations: %s", result).isEmpty();
        }
    }

    @Nested
    class OneClockTwiceAClaimTimeoutAhead {

        @Test
        void breaksOwnershipAndTheRunQuantifiesHowWidely() {
            // given the same cluster with one clock so far ahead that every row it reads looks expired to it
            Scenario scenario = HuntScenarios.segmentOwnerWithDoubleTimeoutSkew();

            // when one seed is run
            ScenarioResult result = ScenarioRunner.run(scenario, Tier.SMOKE, scenario.seed(),
                                                      HuntHistories.directory("s4-double-skew"));
            HistoryView history = HistoryView.read(result.history());
            long widest = OwnershipChecker.widestOverlapMillis(history);
            long claimTimeout = HuntScenarios.CLUSTER_CLAIM_TIMEOUT.toMillis();
            List<String> ownershipViolations =
                    result.violations().stream()
                          .filter(violation -> violation.machineName()
                                                        .equals(OwnershipChecker.AT_MOST_ONE_SEGMENT_OWNER))
                          .map(Violation::detail)
                          .toList();
            System.out.println("S4 double-timeout skew: emulated skew "
                                       + scenario.timescale().emulatedClockSkew().toMillis()
                                       + "ms, widest overlap " + widest + "ms against a claim timeout of " + claimTimeout
                                       + "ms and a declared tolerance of "
                                       + scenario.timescale().ownershipSkewAllowance().toMillis() + "ms; "
                                       + ownershipViolations.size() + " ownership violation(s)");
            ownershipViolations.stream().limit(3).forEach(detail -> System.out.println("  " + detail));

            // then ownership must be broken, because a node that considers every row in the store expired takes them
            // regardless of who holds them; the arm exists to put a number on the window rather than to assert zero
            assertThat(ownershipViolations)
                    .as("this arm is expected to break ownership; a clean run means the skew did not land")
                    .isNotEmpty();
            // and the overlap saturates at one claim timeout rather than growing with the skew, because the emulation
            // skews the comparison a node performs and not the timestamp it writes: the loser's own claim still lapses
            // one timeout after its last refresh however far ahead the thief's clock reads
            assertThat(widest).as("widest overlap in %s", result.history()).isPositive();
            assertThat(widest).isLessThanOrEqualTo(claimTimeout);
        }
    }

    /**
     * Returns the token writes and claim extensions the store refused because the caller no longer owned the segment.
     * <p>
     * That refusal is the framework telling a node, mid-batch, that its segment has moved: the token write is the last
     * step of the batch's own transaction, so a refusal there rolls the batch back. It is the only externally visible
     * sign that a handover landed in the middle of work rather than between two pieces of it.
     */
    private static List<HistoryRecord> lostClaimsWhileWorking(HistoryView history) {
        return history.records().stream()
                      .filter(record -> record.op().equals(HistoryOps.STORE_TOKEN)
                              || record.op().equals(HistoryOps.EXTEND))
                      .filter(record -> record.error() != null && record.error().contains("UnableToClaimToken"))
                      .toList();
    }

    private static Set<String> nodesThatTookSomebodyElsesSegment(HistoryView history) {
        Set<String> stealers = new LinkedHashSet<>();
        java.util.Map<String, String> lastOwner = new java.util.LinkedHashMap<>();
        for (Operation claim : history.operations(HistoryOps.CLAIM)) {
            if (claim.outcome() != Outcome.OK || claim.invocation().key() == null) {
                continue;
            }
            String node = String.valueOf(claim.invocation().node());
            String previous = lastOwner.put(claim.invocation().key(), node);
            if (previous != null && !previous.equals(node)) {
                stealers.add(node);
            }
        }
        return stealers;
    }
}
