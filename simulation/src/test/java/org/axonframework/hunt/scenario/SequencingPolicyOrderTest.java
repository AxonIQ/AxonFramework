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

import org.axonframework.hunt.checker.OrderChecker;
import org.axonframework.hunt.history.HistoryOps;
import org.axonframework.hunt.history.HistoryRecord;
import org.axonframework.hunt.history.HistoryView;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What each sequencing policy actually does on a store that speaks the Dynamic Consistency Boundary protocol.
 * <p>
 * The arm that matters most is the one nobody chooses: the policy the framework wires when the application says
 * nothing. On a DCB-native store its per-aggregate half can never resolve anything, so the fallback wins for every
 * event and the whole stream shares one sequence identifier. That has two consequences worth pinning, and both are
 * asserted here: the read side is strictly ordered, and the segments and workers configured alongside it buy nothing,
 * because one identifier hashes to one segment.
 * <p>
 * The arm on the aggregate-based store, where the aggregate identifier <em>is</em> populated and the same
 * configuration therefore behaves differently, cannot be written yet: this build ships no such backend. It is the
 * other half of the differential and is left for the phase that adds one.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
class SequencingPolicyOrderTest {

    private static ScenarioResult run(Scenario scenario, String directory) {
        long startedAt = System.nanoTime();
        ScenarioResult result = ScenarioRunner.run(scenario, Tier.SMOKE, scenario.seed(),
                                                   HuntHistories.directory(directory));
        System.out.println(result);
        System.out.println(scenario.id() + " wall time: "
                                   + Duration.ofNanos(System.nanoTime() - startedAt).toMillis() + "ms");
        return result;
    }

    private static Set<String> resolvedSequenceKeys(HistoryView history) {
        return history.operations(HistoryOps.DELIVER).stream()
                      .map(delivery -> delivery.invocation().stringValue(HistoryOps.SEQUENCE_KEY))
                      .filter(java.util.Objects::nonNull)
                      .collect(Collectors.toSet());
    }

    private static HistoryRecord projectionOf(HistoryView history) {
        List<HistoryRecord> projections = history.notes(HistoryOps.PROJECTION);
        assertThat(projections).as("the run must record its read-model state").isNotEmpty();
        return projections.getLast();
    }

    @Nested
    class TheSequencingPolicyTheFrameworkWires {

        @Test
        void collapsesEveryEventOntoOneIdentifierAndDeliversThemInAppendOrder() {
            // given the wired default on a store that populates no aggregate identifier
            Scenario scenario = HuntScenarios.sequencingPolicyOrderWiredDefault();

            // when
            ScenarioResult result = run(scenario, "s10-wired-default");

            // then the ordering oracle holds, and it holds for the reason the arm claims
            assertThat(result.violations()).as("violations: %s", result).isEmpty();
            assertThat(result.verdict()).isEqualTo(Verdict.PASS);

            HistoryView history = HistoryView.read(result.history());
            HistoryRecord projection = projectionOf(history);
            assertThat(projection.longValue("deliveredEvents", 0L)).isPositive();
            assertThat(projection.longValue(
                    org.axonframework.hunt.workload.SequencedWorkload.UNRESOLVED_KEYS, -1L)).isZero();
            // One identifier for the whole stream: the per-aggregate half of the wired default resolves nothing on
            // this store, so its fallback answers for every event.
            assertThat(resolvedSequenceKeys(history))
                    .as("distinct sequence identifiers across six independent streams")
                    .hasSize(1);
        }
    }

    @Nested
    class AnExplicitNoOpSequencingPolicy {

        @Test
        void cannotResolveAnIdentifierAtAllAndStopsTheReadSide() {
            // given a policy documented as imposing no sequencing, chosen explicitly
            Scenario scenario = HuntScenarios.sequencingPolicyOrderNoOp();

            // when
            ScenarioResult result = run(scenario, "s10-no-op");

            // then nothing is delivered, because resolving the identifier throws rather than falling back
            HistoryView history = HistoryView.read(result.history());
            assertThat(projectionOf(history).longValue("deliveredEvents", -1L)).isZero();
            assertThat(projectionOf(history).longValue(
                    org.axonframework.hunt.workload.SequencedWorkload.UNRESOLVED_KEYS, 0L)).isPositive();
            assertThat(history.notes(HistoryOps.SEQUENCE))
                    .as("the failed resolution must be on the record")
                    .isNotEmpty();
            assertThat(history.notes(HistoryOps.SEQUENCE).getFirst().stringValue("error"))
                    .isEqualTo(java.util.NoSuchElementException.class.getName());
            // Nothing was found broken by any oracle: an unreachable read side is not an ordering violation, and the
            // run says so by being undecided rather than by passing.
            assertThat(result.violations()).as("violations: %s", result).isEmpty();
            assertThat(result.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
        }
    }

    @Nested
    class ThePerAggregatePolicyOnItsOwn {

        @Test
        void cannotResolveAnIdentifierOnAStoreThatPopulatesNoAggregateIdentifier() {
            // given the per-aggregate policy with nothing behind it to fall back to
            Scenario scenario = HuntScenarios.sequencingPolicyOrderPerAggregate();

            // when
            ScenarioResult result = run(scenario, "s10-per-aggregate");

            // then the outcome is the same as for the no-op policy, and for the same reason
            HistoryView history = HistoryView.read(result.history());
            assertThat(projectionOf(history).longValue("deliveredEvents", -1L)).isZero();
            assertThat(history.notes(HistoryOps.SEQUENCE).getFirst().stringValue("error"))
                    .isEqualTo(java.util.NoSuchElementException.class.getName());
            assertThat(result.violations()).as("violations: %s", result).isEmpty();
            assertThat(result.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
        }
    }

    @Nested
    class TheOrderingOracle {

        @Test
        void isRegisteredSoEveryRunIsJudgedByIt() {
            // given the registered checker set
            Set<String> registered = org.axonframework.hunt.checker.CheckerRegistry.discover().stream()
                                                                                   .flatMap(checker -> checker
                                                                                           .machineNames().stream())
                                                                                   .collect(Collectors.toSet());

            // then
            assertThat(registered).contains(OrderChecker.SEQUENCE_KEY_ORDER_PRESERVED);
        }
    }
}
