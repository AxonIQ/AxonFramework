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
import org.axonframework.hunt.history.HistoryView;
import org.axonframework.hunt.history.Operation;
import org.axonframework.hunt.history.Outcome;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Splits and merges the segments a running cluster is working through, and asks what fell out.
 * <p>
 * Changing the segment count is a membership change made while events are still arriving: a split hands one segment's
 * work to two, a merge hands two segments' work to one, and a node may be trying to claim the very rows being rewritten.
 * What has to survive it is that every committed event is handled somewhere, that nothing is handled twice outside a
 * recorded handover, and that a key's events do not overtake each other because the segment carrying them changed
 * underneath.
 * <p>
 * <b>The two preconditions the framework states are asserted rather than assumed.</b> A merge is refused on a processor
 * with a single segment, and it is refused by returning {@code false} rather than by failing; a split past the widest
 * possible mask throws. Both are cheap to check and both are the kind of statement that quietly stops being true.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
class SplitAndMergeUnderLoadTest {

    @Nested
    class ThePreconditionsTheFrameworkStates {

        @Test
        void refuseAMergeOnAProcessorWithOneSegment() {
            // given a single-segment run, which is what a processor that has nothing to merge with looks like
            Scenario scenario = HuntScenarios.mergeOnlyOnASingleSegment();

            // when the run merges its only segment
            ScenarioResult result = ScenarioRunner.run(scenario, Tier.SMOKE, scenario.seed(),
                                                      HuntHistories.directory("s9-single-segment-merge"));
            HistoryView history = HistoryView.read(result.history());
            List<Operation> merges = history.operations(HistoryOps.MERGE);
            System.out.println("S9 single-segment merge: " + merges.size() + " merge instruction(s), outcomes "
                                       + merges.stream().map(Operation::outcome).toList() + ", carried out "
                                       + merges.stream()
                                               .map(merge -> merge.completion() == null
                                                       ? "?"
                                                       : merge.completion().stringValue("carriedOut"))
                                               .toList());

            // then the framework must have answered, and answered no. It refuses by declining rather than by failing,
            // which is a different thing and the thing the contract says
            assertThat(merges).isNotEmpty();
            assertThat(merges).allSatisfy(merge -> {
                assertThat(merge.outcome()).isEqualTo(Outcome.OK);
                assertThat(merge.completion()).isNotNull();
                assertThat(merge.completion().stringValue("carriedOut")).isEqualTo("false");
            });
        }

        @Test
        void refuseASplitPastTheWidestMask() {
            // given a segment whose mask is already the widest one that fits
            Segment widest = new Segment(0, Integer.MAX_VALUE);

            // when it is split
            // then the framework refuses, and says why
            assertThatThrownBy(widest::split)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Unable to split the given segmentId, as the mask exceeds the max mask size.");
        }
    }

    @Nested
    class ASplitAndMergeStormWhileTheWorkloadWrites {

        @Test
        void losesNothingAndKeepsEveryKeyInOrder() {
            // given the storm arm, whose per-node segment cap has headroom for the segments a split creates
            Scenario scenario = HuntScenarios.splitMergeUnderLoad();

            // when every seed the smoke tier declares is run
            long startedAt = System.nanoTime();
            List<ScenarioResult> results =
                    ScenarioRunner.runTier(scenario, Tier.SMOKE, HuntHistories.directory("s9-storm"));
            Duration wall = Duration.ofNanos(System.nanoTime() - startedAt);
            results.forEach(System.out::println);
            System.out.println("S9 storm total wall time: " + wall.toMillis() + "ms across " + results.size()
                                       + " seed(s)");

            assertThat(results).hasSize(3);
            Set<Integer> segmentsBeyondTheOriginalSet = new LinkedHashSet<>();
            assertThat(results).allSatisfy(result -> {
                HistoryView history = HistoryView.read(result.history());
                segmentsSeen(history).stream()
                                     .filter(segment -> segment >= scenario.segments())
                                     .forEach(segmentsBeyondTheOriginalSet::add);
                long splits = carriedOut(history, HistoryOps.SPLIT);
                long merges = carriedOut(history, HistoryOps.MERGE);
                Set<Integer> segments = segmentsSeen(history);
                System.out.println("S9 storm seed " + result.seed() + ": " + splits + " split(s) and " + merges
                                           + " merge(s) carried out, deliveries came from segments " + segments);

                // then the storm must really have changed the segment count, or the arm ran an ordinary cluster
                assertThat(result.faultFires().get("segment-split-merge"))
                        .as("segment-split-merge fire count for seed %d", result.seed())
                        .isNotNull()
                        .isPositive();
                assertThat(splits).as("splits carried out for seed %d", result.seed()).isPositive();
                // and nothing may be lost, nothing doubled outside a handover, and no key reordered
                assertThat(result.violations())
                        .as("violations for seed %d: %s", result.seed(), result)
                        .isEmpty();
            });

            // and, across the tier, events must have been handled under a segment that only exists after a split. Per
            // seed that is a race -- the storm merges the segment back within the period, so a split whose children get
            // no traffic before the merge is a legitimate outcome -- but a tier in which it never happens once has not
            // exercised a split-created segment at all.
            System.out.println("S9 storm: deliveries from split-created segments " + segmentsBeyondTheOriginalSet);
            assertThat(segmentsBeyondTheOriginalSet)
                    .as("segments beyond the original set that delivered anything, across the tier")
                    .isNotEmpty();
        }
    }

    private static long carriedOut(HistoryView history, String op) {
        return history.operations(op).stream()
                      .filter(instruction -> instruction.completion() != null
                              && "true".equals(instruction.completion().stringValue("carriedOut")))
                      .count();
    }

    private static Set<Integer> segmentsSeen(HistoryView history) {
        Set<Integer> segments = new LinkedHashSet<>();
        for (Operation delivery : history.operations(HistoryOps.DELIVER)) {
            Object raw = delivery.invocation().value().get(HistoryOps.SEGMENT);
            if (raw instanceof Number number) {
                segments.add(number.intValue());
            }
        }
        return segments;
    }
}
