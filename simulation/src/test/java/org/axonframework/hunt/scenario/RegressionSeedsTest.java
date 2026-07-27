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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seeds that must never regress.
 * <p>
 * A seed lands here when it has earned it: it once tripped something, or it exercises a shape the smoke tier would
 * otherwise not reach. Pinning it turns a one-off discovery into a permanent guard, which is the only way a fuzz
 * campaign accumulates rather than repeats itself.
 * <p>
 * The seeds below are the founding set: three that the shipped scenario's smoke tier runs, and two picked to widen
 * the swarm shapes the per-change build visits.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
class RegressionSeedsTest {

    @ParameterizedTest
    @ValueSource(longs = {1L, 2L, 3L, 17L, 101L})
    void contendedAppendsHoldOnEveryPinnedSeed(long seed) {
        // given the shipped contention scenario
        Scenario scenario = HuntScenarios.appendRejectedAfterMarker();

        // when the pinned seed is run
        ScenarioResult result = ScenarioRunner.run(scenario, Tier.SMOKE, seed, HuntHistories.directory("regression"));

        // then nothing may be found broken
        assertThat(result.violations()).as("violations on pinned seed %d: %s", seed, result).isEmpty();
        assertThat(result.verdict()).as("verdict on pinned seed %d: %s", seed, result).isEqualTo(Verdict.PASS);
    }
}
