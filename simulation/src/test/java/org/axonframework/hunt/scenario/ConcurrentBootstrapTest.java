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

import org.axonframework.hunt.checker.LivenessChecker;
import org.axonframework.hunt.checker.OwnershipChecker;
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
 * Drives several nodes into a token store that holds nothing and watches what genesis does to them.
 * <p>
 * The claim rules the framework documents are all rules about an entry that already exists. A first deployment has no
 * entries at all: every node discovers an empty store at the same instant, every node concludes that it must create
 * the segments, and they race. This is where the classic first-deploy defects live, and no steady-state guarantee
 * covers it.
 * <p>
 * The arm is worthless unless the nodes really did collide, so the race is evidenced rather than assumed: the test
 * asserts that two nodes' initialisation attempts genuinely overlapped in time before it asserts anything about the
 * outcome. A clean result from a cluster that happened to boot in single file proves nothing.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
class ConcurrentBootstrapTest {

    @Nested
    class FourNodesBootingIntoAnEmptyTokenStore {

        @Test
        void endUpWithExactlyTheConfiguredSegments() {
            // given the bootstrap arm on the backend whose token store really arbitrates a claim
            Scenario scenario = HuntScenarios.concurrentBootstrap();

            // when every seed the smoke tier declares is run
            long startedAt = System.nanoTime();
            List<ScenarioResult> results =
                    ScenarioRunner.runTier(scenario, Tier.SMOKE, HuntHistories.directory("s15-bootstrap"));
            Duration wall = Duration.ofNanos(System.nanoTime() - startedAt);
            results.forEach(System.out::println);
            System.out.println("S15 bootstrap total wall time: " + wall.toMillis() + "ms across "
                                       + results.size() + " seed(s)");

            // then nothing may be found broken, and the store must hold each configured segment exactly once
            assertThat(results).hasSize(3);
            assertThat(results).allSatisfy(result -> assertThat(result.violations())
                    .as("violations for seed %d: %s", result.seed(), result)
                    .isEmpty());
            assertThat(results).allSatisfy(result -> {
                HistoryView history = HistoryView.read(result.history());
                assertThat(finalSegments(history))
                        .as("segments the store held after seed %d", result.seed())
                        .containsExactly(0, 1, 2, 3);
            });
        }

        @Test
        void reallyRacedEachOtherToCreateThem() {
            // given one seed of the bootstrap arm
            Scenario scenario = HuntScenarios.concurrentBootstrap();

            // when it is run
            ScenarioResult result = ScenarioRunner.run(scenario, Tier.SMOKE, scenario.seed(),
                                                       HuntHistories.directory("s15-race"));
            HistoryView history = HistoryView.read(result.history());
            List<Operation> attempts = history.operations(HistoryOps.INIT_SEGMENTS);
            Set<String> attemptingNodes = new LinkedHashSet<>();
            attempts.forEach(attempt -> attemptingNodes.add(String.valueOf(attempt.invocation().node())));
            long succeeded = attempts.stream().filter(attempt -> attempt.outcome() == Outcome.OK).count();
            int overlapping = overlappingPairs(attempts);
            System.out.println("S15 bootstrap race: " + attempts.size() + " initialisation attempt(s) from "
                                       + attemptingNodes + ", " + succeeded + " accepted, " + overlapping
                                       + " overlapping pair(s) from distinct nodes");

            // then more than one node must have been inside an initialisation while another one was, or the arm
            // observed a cluster that booted in single file and proves nothing about a race
            assertThat(attemptingNodes).hasSizeGreaterThan(1);
            assertThat(overlapping).isPositive();
            // and exactly one of the racing attempts may create the segments
            assertThat(succeeded).isEqualTo(1L);
        }

        @Test
        void holdOwnershipOfEverySegmentFromTheFirstInstant() {
            // given one seed of the bootstrap arm
            Scenario scenario = HuntScenarios.concurrentBootstrap();

            // when it is run
            ScenarioResult result = ScenarioRunner.run(scenario, Tier.SMOKE, scenario.seed() + 1,
                                                       HuntHistories.directory("s15-ownership"));
            HistoryView history = HistoryView.read(result.history());

            // then the ownership oracle must have judged rather than held vacuously, and it must have held from the
            // first record onwards, because genesis is the window the arm exists for
            assertThat(history.header().workloadShape())
                    .containsEntry(OwnershipChecker.ARBITRATES_CLAIMS, "true");
            assertThat(nodesThatClaimed(history)).hasSizeGreaterThan(1);
            assertThat(result.results())
                    .filteredOn(checked -> checked.checkerName().equals("OwnershipChecker"))
                    .singleElement()
                    .satisfies(checked -> assertThat(checked.violations()).isEmpty());
            assertThat(result.violations()).isEmpty();
        }

        @Test
        void deliverEveryCommittedEventWellInsideTheDeclaredHorizon() {
            // given one seed of the bootstrap arm
            Scenario scenario = HuntScenarios.concurrentBootstrap();

            // when it is run
            ScenarioResult result = ScenarioRunner.run(scenario, Tier.SMOKE, scenario.seed() + 2,
                                                       HuntHistories.directory("s15-liveness"));
            HistoryView history = HistoryView.read(result.history());
            long slowest = LivenessChecker.slowestDeliveryNanos(history);
            System.out.println("S15 slowest commit-to-delivery latency: " + (slowest / 1_000_000L) + "ms against a "
                                       + scenario.livenessHorizon().toMillis() + "ms horizon");

            // then something must actually have been measured, and it must sit inside the declared horizon; the
            // margin between the two is printed so that a horizon drifting towards a rubber stamp is visible
            assertThat(slowest).isNotNegative();
            assertThat(Duration.ofNanos(slowest)).isLessThan(scenario.livenessHorizon());
            assertThat(result.violations()).isEmpty();
        }
    }

    @Nested
    class ANodeLeavingAndRejoiningMidStampede {

        @Test
        void costsNothingAndIsAccountedFor() {
            // given the bootstrap arm with a node dropped without releasing its claims
            Scenario scenario = HuntScenarios.concurrentBootstrapWithNodeChurn();

            // when every seed the tier declares is run; one crash is a smoke test, and three seeds is the least that
            // makes a verdict about a race worth quoting
            List<ScenarioResult> results =
                    ScenarioRunner.runTier(scenario, Tier.SMOKE, HuntHistories.directory("s15-churn"));
            results.forEach(System.out::println);

            assertThat(results).hasSize(3);
            assertThat(results).allSatisfy(result -> {
                HistoryView history = HistoryView.read(result.history());

                // then the crash must actually have landed, or the arm verified nothing
                assertThat(result.faultFires().get("node-crash"))
                        .as("node-crash fire count for seed %d", result.seed())
                        .isNotNull()
                        .isPositive();
                assertThat(history.notes(HistoryOps.NODE).stream()
                                  .map(note -> note.stringValue(HistoryOps.ACTION))
                                  .toList())
                        .as("node lifecycle for seed %d", result.seed())
                        .contains("crashed", "restarted");

                // and nothing may be lost, whatever the churn did; a repeated delivery is permitted here and counted
                assertThat(result.violations()).as("violations for seed %d: %s", result.seed(), result).isEmpty();
                assertThat(finalSegments(history)).containsExactly(0, 1, 2, 3);
            });
        }
    }

    private static List<Integer> finalSegments(HistoryView history) {
        return history.notes(HistoryOps.INIT_SEGMENTS).stream()
                      .filter(note -> "final".equals(note.key()))
                      .findFirst()
                      .map(note -> note.stringListValue(HistoryOps.SEGMENT).stream().map(Integer::parseInt).toList())
                      .orElse(List.of());
    }

    private static Set<String> nodesThatClaimed(HistoryView history) {
        Set<String> nodes = new LinkedHashSet<>();
        history.operations(HistoryOps.CLAIM)
               .forEach(claim -> nodes.add(String.valueOf(claim.invocation().node())));
        return nodes;
    }

    /**
     * Counts pairs of initialisation attempts from distinct nodes whose call windows overlapped in time.
     * <p>
     * An attempt's window runs from its invocation to its completion, both taken from the recorder's own monotonic
     * clock. A pair that overlaps is two nodes inside the operation at once, which is the race; a pair that does not
     * is two nodes taking turns, which is not.
     */
    private static int overlappingPairs(List<Operation> attempts) {
        int overlapping = 0;
        for (int first = 0; first < attempts.size(); first++) {
            for (int second = first + 1; second < attempts.size(); second++) {
                Operation left = attempts.get(first);
                Operation right = attempts.get(second);
                if (String.valueOf(left.invocation().node()).equals(String.valueOf(right.invocation().node()))) {
                    continue;
                }
                if (overlaps(left, right)) {
                    overlapping++;
                }
            }
        }
        return overlapping;
    }

    private static boolean overlaps(Operation left, Operation right) {
        long leftFrom = left.invocation().logicalTs();
        long leftTo = end(left);
        long rightFrom = right.invocation().logicalTs();
        long rightTo = end(right);
        return Math.max(leftFrom, rightFrom) <= Math.min(leftTo, rightTo);
    }

    private static long end(Operation operation) {
        HistoryRecord completion = operation.completion();
        return completion == null ? Long.MAX_VALUE : completion.logicalTs();
    }
}
