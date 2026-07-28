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
import org.axonframework.hunt.workload.BatchWorkload;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Documents what a reader reading at maximum rate sees of a hundred-event batch that is still being committed.
 * <p>
 * The in-heap storage engine inserts a batch one event at a time while holding its append lock, and a reader opens a
 * stream and walks the storage map without taking that lock. A read that starts in the middle of a commit therefore
 * ends at whatever position the writer had reached, which is a strict prefix of the batch. That is the current
 * behaviour, and this class asserts it rather than wishing it away, because the suite observes the framework and does
 * not patch it.
 * <p>
 * <b>This test is expected to flip red the day batch visibility becomes atomic.</b> That flip is the signal to
 * re-evaluate finding F-3 in {@code formal/FINDINGS.adoc} and to turn the assertion around: a store that publishes a
 * batch under a single visible-position advance would make the observation below impossible, and the suite should
 * then require it to be impossible.
 * <p>
 * The neighbouring guarantee is asserted alongside, so the arm cannot be read as claiming more than it shows. Nothing
 * is delivered before its commit, and nothing a rejected append offered survives in the store; what is weaker than a
 * reader might assume is only the atomicity of a batch's arrival.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
class PartialBatchVisibilityTest {

    @Nested
    class AReaderReadingWhileAWideBatchIsCommitting {

        @Test
        void observesBatchesInPartAndTheStoreStillHoldsThemWhole() {
            // given one writer committing hundred-event batches and one reader reading the store as fast as it can,
            // over every seed the smoke tier declares
            Scenario scenario = HuntScenarios.partialBatchVisibility();

            // when
            long startedAt = System.nanoTime();
            List<ScenarioResult> results =
                    ScenarioRunner.runTier(scenario, Tier.SMOKE, HuntHistories.directory("s16"));
            results.forEach(System.out::println);

            long polls = 0;
            List<HistoryRecord> torn = new ArrayList<>();
            for (ScenarioResult result : results) {
                HistoryView history = HistoryView.read(result.history());
                polls += history.notes(HistoryOps.PROJECTION).getLast()
                                .longValue(BatchWorkload.POLLS, 0L);
                torn.addAll(history.notes(HistoryOps.POLL));
            }
            System.out.println("polls=" + polls + " torn observations=" + torn.size()
                                       + " across " + results.size() + " seed(s) wall="
                                       + Duration.ofNanos(System.nanoTime() - startedAt).toMillis() + "ms");
            System.out.println("first torn observations: "
                                       + torn.stream().limit(3).map(HistoryRecord::value).toList());

            // then the reader must actually have read while the writer was writing, or the arm proves nothing
            assertThat(polls).as("reads completed while the writer was committing").isPositive();

            // then a batch is observable in part. This asserts the CURRENT behaviour and is documented to flip red
            // when batch visibility becomes atomic; see finding F-3.
            assertThat(torn)
                    .as("reads in which a batch was visible in part but not in whole (finding F-3); an empty result "
                                + "means the gap is closed and this assertion must be turned around")
                    .isNotEmpty();

            // and the guarantee that is actually documented still holds: nothing is seen before it is committed
            assertThat(results).allSatisfy(result -> assertThat(result.violations())
                    .as("violations for seed %d: %s", result.seed(), result)
                    .isEmpty());
        }
    }

    @Nested
    class TheStoreOnceTheRunHasQuiesced {

        @Test
        void holdsEveryBatchWholeSoTheTearIsOnlyEverTransient() {
            // given the history the arm above recorded
            Scenario scenario = HuntScenarios.partialBatchVisibility();
            ScenarioResult result = ScenarioRunner.run(scenario, Tier.SMOKE, scenario.seed() + 10,
                                                       HuntHistories.directory("s16-quiesced"));
            System.out.println(result);

            // when the authoritative scan is folded per batch
            HistoryView history = HistoryView.read(result.history());
            List<String> stored = history.notes(HistoryOps.SCAN).getLast()
                                         .stringListValue(org.axonframework.hunt.model.DcbHistoryCodec.EVENT_IDS);
            java.util.Map<String, Integer> perBatch = new java.util.TreeMap<>();
            stored.forEach(eventId -> perBatch.merge(eventId.substring(0, eventId.indexOf("-e")), 1, Integer::sum));

            // then every batch the store holds, it holds whole: the partial view is a window, not a loss
            assertThat(perBatch).as("events per batch in the authoritative scan").isNotEmpty();
            assertThat(perBatch.values()).allSatisfy(count -> assertThat(count).isEqualTo(100));
        }
    }
}
