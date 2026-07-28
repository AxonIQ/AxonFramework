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
 * Rewinds a running projection to the start of the stream and asks what came back.
 * <p>
 * A reset is the widest-reaching thing an operator can do to a processor: every segment goes back to the beginning and
 * the whole stream arrives a second time. The framework's promises about it are narrow and worth checking one by one. It
 * refuses a reset on a running processor. It rewrites every known segment's token inside one unit of work. And it marks
 * each redelivered event as a replay, through the token it hands the handler, so that downstream code can tell a replay
 * apart from a duplicate.
 * <p>
 * The last of those is what makes a replay safe to have in a suite that otherwise treats a repeated delivery as a
 * failure: the delivery says for itself that it is a replay, and the delivery oracle licenses it on that basis rather
 * than on a period of time in which anything would have been forgiven.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
class ReplayAfterResetTest {

    @Nested
    class AskingARunningProcessorToRewind {

        @Test
        void isRefusedWithTheStateItIsIn() {
            // given the reset arm, whose fault first asks a running processor to rewind
            Scenario scenario = HuntScenarios.replaySeesFullPrefix();

            // when it is run
            ScenarioResult result =
                    ScenarioRunner.run(scenario, Tier.SMOKE, scenario.seed(), HuntHistories.directory("s8-refusal"));
            HistoryView history = HistoryView.read(result.history());
            List<Operation> attempts = history.operations(HistoryOps.RESET);
            Operation refused = attempts.stream()
                                        .filter(attempt -> "reset-while-running"
                                                .equals(attempt.invocation().stringValue(HistoryOps.ACTION)))
                                        .findFirst()
                                        .orElseThrow(() -> new AssertionError(
                                                "the run recorded no attempt to reset a running processor"));
            System.out.println("S8 refusal: " + refused.completion());

            // then the framework must have refused it, and refused it with the exception its own precondition raises
            assertThat(refused.outcome()).isEqualTo(Outcome.FAIL);
            assertThat(refused.completion()).isNotNull();
            assertThat(refused.completion().error()).isEqualTo(IllegalStateException.class.getName());
            assertThat(refused.completion().stringValue("message"))
                    .isEqualTo("The Processor must be shut down before triggering a reset.");
        }
    }

    @Nested
    class RewindingAStoppedProcessor {

        @Test
        void replaysTheWholeCommittedHistoryAndFlagsEveryRedelivery() {
            // given the reset arm, which stops the cluster before rewinding, as the framework's precondition requires
            Scenario scenario = HuntScenarios.replaySeesFullPrefix();

            // when every seed the smoke tier declares is run
            long startedAt = System.nanoTime();
            List<ScenarioResult> results =
                    ScenarioRunner.runTier(scenario, Tier.SMOKE, HuntHistories.directory("s8-replay"));
            Duration wall = Duration.ofNanos(System.nanoTime() - startedAt);
            results.forEach(System.out::println);
            System.out.println("S8 replay total wall time: " + wall.toMillis() + "ms across " + results.size()
                                       + " seed(s)");

            assertThat(results).hasSize(3);
            assertThat(results).allSatisfy(result -> {
                HistoryView history = HistoryView.read(result.history());
                long tokenAtReset = tokenAtReset(history);
                List<HistoryRecord> replayed = deliveries(history, true);
                List<HistoryRecord> regular = deliveries(history, false);
                System.out.println("S8 replay seed " + result.seed() + ": rewound from position " + tokenAtReset + ", "
                                           + replayed.size() + " replayed and " + regular.size()
                                           + " regular delivery(s), " + distinctEvents(replayed).size()
                                           + " distinct events replayed");

                // then the rewind must really have happened and really have redelivered events, or the arm measured a
                // reset of an empty stream and says nothing about replay at all
                assertThat(tokenAtReset).as("position the reset rewound from, seed %d", result.seed()).isPositive();
                assertThat(replayed).as("replayed deliveries for seed %d", result.seed()).isNotEmpty();

                // and every delivery the framework called a replay must sit at or below the position it rewound from,
                // and every delivery above it must not be called one; that boundary is the whole content of the replay
                // flag
                assertThat(replayed)
                        .as("replay-flagged deliveries above the reset position, seed %d", result.seed())
                        .allSatisfy(record -> assertThat(record.longValue(HistoryOps.POSITION, Long.MAX_VALUE))
                                .isLessThanOrEqualTo(tokenAtReset));

                // and nothing may be found broken: the projection clears itself on reset, so after the replay it must
                // equal the fold of the whole committed history again
                assertThat(result.violations())
                        .as("violations for seed %d: %s", result.seed(), result)
                        .isEmpty();
            });
        }
    }

    @Nested
    class RewindingOneNodeWhileTheOthersKeepProcessing {

        @Test
        void isNotPreventedAndTheRunRecordsWhatItCost() {
            // given the arm that rewinds one node without stopping the rest, which the framework's precondition cannot
            // prevent because it only ever looks at the local virtual machine
            Scenario scenario = HuntScenarios.replaySeesFullPrefixAcrossNodes();

            // when it is run
            ScenarioResult result = ScenarioRunner.run(scenario, Tier.SMOKE, scenario.seed(),
                                                      HuntHistories.directory("s8-cross-node"));
            HistoryView history = HistoryView.read(result.history());
            List<Operation> resets = history.operations(HistoryOps.RESET).stream()
                                           .filter(reset -> "reset".equals(reset.invocation()
                                                                                .stringValue(HistoryOps.ACTION)))
                                           .toList();
            List<HistoryRecord> replayed = deliveries(history, true);
            System.out.println("S8 cross-node rewind: " + resets.size() + " reset(s), outcome "
                                       + resets.stream().map(Operation::outcome).toList() + ", " + replayed.size()
                                       + " replayed delivery(s); verdict " + result.verdict());
            result.violations().forEach(violation -> System.out.println("  " + violation));
            result.notes().forEach(note -> System.out.println("  note: " + note));

            // then the run is recorded and nothing about it is asserted as a guarantee. The framework says a reset needs
            // the processor stopped and checks that on one virtual machine only; what a cluster does when one node
            // rewinds underneath the others is undefined, so this arm measures it. The one thing that is asserted is
            // that the attempt was made, because an arm that quietly did nothing would document nothing
            assertThat(resets).as("resets recorded in %s", result.history()).isNotEmpty();
        }
    }

    private static long tokenAtReset(HistoryView history) {
        return history.operations(HistoryOps.RESET).stream()
                      .filter(reset -> reset.outcome() == Outcome.OK && reset.completion() != null)
                      .mapToLong(reset -> reset.completion().longValue(HistoryOps.TOKEN_AT_RESET, -1L))
                      .max()
                      .orElse(-1L);
    }

    private static List<HistoryRecord> deliveries(HistoryView history, boolean replay) {
        return history.operations(HistoryOps.DELIVER).stream()
                      .map(Operation::invocation)
                      .filter(record -> replay == Boolean.parseBoolean(record.stringValue(HistoryOps.REPLAY)))
                      .toList();
    }

    private static Set<String> distinctEvents(List<HistoryRecord> records) {
        Set<String> events = new LinkedHashSet<>();
        records.forEach(record -> events.add(String.valueOf(record.stringValue("eventId"))));
        return events;
    }
}
