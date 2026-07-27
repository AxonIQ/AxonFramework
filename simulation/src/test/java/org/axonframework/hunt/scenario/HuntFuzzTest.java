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

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sweeps many seeds looking for a shape the fixed set never produces.
 * <p>
 * Excluded from every normal build by its tag, because a sweep long enough to be worth running is far too long to run
 * on a change. A scheduled job clears the exclusion:
 * <pre>{@code
 * ./mvnw -Phunt -pl simulation -am test -Dhunt.excludedGroups= -Dtest=HuntFuzzTest \
 *     -Dsurefire.failIfNoSpecifiedTests=false -Dhunt.seeds=500 -Dhunt.startSeed=10000
 * }</pre>
 * Anything it finds is pinned into {@link RegressionSeedsTest} so that the discovery outlives the sweep.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
@Tag("fuzz")
class HuntFuzzTest {

    @Test
    void contendedAppendsHoldAcrossASweepOfSeeds() {
        // given a sweep the caller sized
        Scenario scenario = HuntScenarios.appendRejectedAfterMarker();
        long startSeed = Long.parseLong(property("hunt.startSeed", "10000"));
        int seeds = Integer.parseInt(property("hunt.seeds", "25"));

        // when every seed in the sweep is run
        List<ScenarioResult> broken = new ArrayList<>();
        for (int index = 0; index < seeds; index++) {
            ScenarioResult result =
                    ScenarioRunner.run(scenario, Tier.SMOKE, startSeed + index, HuntHistories.directory("fuzz"));
            if (!result.violations().isEmpty()) {
                broken.add(result);
                System.out.println(result);
            }
        }

        // then nothing may be found broken, and the first thing a failure prints is how to see it again
        assertThat(broken).as("seeds with violations across %d seed(s) from %d", seeds, startSeed).isEmpty();
    }

    private static String property(String name, String fallback) {
        String value = System.getProperty(name);
        // Maven substitutes an unset property with its own placeholder, which is not a value.
        return value == null || value.isBlank() || value.startsWith("${") ? fallback : value;
    }
}
