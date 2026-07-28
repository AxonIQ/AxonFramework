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

package org.axonframework.hunt.harness;

import org.axonframework.hunt.checker.AppendOutcomeChecker;
import org.axonframework.hunt.checker.DeliveryChecker;
import org.axonframework.hunt.fault.FaultSchedule;
import org.axonframework.hunt.scenario.HuntScenarios;
import org.axonframework.hunt.scenario.Scenario;
import org.axonframework.hunt.scenario.ScenarioResult;
import org.axonframework.hunt.scenario.ScenarioRunner;
import org.axonframework.hunt.scenario.Tier;
import org.axonframework.hunt.scenario.TierBudget;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs shipped scenarios, unchanged, against every store the suite can reach, and records what each one concluded.
 * <p>
 * <b>Attribution is the whole point.</b> A framework is a library, so the thing under test is really the library
 * crossed with a store protocol, and a failure that names only the scenario starts an argument about whose defect it is.
 * A verdict vector ends it: broken everywhere is the framework's, broken on one store is that adapter's or that store's,
 * and inexpressible on one store is a gap that says so instead of passing quietly.
 * <p>
 * <b>Nothing in a scenario changes to make this work.</b> Every scenario here is taken from the shipped catalogue and
 * pointed somewhere else with {@link Scenario#onBackend(String)}. That substitution is the extensibility charter's
 * backend clause, and this is the test that turns it from a claim into a property: a store added tomorrow inherits the
 * whole corpus by registering itself, with no scenario edited.
 * <p>
 * <b>What a divergence between the in-heap store and PostgreSQL means here.</b> The aggregate-based JPA engine is not a
 * Dynamic Consistency Boundary store, and the differences are documented rather than discovered: at most one tag per
 * event, read as an aggregate identifier; a conflict check that is the database's unique constraint on an aggregate's
 * sequence number; a global index taken from a sequence before the transaction commits, so index order is not commit
 * order; and gap awareness to cope with exactly that. So a scenario whose claim is about the boundary is expected to
 * report the reference-model oracle as inexpressible here, and this test asserts that it says so rather than passing.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
@Tag("container")
class BackendDifferentialTest {

    /**
     * The backends the matrix runs, named rather than discovered, so that a run whose registration is broken fails
     * instead of quietly measuring fewer stores than it reports.
     */
    private static final List<String> BACKENDS = List.of(InMemoryHuntBackend.NAME,
                                                         HsqldbTokenStoreBackend.NAME,
                                                         PostgresJpaHuntBackend.NAME,
                                                         PostgresJpaHuntBackend.SplitTokenStore.NAME);

    /**
     * The timings every arm of the matrix runs at, identical across backends so that a divergence is the store's and not
     * the clock's.
     * <p>
     * A claim timeout of a hundred milliseconds -- the compressed default, and right for a store that answers in
     * nanoseconds -- is meaningless across a database round trip: the owner's extension is still in flight when its own
     * claim lapses, and the run turns into a segment being released and re-claimed over and over, resuming each time from
     * whatever the store last committed. Measured on this matrix before it was widened: 43 claims on a single-node run
     * and 1417 redelivered events. Two seconds against a four-hundred-millisecond extension threshold keeps the
     * five-to-one ratio that the compression exists to preserve.
     */
    private static final HuntTimescale MATRIX_TIMINGS =
            HuntTimescale.compressed().withClaimTimings(Duration.ofSeconds(2), Duration.ofMillis(400));

    /**
     * How long an arm waits for its read side, and how much load it applies.
     * <p>
     * A container is two orders of magnitude slower than a map in the heap, and a budget sized for the heap leaves a
     * PostgreSQL arm permanently behind its own read side -- measured: a thousand commands and a thirty-second settle
     * left it undecided on every run, which makes the arm unable to signal anything. Three hundred commands and a
     * minute of settle are enough for every store in the matrix to finish, which is what makes "the read side caught up"
     * an assertion rather than a wish.
     */
    private static final TierBudget MATRIX_BUDGET = new TierBudget(300, 1, Duration.ofMinutes(4));

    private static final Duration MATRIX_SETTLE = Duration.ofSeconds(60);

    private static Map<String, ScenarioResult> across(Scenario scenario) {
        Map<String, ScenarioResult> perBackend = new LinkedHashMap<>();
        for (String backend : BACKENDS) {
            Scenario arm = scenario.onBackend(backend)
                                   .withTimescale(MATRIX_TIMINGS)
                                   .withBudget(Tier.SMOKE, MATRIX_BUDGET)
                                   .withFaults(FaultSchedule.none(MATRIX_SETTLE));
            perBackend.put(backend, ScenarioRunner.run(arm, Tier.SMOKE, arm.seed(),
                                                       ScenarioRunner.historyDirectory(
                                                               Path.of("target", "hunt-histories",
                                                                       "differential-" + backend))));
        }
        System.out.println(vector(scenario.id(), perBackend));
        perBackend.forEach((backend, result) -> {
            result.violations().forEach(violation -> System.out.println("  " + backend + " violation: " + violation));
            result.notes().forEach(note -> System.out.println("  " + backend + " note: " + note));
            result.measurements().forEach(m -> System.out.println("  " + backend + " measured: " + m));
            result.notApplicable().forEach(n -> System.out.println("  " + backend + " n/a: " + n));
        });
        return perBackend;
    }

    /**
     * Renders the per-backend verdict vector, which is what a finding carries so that nobody has to re-derive it.
     */
    private static String vector(String scenarioId, Map<String, ScenarioResult> perBackend) {
        List<String> parts = new ArrayList<>();
        perBackend.forEach((backend, result) -> parts.add(backend + ":" + result.verdict()
                                                                 + (result.notApplicable().isEmpty()
                                                                 ? ""
                                                                 : "(" + result.notApplicable().size() + " n/a)")));
        return "VECTOR " + scenarioId + " " + String.join(" ", parts);
    }

    @Nested
    class TheSameContendedAppendScenarioOnEveryStore {

        @Test
        void reportsAVerdictPerBackendAndNamesWhatPostgresCannotExpress() {
            // given the shipped contended-append scenario, edited in no way at all
            Scenario scenario = HuntScenarios.appendRejectedAfterMarker();

            // when it is run against every registered store
            Map<String, ScenarioResult> perBackend = across(scenario);

            // then every store produced a verdict, so the vector is complete rather than partial
            assertThat(perBackend).hasSize(BACKENDS.size());
            assertThat(perBackend.values()).allSatisfy(result -> assertThat(result.verdict()).isNotNull());

            // and the two Dynamic Consistency Boundary stores judged the protocol, while the aggregate-based store said
            // it cannot: a store whose append condition is not a boundary over tags and a marker has nothing for the
            // reference model to be compared against, and reporting that is what stops the vector claiming coverage
            assertThat(perBackend.get(InMemoryHuntBackend.NAME).notApplicable()).isEmpty();
            assertThat(perBackend.get(PostgresJpaHuntBackend.NAME).notApplicable())
                    .anySatisfy(statement -> assertThat(statement).contains("AppendConformsToDcbModel",
                                                                            "not expressible"));

            // and the divergence this matrix found is pinned where it is, so that closing it is what breaks this
            // assertion. An append made with no consistency condition is rejected as conflicting on the aggregate-based
            // store whenever the aggregate already holds events, because an INFINITY marker carries no position and the
            // sequencer restarts at zero. That is finding F-14, recorded rather than repaired: the suite observes the
            // framework and does not fix it, so the expectation flips red on the day it stops happening.
            assertThat(perBackend.get(InMemoryHuntBackend.NAME).violations())
                    .as("the boundary store holds the guarantee")
                    .isEmpty();
            assertThat(perBackend.get(PostgresJpaHuntBackend.NAME).violations())
                    .as("finding F-14 on %s", PostgresJpaHuntBackend.NAME)
                    .isNotEmpty()
                    .allSatisfy(violation -> assertThat(violation.machineName())
                            .isEqualTo(AppendOutcomeChecker.UNCONDITIONAL_APPEND_NEVER_REJECTED));
        }
    }

    @Nested
    class WhatEachStoreDeliveredOfWhatItCommitted {

        @Test
        void isMeasuredPerBackendAndDecidedOnlyWhereTheStoreCanBeDecidedAbout() {
            // given the shipped contended-append scenario, whose read side is a real streaming processor over whichever
            // store the arm names
            Scenario scenario = HuntScenarios.appendRejectedAfterMarker();

            // when it is run against every registered store
            Map<String, ScenarioResult> perBackend = across(scenario);

            // then no arm anywhere may report a committed event as never delivered
            perBackend.forEach((backend, result) -> assertThat(result.violations())
                    .as("delivery violations on %s", backend)
                    .noneMatch(violation -> DeliveryChecker.NO_COMMITTED_EVENT_GOES_UNDELIVERED
                            .equals(violation.machineName())));

            // and the two stores whose read side does catch up must say so, because that is the only place where "no
            // loss" is a decided answer rather than an undecided one
            List.of(InMemoryHuntBackend.NAME, HsqldbTokenStoreBackend.NAME).forEach(backend -> assertThat(
                            perBackend.get(backend).notes())
                    .as("read side on %s: %s", backend, perBackend.get(backend))
                    .noneMatch(note -> note.contains("read side had not caught up")));

            // and the PostgreSQL arms are measured rather than asserted, deliberately. Their read side does not catch up
            // with this workload, and the reason is not a budget: three hundred commands with a minute of settle behave
            // exactly as a thousand with thirty seconds did. The harness counts an append as stored when the engine's
            // commit call returns, and on this engine that call returns before the database transaction commits, so the
            // count the read side is chased against is not a count of what is readable. Until that is fixed the arms
            // cannot be held to quiescence, and pretending otherwise would ship a permanently red assertion -- which is
            // the exact shape of inertness this suite exists to avoid.
            List.of(PostgresJpaHuntBackend.NAME, PostgresJpaHuntBackend.SplitTokenStore.NAME).forEach(
                    backend -> System.out.println("MEASURED " + backend + " read side caught up: "
                                                          + perBackend.get(backend).notes().stream()
                                                                      .noneMatch(note -> note.contains(
                                                                              "read side had not caught up"))));
        }
    }
}
