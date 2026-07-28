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
import org.axonframework.hunt.checker.OwnershipChecker;
import org.axonframework.hunt.history.HistoryOps;
import org.axonframework.hunt.history.HistoryView;
import org.axonframework.hunt.scenario.HuntScenarios;
import org.axonframework.hunt.scenario.Scenario;
import org.axonframework.hunt.scenario.ScenarioResult;
import org.axonframework.hunt.scenario.ScenarioRunner;
import org.axonframework.hunt.scenario.Tier;
import org.axonframework.hunt.scenario.TierBudget;
import org.axonframework.hunt.scenario.Verdict;
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
 * <b>Several scenarios and several seeds, because one of each is one interleaving.</b> A matrix of one scenario at one
 * seed says what that scenario did once on each store and nothing more; a scenario that would only diverge on a different
 * shape of history, or on a different race, could not surface in it. Every arm therefore runs every seed its tier
 * declares, and the matrix carries a scenario from each family the corpus has: contended conditioned appends, a
 * deterministic single-writer arm, a reader racing a committing batch, a transaction failed at the moment of commit, and
 * a cluster booting into an empty token store.
 * <p>
 * <b>A store that cannot express an invariant says so, and that is recorded rather than passed.</b> The framework's
 * in-heap token store has no owner, no timestamp and no expiry, so every claim assertion made against it holds vacuously
 * -- which reads as coverage and is worse than nothing. The bootstrap arm is in the matrix precisely to demonstrate that
 * the vector reports it as not expressible on the stores that arbitrate no claims, and as decided on the two that do.
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
     * How long an arm waits for its read side, how much load it applies, and how many seeds it runs.
     * <p>
     * A container is two orders of magnitude slower than a map in the heap, and a budget sized for the heap leaves a
     * PostgreSQL arm permanently behind its own read side -- measured: a thousand commands and a thirty-second settle
     * left it undecided on every run, which makes the arm unable to signal anything. Three hundred commands and a
     * minute of settle are enough for every store in the matrix to finish, which is what makes "the read side caught up"
     * an assertion rather than a wish.
     * <p>
     * Two seeds rather than one, because a single seed is a single interleaving and the suite's own weak-oracle rules cap
     * a one-seed arm at a partial verdict however clean it is.
     */
    private static final TierBudget MATRIX_BUDGET = new TierBudget(300, 2, Duration.ofMinutes(4));

    private static final Duration MATRIX_SETTLE = Duration.ofSeconds(60);

    /**
     * The whole matrix, run once for the class rather than once per assertion.
     * <p>
     * Every arm is a container-backed run of tens of seconds, and the assertions below read different facts off the same
     * runs. Running the matrix per test would double its cost and, worse, would compare assertions made about two
     * different sets of runs.
     */
    private static final Map<String, Map<String, List<ScenarioResult>>> MATRIX = new LinkedHashMap<>();

    /**
     * The scenarios the matrix runs, one from each family the corpus has.
     * <p>
     * Named rather than taken wholesale from the catalogue: the corpus includes arms whose whole purpose is to observe a
     * refusal or a documented gap, and folding those into a cross-store differential would compare the shapes of two
     * expected failures rather than the stores.
     */
    private static List<Scenario> scenarios() {
        return List.of(HuntScenarios.appendRejectedAfterMarker(),
                       HuntScenarios.appendRejectedAfterMarkerSingleWriter(),
                       HuntScenarios.partialBatchVisibility(),
                       HuntScenarios.uncommittedNeverVisibleAtCommit(),
                       HuntScenarios.concurrentBootstrap());
    }

    private static Map<String, List<ScenarioResult>> across(Scenario scenario) {
        return MATRIX.computeIfAbsent(scenario.id(), id -> {
            Map<String, List<ScenarioResult>> perBackend = new LinkedHashMap<>();
            for (String backend : BACKENDS) {
                Scenario arm = scenario.onBackend(backend)
                                       .withTimescale(MATRIX_TIMINGS)
                                       .withBudget(Tier.SMOKE, MATRIX_BUDGET);
                // A scenario that declares its own faults keeps them: the matrix compares stores, and taking a
                // scenario's fault away would compare a different experiment. Only a fault-free scenario has its settle
                // window restated, which is what a container needs.
                if (arm.faults().declaredFaults().isEmpty()) {
                    arm = arm.withFaults(org.axonframework.hunt.fault.FaultSchedule.none(MATRIX_SETTLE));
                }
                List<ScenarioResult> perSeed = new ArrayList<>();
                for (long seed : arm.seeds(Tier.SMOKE)) {
                    perSeed.add(ScenarioRunner.run(arm, Tier.SMOKE, seed,
                                                   ScenarioRunner.historyDirectory(
                                                           Path.of("target", "hunt-histories",
                                                                   "differential-" + backend))));
                }
                perBackend.put(backend, List.copyOf(perSeed));
            }
            System.out.println(vector(id, perBackend));
            perBackend.forEach((backend, results) -> results.forEach(result -> {
                result.violations().forEach(violation -> System.out.println("  " + backend + " violation: "
                                                                                    + violation));
                result.notes().forEach(note -> System.out.println("  " + backend + " note: " + note));
                result.measurements().forEach(m -> System.out.println("  " + backend + " measured: " + m));
                result.notApplicable().forEach(n -> System.out.println("  " + backend + " n/a: " + n));
            }));
            return perBackend;
        });
    }

    /**
     * Renders the per-backend verdict vector, one entry per seed, which is what a finding carries so that nobody has to
     * re-derive it.
     */
    private static String vector(String scenarioId, Map<String, List<ScenarioResult>> perBackend) {
        List<String> parts = new ArrayList<>();
        perBackend.forEach((backend, results) -> parts.add(
                backend + ":" + results.stream().map(result -> result.verdict()
                        + (result.notApplicable().isEmpty() ? "" : "(" + result.notApplicable().size() + " n/a)"))
                                       .map(String::valueOf)
                                       .reduce((first, second) -> first + "/" + second)
                                       .orElse("none")));
        return "VECTOR " + scenarioId + " " + String.join(" ", parts);
    }

    private static List<ScenarioResult> allResults(Map<String, List<ScenarioResult>> perBackend) {
        return perBackend.values().stream().flatMap(List::stream).toList();
    }

    @Nested
    class EveryScenarioOfTheMatrixOnEveryStore {

        @Test
        void producesAVerdictPerSeedPerBackendWithNoArmLeftUnrun() {
            // given a scenario from each family the corpus has, edited in no way at all
            List<Scenario> scenarios = scenarios();

            // when every one of them is run against every registered store, at every seed its tier declares
            Map<String, Map<String, List<ScenarioResult>>> matrix = new LinkedHashMap<>();
            scenarios.forEach(scenario -> matrix.put(scenario.id(), across(scenario)));

            // then the vector is complete rather than partial: every store answered for every seed of every scenario,
            // because an arm that silently did not run is indistinguishable from one that passed
            assertThat(matrix).hasSize(scenarios.size());
            matrix.forEach((id, perBackend) -> {
                assertThat(perBackend).as("stores that answered for %s", id).hasSize(BACKENDS.size());
                perBackend.forEach((backend, results) -> {
                    assertThat(results).as("seeds run for %s on %s", id, backend)
                                       .hasSize(MATRIX_BUDGET.seeds());
                    assertThat(results).allSatisfy(result -> assertThat(result.verdict()).isNotNull());
                });
            });
        }
    }

    @Nested
    class TheSameContendedAppendScenarioOnEveryStore {

        @Test
        void reportsAVerdictPerBackendAndNamesWhatPostgresCannotExpress() {
            // given the shipped contended-append scenario, edited in no way at all
            Scenario scenario = HuntScenarios.appendRejectedAfterMarker();

            // when it is run against every registered store
            Map<String, List<ScenarioResult>> perBackend = across(scenario);

            // then the two Dynamic Consistency Boundary stores judged the protocol, while the aggregate-based store said
            // it cannot: a store whose append condition is not a boundary over tags and a marker has nothing for the
            // reference model to be compared against, and reporting that is what stops the vector claiming coverage
            assertThat(perBackend.get(InMemoryHuntBackend.NAME))
                    .allSatisfy(result -> assertThat(result.notApplicable())
                            .as("a boundary store must be able to judge the protocol")
                            .noneMatch(statement -> statement.contains("AppendConformsToDcbModel")));
            assertThat(perBackend.get(PostgresJpaHuntBackend.NAME))
                    .allSatisfy(result -> assertThat(result.notApplicable())
                            .anySatisfy(statement -> assertThat(statement).contains("AppendConformsToDcbModel",
                                                                                    "not expressible")));

            // and the divergence this matrix found is pinned where it is, so that closing it is what breaks this
            // assertion. An append made with no consistency condition is rejected as conflicting on the aggregate-based
            // store whenever the aggregate already holds events, because an INFINITY marker carries no position and the
            // sequencer restarts at zero. That is finding F-14, recorded rather than repaired: the suite observes the
            // framework and does not fix it, so the expectation flips red on the day it stops happening.
            assertThat(perBackend.get(InMemoryHuntBackend.NAME))
                    .as("the boundary store holds the guarantee")
                    .allSatisfy(result -> assertThat(result.violations()).isEmpty());
            // The pinned expectation is that the divergence is *present*, not that it is the only thing present. It was
            // written as the latter when the aggregate-based arm produced nothing else, and that stopped being true the
            // moment the loss oracle became decidable on this store: the arm now also reports events the store held and
            // the read side never delivered, intermittently, which is finding F-16 rather than a broken expectation. An
            // assertion about the absence of other findings is an assertion that the suite must stop finding things.
            assertThat(perBackend.get(PostgresJpaHuntBackend.NAME))
                    .as("finding F-14 on %s", PostgresJpaHuntBackend.NAME)
                    .allSatisfy(result -> assertThat(result.violations())
                            .isNotEmpty()
                            .anySatisfy(violation -> assertThat(violation.machineName())
                                    .isEqualTo(AppendOutcomeChecker.UNCONDITIONAL_APPEND_NEVER_REJECTED)));
        }
    }

    @Nested
    class WhatEachStoreDeliveredOfWhatItCommitted {

        @Test
        void isDecidedRatherThanExcusedOnEveryStoreNowThatQuiescenceIsAskedOfTheStoreItself() {
            // given the shipped contended-append scenario, whose read side is a real streaming processor over whichever
            // store the arm names
            Scenario scenario = HuntScenarios.appendRejectedAfterMarker();

            // when it is run against every registered store
            Map<String, List<ScenarioResult>> perBackend = across(scenario);

            // then the two in-heap-event-store arms deliver everything they commit, which is what a store with no index
            // taken before a commit should do: it has no holes for a reader to have to come back for.
            List.of(InMemoryHuntBackend.NAME, HsqldbTokenStoreBackend.NAME).forEach(backend -> assertThat(
                            perBackend.get(backend))
                    .as("delivery on %s", backend)
                    .allSatisfy(result -> {
                        assertThat(result.violations())
                                .noneMatch(violation -> DeliveryChecker.NO_COMMITTED_EVENT_GOES_UNDELIVERED
                                        .equals(violation.machineName()));
                        assertThat(result.notes()).noneMatch(note -> note.contains("read side had not caught up"));
                    }));

            // and on the stores that do take an index before committing, whatever the read side did is DECIDED rather
            // than excused. That is the part that could not be asserted before, and the change behind it is not a
            // tolerance: quiescence used to be measured against a count the harness kept, and on a store whose engine
            // returns from its commit call before the database transaction commits, that count is not a count of
            // readable events. So the arm could never reach quiescence at any budget, and a store that loses an event
            // for ever produced exactly the same observation as a run that was merely interrupted -- which is why a
            // planted gap defect escaped this suite once. Quiescence is now asked of the store, and a read side that has
            // stopped moving with events still missing is judged instead of forgiven.
            List.of(PostgresJpaHuntBackend.NAME, PostgresJpaHuntBackend.SplitTokenStore.NAME).forEach(
                    backend -> perBackend.get(backend).forEach(result -> {
                        System.out.println("MEASURED " + backend + " seed " + result.seed()
                                                   + " undelivered=" + undelivered(result)
                                                   + " decided=" + lossDecided(result)
                                                   + " caughtUp=" + result.notes().stream()
                                                                          .noneMatch(note -> note.contains(
                                                                                  "read side had not caught up")));
                        if (undelivered(result) > 0 && stalled(result)) {
                            assertThat(lossDecided(result))
                                    .as("a read side that stopped with events still in the store must be judged, not "
                                                + "excused: %s", result)
                                    .isTrue();
                        }
                    }));
        }

        private static boolean lossDecided(ScenarioResult result) {
            return result.violations().stream()
                         .anyMatch(violation -> DeliveryChecker.NO_COMMITTED_EVENT_GOES_UNDELIVERED
                                 .equals(violation.machineName()));
        }

        private static boolean stalled(ScenarioResult result) {
            return Boolean.parseBoolean(String.valueOf(drainOf(result).get(HistoryOps.STALLED)));
        }

        private static long undelivered(ScenarioResult result) {
            Map<String, Object> drain = drainOf(result);
            long readable = Long.parseLong(String.valueOf(drain.getOrDefault("readableEvents", "0")));
            long delivered = Long.parseLong(String.valueOf(drain.getOrDefault("deliveredEvents", "0")));
            return Math.max(0L, readable - delivered);
        }

        private static Map<String, Object> drainOf(ScenarioResult result) {
            return HistoryView.read(result.history()).notes(HistoryOps.PHASE).stream()
                              .filter(phase -> phase.stringValue(HistoryOps.QUIESCED) != null)
                              .map(org.axonframework.hunt.history.HistoryRecord::value)
                              .findFirst()
                              .orElse(Map.of());
        }
    }

    @Nested
    class AClusterScenarioOnAStoreThatArbitratesNoClaims {

        @Test
        void reportsTheOwnershipInvariantAsNotApplicableRatherThanPassingItQuietly() {
            // given the shipped bootstrap scenario, whose claim is entirely about who owns a segment
            Scenario scenario = HuntScenarios.concurrentBootstrap();

            // when it is run against every registered store, two of which arbitrate claims and two of which do not
            Map<String, List<ScenarioResult>> perBackend = across(scenario);

            // then the stores with no notion of an owner say the invariant is not expressible on them. That matters more
            // than it looks: the framework's in-heap token store grants every claim, so an ownership assertion made
            // against it holds vacuously, and a vector that recorded that as a pass would be claiming coverage it does
            // not have.
            assertThat(perBackend.get(InMemoryHuntBackend.NAME))
                    .as("a store that arbitrates no claims must say so")
                    .allSatisfy(result -> assertThat(result.notApplicable())
                            .anySatisfy(statement -> assertThat(statement)
                                    .contains(OwnershipChecker.AT_MOST_ONE_SEGMENT_OWNER, "not expressible")));

            // and the stores that do arbitrate claims decided it instead of declining
            List.of(HsqldbTokenStoreBackend.NAME, PostgresJpaHuntBackend.NAME).forEach(backend -> assertThat(
                            perBackend.get(backend))
                    .as("a store that arbitrates claims must decide ownership: %s", backend)
                    .allSatisfy(result -> assertThat(result.notApplicable())
                            .noneMatch(statement -> statement.contains(OwnershipChecker.AT_MOST_ONE_SEGMENT_OWNER))));
        }
    }

    @Nested
    class TheWholeMatrixAsOneTable {

        @Test
        void printsAVerdictVectorPerScenarioAndPerSeedForEveryFindingToQuote() {
            // given every scenario of the matrix
            List<Scenario> scenarios = scenarios();

            // when each is run against every store
            scenarios.forEach(BackendDifferentialTest::across);

            // then the table is printed in one place, so a finding can quote its vector without re-deriving it
            System.out.println("=== BACKEND DIFFERENTIAL MATRIX ===");
            MATRIX.forEach((id, perBackend) -> System.out.println(vector(id, perBackend)));

            // and no arm anywhere reached a pass it was not entitled to. A scenario that declares a fault and reports it
            // as never having fired has verified nothing, whatever else its oracles said, so the only verdict such a run
            // may carry is an undecided one. This is the landing-evidence rule applied across every store: a fault that
            // works in the heap and silently does nothing across a database round trip would otherwise turn one arm of
            // the matrix into a fault-free control that reads as coverage.
            MATRIX.forEach((id, perBackend) -> allResults(perBackend).forEach(result -> {
                if (!result.faultFires().isEmpty()) {
                    System.out.println("FIRES " + id + " seed " + result.seed() + " " + result.faultFires());
                }
                boolean aFaultDidNotFire = result.faultFires().values().stream().anyMatch(fires -> fires == 0L);
                if (aFaultDidNotFire) {
                    assertThat(result.verdict())
                            .as("a run whose declared fault never fired may not pass: %s", result)
                            .isNotEqualTo(Verdict.PASS);
                }
            }));
        }
    }
}
