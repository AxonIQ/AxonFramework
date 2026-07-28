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
import org.axonframework.hunt.model.DcbHistoryCodec;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kills a transaction in each of the three phases the framework drives it through, one arm at a time.
 * <p>
 * The three arms are separate runs with separate verdicts on purpose. They leave different traces and only one of
 * them exercises rollback at all, so folding them into a single result would make it impossible to say which phase a
 * finding came from.
 * <p>
 * The oracle is the same for all three and is not written here: {@code VisibilityChecker} runs against every history
 * the suite records and judges, per event identifier, that no delivery precedes the commit of the transaction that
 * appended it and that nothing a transaction rolled back is delivered or left in the store. What each arm adds is the
 * evidence that it reached the situation it claims to test.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
class TransactionPhaseFailureTest {

    /**
     * Runs every seed the arm's smoke budget declares and returns the last of them.
     * <p>
     * All of them are asserted clean; the last is handed back for the arm's own evidence assertions, which are about
     * the shape of a run rather than about which seed produced it.
     */
    private static ScenarioResult run(Scenario scenario, String directory) {
        long startedAt = System.nanoTime();
        List<ScenarioResult> results = ScenarioRunner.runTier(scenario, Tier.SMOKE, HuntHistories.directory(directory));
        results.forEach(System.out::println);
        System.out.println(scenario.id() + " wall time: "
                                   + Duration.ofNanos(System.nanoTime() - startedAt).toMillis() + "ms across "
                                   + results.size() + " seed(s)");
        assertThat(results).allSatisfy(result -> assertThat(result.violations())
                .as("violations for seed %d: %s", result.seed(), result)
                .isEmpty());
        return results.getLast();
    }

    private static Set<String> rolledBackEventIds(HistoryView history) {
        return history.operations(HistoryOps.ROLLBACK).stream()
                      .flatMap(rollback -> rollback.invocation()
                                                   .stringListValue(DcbHistoryCodec.EVENT_IDS).stream())
                      .collect(java.util.stream.Collectors.toSet());
    }

    private static Set<String> deliveredEventIds(HistoryView history) {
        return history.operations(HistoryOps.DELIVER).stream()
                      .map(delivery -> delivery.invocation().stringValue(DcbHistoryCodec.EVENT_ID))
                      .filter(java.util.Objects::nonNull)
                      .collect(java.util.stream.Collectors.toSet());
    }

    private static List<String> scannedEventIds(HistoryView history) {
        List<HistoryRecord> scans = history.notes(HistoryOps.SCAN);
        return scans.isEmpty() ? List.of() : scans.getLast().stringListValue(DcbHistoryCodec.EVENT_IDS);
    }

    @Nested
    class AFailureWhileEventsAreHandedToTheStore {

        @Test
        void leavesNothingVisibleAndNothingToRollBack() {
            // given the arm that fails the append itself
            Scenario scenario = HuntScenarios.uncommittedNeverVisibleAtPrepareCommit();

            // when
            ScenarioResult result = run(scenario, "s3-prepare-commit");

            // then the fault landed, nothing was found broken, and the failed appends left nothing behind
            assertThat(result.faultFires()).hasEntrySatisfying("prepare-commit-failure",
                                                               fires -> assertThat(fires).isPositive());
            assertThat(result.violations()).as("violations: %s", result).isEmpty();
            assertThat(result.verdict()).isEqualTo(Verdict.PASS);

            HistoryView history = HistoryView.read(result.history());
            long failedAppends = history.operations(HistoryOps.APPEND).stream()
                                        .filter(append -> append.outcome()
                                                == org.axonframework.hunt.history.Outcome.FAIL)
                                        .count();
            assertThat(failedAppends).as("appends killed before reaching the store").isPositive();
            // The framework registers its rollback handler only once the append has returned a transaction, so an
            // append that never got that far has nothing to roll back and records no rollback of its own.
            assertThat(history.operations(HistoryOps.ROLLBACK)).isEmpty();
        }
    }

    @Nested
    class AFailureAtTheMomentOfCommit {

        @Test
        void rollsTheBatchBackAndKeepsItOutOfEveryDeliveryAndTheStore() {
            // given the arm that refuses the commit
            Scenario scenario = HuntScenarios.uncommittedNeverVisibleAtCommit();

            // when
            ScenarioResult result = run(scenario, "s3-commit");

            // then
            assertThat(result.faultFires()).hasEntrySatisfying("append-rejection",
                                                               fires -> assertThat(fires).isPositive());
            assertThat(result.violations()).as("violations: %s", result).isEmpty();
            assertThat(result.verdict()).isEqualTo(Verdict.PASS);

            HistoryView history = HistoryView.read(result.history());
            Set<String> rolledBack = rolledBackEventIds(history);
            assertThat(rolledBack).as("this arm must actually exercise rollback").isNotEmpty();
            assertThat(deliveredEventIds(history)).doesNotContainAnyElementsOf(rolledBack);
            assertThat(scannedEventIds(history)).doesNotContainAnyElementsOf(rolledBack);
        }
    }

    @Nested
    class AFailureAfterTheCommitHasPublishedTheBatch {

        @Test
        void keepsTheCommittedEventsObservableAndRollsNothingBack() {
            // given the arm that fails the marker calculation, strictly after the point of no return
            Scenario scenario = HuntScenarios.uncommittedNeverVisibleAfterCommit();

            // when
            ScenarioResult result = run(scenario, "s3-after-commit");

            // then
            assertThat(result.faultFires()).hasEntrySatisfying("after-commit-failure",
                                                               fires -> assertThat(fires).isPositive());
            assertThat(result.violations()).as("violations: %s", result).isEmpty();
            assertThat(result.verdict()).isEqualTo(Verdict.PASS);

            HistoryView history = HistoryView.read(result.history());
            List<Operation> rollbacks = history.operations(HistoryOps.ROLLBACK);
            // The framework's per-transaction error handler calls rollback whatever phase the error arrived in, so a
            // rollback is requested here even though the batch is already durable.
            assertThat(rollbacks).as("the framework must have asked for a rollback").isNotEmpty();
            assertThat(rollbacks).anyMatch(rollback -> Boolean.TRUE.equals(
                    rollback.invocation().value().get("afterCommit")));
            // Nothing was actually discarded: those events were committed, so they stay in the store and stay
            // deliverable, and no oracle may report them as rolled back.
            assertThat(rolledBackEventIds(history)).isEmpty();
            assertThat(scannedEventIds(history)).isNotEmpty();
        }
    }
}
