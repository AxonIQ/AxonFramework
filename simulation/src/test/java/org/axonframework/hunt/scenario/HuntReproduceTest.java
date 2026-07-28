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

import org.axonframework.hunt.harness.HuntTimescale;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Replays one run, named entirely by system properties.
 * <p>
 * This is the far end of the command every violation prints. A report that says a run broke is worth an hour of
 * somebody's time; a report that says how to see it break again is worth minutes, and the difference is that this
 * class exists and accepts exactly the four properties the history header renders.
 * <p>
 * With no properties it replays the shipped contention scenario at its first seed, so it is also a working example of
 * the command.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
class HuntReproduceTest {

    @Test
    void replaysTheRunNamedOnTheCommandLine() {
        // given the run the properties name, defaulting to the shipped scenario's first seed
        Scenario declared = HuntScenarios.byId(property("hunt.scenario", HuntScenarios.APPEND_REJECTED_AFTER_MARKER))
                                         .orElseThrow(() -> new IllegalArgumentException(
                                                 "No scenario named [" + property("hunt.scenario", "") + "]; this "
                                                         + "build ships "
                                                         + HuntScenarios.all().stream().map(Scenario::id).toList()
                                                         + "."));
        Tier tier = Tier.valueOf(property("hunt.tier", Tier.SMOKE.name()));
        long seed = Long.parseLong(property("hunt.seed", String.valueOf(declared.seed())));
        Scenario scenario = new Scenario(declared.id(),
                                         declared.name(),
                                         declared.claims(),
                                         declared.workload(),
                                         declared.faults(),
                                         property("hunt.backend", declared.backend()),
                                         HuntTimescale.byName(property("hunt.timescale",
                                                                       declared.timescale().name())),
                                         declared.determinism(),
                                         declared.buggifyProbability(),
                                         declared.oracles(),
                                         seed,
                                         declared.budgets(),
                                         declared.nodes(),
                                         declared.deliveryMode(),
                                         declared.livenessHorizon());

        // when it is replayed
        ScenarioResult result = ScenarioRunner.run(scenario, tier, seed, HuntHistories.directory("reproduce"));
        System.out.println(result);

        // then nothing may be found broken; a replay that goes red is the finding, not a test failure to explain away
        assertThat(result.violations())
                .as("violations replaying %s seed %d: %s", scenario.id(), seed, result)
                .isEmpty();
    }

    private static String property(String name, String fallback) {
        String value = System.getProperty(name);
        // Maven substitutes an unset property with its own placeholder, which is not a value.
        return value == null || value.isBlank() || value.startsWith("${") ? fallback : value;
    }
}
