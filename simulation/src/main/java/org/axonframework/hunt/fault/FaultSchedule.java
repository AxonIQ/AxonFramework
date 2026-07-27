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

package org.axonframework.hunt.fault;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * When a run's faults are installed, and when they are taken away again.
 * <p>
 * A run has five phases and they are not decoration:
 * <ol>
 *     <li><b>warmup</b> -- the workload runs unperturbed, so the run has a baseline and so the checkers see what
 *     normal looks like;</li>
 *     <li><b>fault windows</b> -- each window installs its faults, and every record written while it is open carries
 *     its identifier;</li>
 *     <li><b>heal</b> -- every fault is removed and the system is given time to stop being perturbed;</li>
 *     <li><b>settle</b> -- the workload drains to quiescence with nothing interfering;</li>
 *     <li><b>verdict</b> -- and only now do the oracles run.</li>
 * </ol>
 * Judging a system while it is still being broken manufactures violations at the run boundary, which is the most
 * common way a chaos suite produces findings that are not real. The phases exist to make that impossible.
 * <p>
 * Example usage:
 * <pre>{@code
 * FaultSchedule schedule = FaultSchedule.builder()
 *         .warmup(Duration.ofMillis(200))
 *         .window(FaultWindow.immediately("w1", Duration.ofMillis(400), new InjectedLatencyFault(...)))
 *         .heal(Duration.ofMillis(100))
 *         .settle(Duration.ofSeconds(5))
 *         .build();
 * }</pre>
 *
 * @param warmup  how long the workload runs before the first window may open
 * @param windows the fault windows, which may overlap
 * @param heal    how long the run waits, with every fault removed, before it starts draining
 * @param settle  the longest the run waits for the system to reach quiescence before the oracles run
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public record FaultSchedule(Duration warmup, List<FaultWindow> windows, Duration heal, Duration settle) {

    /**
     * Compact constructor rejecting missing parts and negative phase lengths.
     */
    public FaultSchedule {
        Objects.requireNonNull(warmup, "The warmup cannot be null.");
        Objects.requireNonNull(heal, "The heal cannot be null.");
        Objects.requireNonNull(settle, "The settle cannot be null.");
        windows = List.copyOf(Objects.requireNonNull(windows, "The windows cannot be null."));
        if (warmup.isNegative() || heal.isNegative() || settle.isNegative()) {
            throw new IllegalArgumentException("A fault schedule cannot have a negative phase length.");
        }
    }

    /**
     * Creates a schedule that injects nothing: the smoke arm of a scenario whose primary claim needs no fault.
     *
     * @param settle the longest the run waits for quiescence before the oracles run
     * @return a fault-free schedule
     */
    public static FaultSchedule none(Duration settle) {
        return new FaultSchedule(Duration.ZERO, List.of(), Duration.ZERO, settle);
    }

    /**
     * Creates a schedule with one window holding one fault.
     *
     * @param fault  the fault to install
     * @param warmup how long the workload runs unperturbed first
     * @param window how long the fault stays installed
     * @param settle the longest the run waits for quiescence before the oracles run
     * @return a single-fault schedule
     */
    public static FaultSchedule single(Fault fault, Duration warmup, Duration window, Duration settle) {
        Objects.requireNonNull(fault, "The fault cannot be null.");
        return new FaultSchedule(warmup,
                                 List.of(FaultWindow.immediately(fault.kind(), window, fault)),
                                 window.dividedBy(2),
                                 settle);
    }

    /**
     * Creates a builder.
     *
     * @return a builder with every phase defaulted to zero
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns every fault the schedule declares, across all windows.
     *
     * @return the declared faults, in window order
     */
    public List<Fault> declaredFaults() {
        return windows.stream().flatMap(window -> window.faults().stream()).toList();
    }

    /**
     * Returns the largest number of faults installed at the same time.
     * <p>
     * This is what the fault-composition policy is enforced against: a smoke tier runs one fault at a time so that a
     * failure is attributable, a hardening tier runs pairs, and only a release tier runs storms.
     *
     * @return the maximum number of simultaneously installed faults; zero when the schedule injects nothing
     */
    public int maxConcurrentFaults() {
        int maximum = 0;
        for (FaultWindow window : windows) {
            int concurrent = 0;
            for (FaultWindow other : windows) {
                if (window.overlaps(other)) {
                    concurrent += other.faults().size();
                }
            }
            maximum = Math.max(maximum, concurrent);
        }
        return maximum;
    }

    /**
     * Returns how long the fault phase lasts: from the end of warmup to the close of the last window.
     *
     * @return the length of the fault phase; {@link Duration#ZERO} when nothing is injected
     */
    public Duration faultPhase() {
        return windows.stream().map(FaultWindow::end).max(Duration::compareTo).orElse(Duration.ZERO);
    }

    /**
     * Renders the schedule for the history header.
     *
     * @return the phase lengths and the declared faults, rendered flat
     */
    public Map<String, String> describe() {
        Map<String, String> described = new LinkedHashMap<>();
        described.put("warmupMs", String.valueOf(warmup.toMillis()));
        described.put("faultPhaseMs", String.valueOf(faultPhase().toMillis()));
        described.put("healMs", String.valueOf(heal.toMillis()));
        described.put("settleMs", String.valueOf(settle.toMillis()));
        described.put("declaredFaults", String.join(",", declaredFaults().stream().map(Fault::kind).toList()));
        described.put("maxConcurrentFaults", String.valueOf(maxConcurrentFaults()));
        return Map.copyOf(described);
    }

    /**
     * Assembles a schedule phase by phase.
     *
     * @author Stefan Dragisic
     * @since 5.3.0
     */
    public static final class Builder {

        private final List<FaultWindow> windows = new ArrayList<>();
        private Duration warmup = Duration.ZERO;
        private Duration heal = Duration.ZERO;
        private Duration settle = Duration.ZERO;

        private Builder() {
        }

        /**
         * Sets how long the workload runs unperturbed before the first window may open.
         *
         * @param duration the warmup length
         * @return this builder
         */
        public Builder warmup(Duration duration) {
            this.warmup = Objects.requireNonNull(duration, "The duration cannot be null.");
            return this;
        }

        /**
         * Adds a fault window.
         *
         * @param window the window to add
         * @return this builder
         */
        public Builder window(FaultWindow window) {
            windows.add(Objects.requireNonNull(window, "The window cannot be null."));
            return this;
        }

        /**
         * Sets how long the run waits, with every fault removed, before it starts draining.
         *
         * @param duration the heal length
         * @return this builder
         */
        public Builder heal(Duration duration) {
            this.heal = Objects.requireNonNull(duration, "The duration cannot be null.");
            return this;
        }

        /**
         * Sets the longest the run waits for quiescence before the oracles run.
         *
         * @param duration the settle limit
         * @return this builder
         */
        public Builder settle(Duration duration) {
            this.settle = Objects.requireNonNull(duration, "The duration cannot be null.");
            return this;
        }

        /**
         * Builds the schedule.
         *
         * @return the schedule
         */
        public FaultSchedule build() {
            return new FaultSchedule(warmup, List.copyOf(windows), heal, settle);
        }
    }
}
