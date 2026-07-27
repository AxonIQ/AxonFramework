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

import org.axonframework.hunt.checker.FaultLandingChecker;
import org.axonframework.hunt.scenario.Scenario;
import org.axonframework.hunt.scenario.ScenarioResult;
import org.axonframework.hunt.scenario.ScenarioRunner;
import org.axonframework.hunt.scenario.Tier;
import org.axonframework.hunt.scenario.TierBudget;
import org.axonframework.hunt.workload.LedgerWorkload;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs every fault kind against a real system and checks it left evidence.
 * <p>
 * A fault nobody has watched fire is a fault that may not work, and a suite full of those reports passes it has not
 * earned. Each case here drives a short run with one fault installed and asserts the fire count is positive, which is
 * the same evidence the runner writes into every history.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
class FaultsLandTest {

    private static ScenarioResult runWith(String id, Fault fault, int commands) {
        Scenario scenario = Scenario.builder(id, "One fault, one short run, one fire count")
                                    .claims("C1")
                                    .workload(LedgerWorkload::hotKey)
                                    .faults(FaultSchedule.builder()
                                                         .warmup(Duration.ofMillis(5))
                                                         .window(FaultWindow.immediately("only",
                                                                                         Duration.ofMillis(400),
                                                                                         fault))
                                                         .heal(Duration.ofMillis(50))
                                                         .settle(Duration.ofSeconds(10))
                                                         .build())
                                    .oracles(FaultLandingChecker.DECLARED_FAULTS_LAND)
                                    .seed(9L)
                                    .budget(Tier.SMOKE, new TierBudget(commands, 1, Duration.ofSeconds(60)))
                                    .build();
        ScenarioResult result = ScenarioRunner.run(scenario, Tier.SMOKE, scenario.seed(),
                                                   ScenarioRunner.historyDirectory(
                                                           Path.of("target", "hunt-histories", "faults")));
        System.out.println(result);
        return result;
    }

    @Test
    void aSlowStoreDelaysAppendsAndSaysHowMany() {
        // given / when
        ScenarioResult result = runWith("fault_latency", new InjectedLatencyFault(Duration.ofMillis(1)), 3_000);

        // then
        assertThat(result.faultFires().get("injected-latency")).isPositive();
    }

    @Test
    void aRefusingStoreFailsCommitsWithoutBeingMistakenForAConflict() {
        // given / when
        ScenarioResult result = runWith("fault_rejection", new AppendRejectionFault(5), 3_000);

        // then the refusals landed, and none of them was read as a protocol violation
        assertThat(result.faultFires().get("append-rejection")).isPositive();
        assertThat(result.violations()).isEmpty();
    }

    @Test
    void aLosingStoreAcknowledgesCommitsItNeverMade() {
        // given / when
        ScenarioResult result = runWith("fault_vanish", new WriteThenVanishFault(5), 3_000);

        // then
        assertThat(result.faultFires().get("write-then-vanish")).isPositive();
    }

    @Test
    void anAtLeastOnceStoreWritesTheSameBatchTwice() {
        // given / when
        ScenarioResult result = runWith("fault_duplicate", new DuplicatedAppendFault(5), 3_000);

        // then
        assertThat(result.faultFires().get("duplicated-append")).isPositive();
    }

    @Test
    void aTornBatchStoresOnlyItsFirstEvent() {
        // given / when
        ScenarioResult result = runWith("fault_partial", new PartialBatchFault(1, 5), 3_000);

        // then
        assertThat(result.faultFires().get("partial-batch")).isPositive();
    }

    @Test
    void aFrozenWriterIsHeldLongerThanEveryTimeoutInTheRun() {
        // given a stall longer than the run's compressed timings, aimed at a writer that keeps working
        ScenarioResult result = runWith("fault_pause", new ParticipantPauseFault(Duration.ofMillis(150), 0), 6_000);

        // then the writer was actually held, and the evidence says for how long
        assertThat(result.faultFires().get("participant-pause")).isPositive();
    }

    @Test
    void aStoreThatSkipsItsConflictCheckIsCaughtByTheModel() {
        // given / when
        ScenarioResult result = runWith("fault_bypass", new ConflictCheckBypassFault(1), 3_000);

        // then the fault landed and the oracle went red, which is what makes the oracle an oracle
        assertThat(result.faultFires().get("conflict-check-bypass")).isPositive();
        assertThat(result.violations()).isNotEmpty();
    }
}
