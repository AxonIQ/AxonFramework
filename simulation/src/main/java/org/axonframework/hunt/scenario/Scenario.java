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

import org.axonframework.hunt.fault.FaultSchedule;
import org.axonframework.hunt.harness.DeterminismMode;
import org.axonframework.hunt.harness.HuntTimescale;
import org.axonframework.hunt.harness.InMemoryHuntBackend;
import org.axonframework.hunt.workload.Workload;

import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One executable experiment, expressed entirely as data.
 * <p>
 * Nothing in {@link ScenarioRunner} knows any scenario's name. A scenario says which claims it is trying to falsify,
 * what load to apply, what to break and when, which store, which timings, which oracles must be present, which seed,
 * and what it may cost; the runner does the rest. That is what makes the scenario list a corpus rather than a
 * codebase: the next one is a declaration, and the harness does not change.
 * <p>
 * Example usage:
 * <pre>{@code
 * Scenario scenario = Scenario.builder("uncontended_transfers_conserve_balance", "Uncontended transfers")
 *         .claims("C1")
 *         .workload(LedgerWorkload::seedShaped)
 *         .oracles(ConservationChecker.LEDGER_CONSERVES_TOTAL_BALANCE)
 *         .seed(7L)
 *         .budget(Tier.SMOKE, new TierBudget(200, 1, Duration.ofSeconds(60)))
 *         .build();
 * ScenarioResult result = ScenarioRunner.run(scenario, Tier.SMOKE, scenario.seed(), historyDirectory);
 * }</pre>
 *
 * @param id           the stable identifier, used on the command line and in the history header
 * @param name         a one-line description of what the scenario is trying to break
 * @param claims       the claim and missing-claim identifiers from the plan that this scenario tries to falsify
 * @param workload     the load to apply; a supplier, because every run gets its own instance and its own state
 * @param faults       when to break what, and when to stop
 * @param backend      the name of the store to drive
 * @param timescale    the timings to run at
 * @param determinism  how much of the run's scheduling to pin down
 * @param buggifyProbability the chance a scheduling-bias point perturbs the run; zero leaves the points inert
 * @param oracles      the invariant names that must be registered and must hold; the whole registered checker set
 *                     runs regardless, so this is a guard against an oracle silently disappearing, not a filter
 * @param seed         the base seed; a tier running several seeds counts up from it
 * @param budgets      what the scenario may cost, per tier
 * @param nodes        how many framework instances share the run's store and token store; one unless the scenario's
 *                     claim is about what several of them do to each other
 * @param deliveryMode the delivery guarantee this scenario's deployment can actually provide, declared rather than
 *                     guessed, because the guarantee genuinely differs between deployments
 * @param livenessHorizon how long a committed event may take to reach a consumer before that is a liveness failure;
 *                     defaults to the timescale's quiescence budget, which is how long the runner itself waits
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public record Scenario(String id,
                       String name,
                       Set<String> claims,
                       Supplier<Workload> workload,
                       FaultSchedule faults,
                       String backend,
                       HuntTimescale timescale,
                       DeterminismMode determinism,
                       double buggifyProbability,
                       Set<String> oracles,
                       long seed,
                       Map<Tier, TierBudget> budgets,
                       int nodes,
                       DeliveryMode deliveryMode,
                       Duration livenessHorizon) {

    /**
     * Compact constructor rejecting missing parts and defensively copying every collection.
     */
    public Scenario {
        Objects.requireNonNull(id, "The id cannot be null.");
        Objects.requireNonNull(name, "The name cannot be null.");
        Objects.requireNonNull(workload, "The workload cannot be null.");
        Objects.requireNonNull(faults, "The faults cannot be null.");
        Objects.requireNonNull(backend, "The backend cannot be null.");
        Objects.requireNonNull(timescale, "The timescale cannot be null.");
        Objects.requireNonNull(determinism, "The determinism cannot be null.");
        Objects.requireNonNull(deliveryMode, "The deliveryMode cannot be null.");
        Objects.requireNonNull(livenessHorizon, "The livenessHorizon cannot be null.");
        claims = Set.copyOf(Objects.requireNonNull(claims, "The claims cannot be null."));
        oracles = Set.copyOf(Objects.requireNonNull(oracles, "The oracles cannot be null."));
        budgets = Map.copyOf(Objects.requireNonNull(budgets, "The budgets cannot be null."));
        if (budgets.isEmpty()) {
            throw new IllegalArgumentException("The scenario [" + id + "] declares no budget for any tier.");
        }
        if (buggifyProbability < 0.0 || buggifyProbability > 1.0) {
            throw new IllegalArgumentException(
                    "The buggifyProbability must be in [0,1], but was " + buggifyProbability + ".");
        }
        if (nodes < 1) {
            throw new IllegalArgumentException(
                    "The scenario [" + id + "] declares " + nodes + " nodes; it needs at least one.");
        }
    }

    /**
     * Starts a builder with everything a scenario can default already defaulted: the in-heap store, the compressed
     * timescale, real threads, no scheduling bias and no faults.
     *
     * @param id   the scenario's stable identifier
     * @param name a one-line description of what it is trying to break
     * @return a builder
     */
    public static Builder builder(String id, String name) {
        return new Builder(id, name);
    }

    /**
     * Returns this scenario pointed at a different store.
     * <p>
     * This is the whole of the backend-differential mechanism. A scenario is backend-agnostic by construction, so
     * running one somewhere else is a substitution rather than an edit, and a defect that appears on one store and
     * not on another is attributable to that store's adapter instead of starting an argument.
     *
     * @param name the name of a registered backend
     * @return the same scenario, driven against that backend
     */
    public Scenario onBackend(String name) {
        return new Scenario(id, this.name, claims, workload, faults, Objects.requireNonNull(name, "The name cannot "
                + "be null."), timescale, determinism, buggifyProbability, oracles, seed, budgets, nodes,
                            deliveryMode, livenessHorizon);
    }

    /**
     * Returns the budget for the given tier.
     *
     * @param tier the tier to look up
     * @return the budget
     * @throws IllegalArgumentException if the scenario declares no budget for that tier
     */
    public TierBudget budget(Tier tier) {
        TierBudget budget = budgets.get(Objects.requireNonNull(tier, "The tier cannot be null."));
        if (budget == null) {
            throw new IllegalArgumentException(
                    "The scenario [" + id + "] declares no budget for the " + tier + " tier.");
        }
        return budget;
    }

    /**
     * Returns the seeds a tier runs, counting up from the scenario's base seed.
     *
     * @param tier the tier to run
     * @return the seeds, in order
     */
    public java.util.List<Long> seeds(Tier tier) {
        int count = budget(tier).seeds();
        java.util.List<Long> seeds = new java.util.ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            seeds.add(seed + index);
        }
        return java.util.List.copyOf(seeds);
    }

    /**
     * Assembles a scenario field by field.
     *
     * @author Stefan Dragisic
     * @since 5.3.0
     */
    public static final class Builder {

        private final String id;
        private final String name;
        private final Set<String> claims = new LinkedHashSet<>();
        private final Set<String> oracles = new LinkedHashSet<>();
        private final Map<Tier, TierBudget> budgets = new EnumMap<>(Tier.class);
        private Supplier<Workload> workload = () -> {
            throw new IllegalStateException("A scenario must declare a workload.");
        };
        private FaultSchedule faults = FaultSchedule.none(Duration.ofSeconds(30));
        private String backend = InMemoryHuntBackend.NAME;
        private HuntTimescale timescale = HuntTimescale.compressed();
        private DeterminismMode determinism = DeterminismMode.REAL_THREADS;
        private double buggifyProbability;
        private long seed;
        private int nodes = 1;
        private DeliveryMode deliveryMode = DeliveryMode.AT_LEAST_ONCE_NO_LOSS;
        private @org.jspecify.annotations.Nullable Duration livenessHorizon;

        private Builder(String id, String name) {
            this.id = Objects.requireNonNull(id, "The id cannot be null.");
            this.name = Objects.requireNonNull(name, "The name cannot be null.");
        }

        /**
         * Declares the claims this scenario tries to falsify.
         *
         * @param ids the claim and missing-claim identifiers from the plan
         * @return this builder
         */
        public Builder claims(String... ids) {
            claims.addAll(java.util.List.of(ids));
            return this;
        }

        /**
         * Declares the load to apply.
         *
         * @param supplier a supplier of the workload; called once per run, so each run gets its own state
         * @return this builder
         */
        public Builder workload(Supplier<Workload> supplier) {
            this.workload = Objects.requireNonNull(supplier, "The supplier cannot be null.");
            return this;
        }

        /**
         * Declares when to break what.
         *
         * @param schedule the fault schedule
         * @return this builder
         */
        public Builder faults(FaultSchedule schedule) {
            this.faults = Objects.requireNonNull(schedule, "The schedule cannot be null.");
            return this;
        }

        /**
         * Declares which store to drive.
         *
         * @param name the backend's name
         * @return this builder
         */
        public Builder backend(String name) {
            this.backend = Objects.requireNonNull(name, "The name cannot be null.");
            return this;
        }

        /**
         * Declares which timings to run at.
         *
         * @param arm the timescale arm
         * @return this builder
         */
        public Builder timescale(HuntTimescale arm) {
            this.timescale = Objects.requireNonNull(arm, "The arm cannot be null.");
            return this;
        }

        /**
         * Declares how much of the run's scheduling to pin down.
         *
         * @param mode the determinism mode
         * @return this builder
         */
        public Builder determinism(DeterminismMode mode) {
            this.determinism = Objects.requireNonNull(mode, "The mode cannot be null.");
            return this;
        }

        /**
         * Declares how hard to bias the run's scheduling towards rare interleavings.
         *
         * @param probability the chance in {@code [0,1]} that a reached perturbation point fires
         * @return this builder
         */
        public Builder buggify(double probability) {
            this.buggifyProbability = probability;
            return this;
        }

        /**
         * Declares the invariants that must be registered and must hold.
         *
         * @param machineNames the invariant names
         * @return this builder
         */
        public Builder oracles(String... machineNames) {
            oracles.addAll(java.util.List.of(machineNames));
            return this;
        }

        /**
         * Declares the base seed.
         *
         * @param baseSeed the seed a tier's seeds count up from
         * @return this builder
         */
        public Builder seed(long baseSeed) {
            this.seed = baseSeed;
            return this;
        }

        /**
         * Declares what the scenario may cost at one tier.
         *
         * @param tier   the tier
         * @param budget the budget
         * @return this builder
         */
        public Builder budget(Tier tier, TierBudget budget) {
            budgets.put(Objects.requireNonNull(tier, "The tier cannot be null."),
                        Objects.requireNonNull(budget, "The budget cannot be null."));
            return this;
        }

        /**
         * Declares how many framework instances share the run's store and token store.
         *
         * @param count the node count; one unless the scenario's claim is about what several nodes do to each other
         * @return this builder
         */
        public Builder nodes(int count) {
            this.nodes = count;
            return this;
        }

        /**
         * Declares the delivery guarantee this scenario's deployment can actually provide.
         *
         * @param mode the mode; see {@link DeliveryMode} for why it is declared rather than inferred
         * @return this builder
         */
        public Builder deliveryMode(DeliveryMode mode) {
            this.deliveryMode = Objects.requireNonNull(mode, "The mode cannot be null.");
            return this;
        }

        /**
         * Declares how long a committed event may take to reach a consumer.
         * <p>
         * State the basis in the scenario's own documentation. A horizon nobody can justify is a constant somebody
         * raised until the suite went green.
         *
         * @param horizon the liveness horizon
         * @return this builder
         */
        public Builder livenessHorizon(Duration horizon) {
            this.livenessHorizon = Objects.requireNonNull(horizon, "The horizon cannot be null.");
            return this;
        }

        /**
         * Builds the scenario.
         * <p>
         * An undeclared liveness horizon resolves to the chosen timescale's quiescence budget, which is how long the
         * runner itself waits for the read side before judging anything: past that point the run has already given
         * up, so a longer horizon could never fire.
         *
         * @return the scenario
         */
        public Scenario build() {
            return new Scenario(id, name, claims, workload, faults, backend, timescale, determinism,
                                buggifyProbability, oracles, seed, budgets, nodes, deliveryMode,
                                livenessHorizon == null ? timescale.quiescence() : livenessHorizon);
        }
    }
}
