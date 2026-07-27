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

import org.axonframework.hunt.checker.ConservationChecker;
import org.axonframework.hunt.fault.FaultSchedule;
import org.axonframework.hunt.harness.DeterminismMode;
import org.axonframework.hunt.workload.LedgerWorkload;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Measures what a seed actually fixes, in both determinism modes, and prints the measurement.
 * <p>
 * The measurement is the point. Every harness of this kind claims reproducibility, and the claim is worth exactly as
 * much as the diff that backs it. What the suite is allowed to say about determinism is what these two runs show and
 * nothing beyond it.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
class DeterminismProbeTest {

    private static Scenario probeScenario(DeterminismMode mode) {
        return Scenario.builder("determinism_probe_" + mode.name().toLowerCase(java.util.Locale.ROOT),
                                "The same seed, twice, in " + mode + " mode")
                       .claims("C1")
                       .workload(LedgerWorkload::hotKey)
                       .determinism(mode)
                       .faults(FaultSchedule.none(Duration.ofSeconds(20)))
                       .oracles(ConservationChecker.LEDGER_CONSERVES_TOTAL_BALANCE)
                       .seed(77L)
                       .budget(Tier.SMOKE, new TierBudget(300, 1, Duration.ofSeconds(60)))
                       .build();
    }

    @Nested
    class WithRealThreads {

        @Test
        void reportsWhatTwoRunsOfOneSeedAgreedOn() {
            // given the mode the suite runs its scenarios in
            Scenario scenario = probeScenario(DeterminismMode.REAL_THREADS);

            // when the same seed is run twice and the two histories are diffed
            DeterminismProbe.Reading reading =
                    DeterminismProbe.probe(scenario, Tier.SMOKE, scenario.seed(),
                                           HuntHistories.directory("determinism-real"));
            System.out.println(reading);

            // then the store must at least hold the same events: the seed fixes which transfers are attempted, and
            // in an uncontended-by-conflict sense the set that lands is a function of the schedule, so this is the
            // one property the probe is allowed to be asserted on only if it holds. It is reported either way.
            assertThat(reading.determinism()).isEqualTo("REAL_THREADS");
            assertThat(reading.differences()).as("reported differences: %s", reading).isNotNull();
        }
    }

    @Nested
    class WithASingleWriterAndSingleThreadedExecutors {

        @Test
        void reportsWhatTwoRunsOfOneSeedAgreedOn() {
            // given the mode that pins down everything the framework lets the harness pin down
            Scenario scenario = probeScenario(DeterminismMode.SINGLE_THREADED);

            // when the same seed is run twice and the two histories are diffed
            DeterminismProbe.Reading reading =
                    DeterminismProbe.probe(scenario, Tier.SMOKE, scenario.seed(),
                                           HuntHistories.directory("determinism-single"));
            System.out.println(reading);

            // then the write side must be fully reproducible: one writer issuing a seeded sequence against a store
            // nothing else writes to must reach the same verdicts and leave the same events behind, every time
            assertThat(reading.appendVerdictsIdentical())
                    .as("append verdicts across two runs of one seed: %s", reading)
                    .isTrue();
            assertThat(reading.storeContentsIdentical())
                    .as("store contents across two runs of one seed: %s", reading)
                    .isTrue();
        }
    }
}
