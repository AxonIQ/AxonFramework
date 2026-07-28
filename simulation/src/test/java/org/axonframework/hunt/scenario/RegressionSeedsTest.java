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

import org.axonframework.hunt.harness.DeterminismMode;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The regression assets that must never regress, in the only two forms that actually hold.
 * <p>
 * A pinned <b>seed</b> is worth pinning only where a seed decides the run. The determinism probe measured that it
 * does not under real threads: two runs of one seed differed in record count, in operation counts and in which
 * appends were accepted, because which writer wins a race is the thread schedule's decision and not the seed's. Every
 * seed pinned here therefore belongs to a single-writer arm, where the same seed reaches the same append verdicts and
 * leaves the same events behind every time, and one case asserts that the arm really is one.
 * <p>
 * A pinned <b>history</b> is what a contended run gets instead. The file is the exact record of the run, and every
 * checker is a pure function of it, so replaying the file reaches the same verdict for ever while re-running the seed
 * only draws another sample. {@link HistoryReplayTest} owns the mechanism; what is asserted here is that the shipped
 * assets are still judged the way they were recorded, on every change.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
class RegressionSeedsTest {

    @Nested
    class PinnedSeedsOfTheArmThatReplaysExactly {

        @Test
        void pinOnlyAnArmWhoseSeedDecidesTheRun() {
            // given the arm the seeds below are pinned against
            Scenario scenario = HuntScenarios.appendRejectedAfterMarkerSingleWriter();

            // then it must be the mode in which a seed fixes the write side; pinning a seed against anything else
            // would claim a reproducibility the harness has measured it does not have
            assertThat(scenario.determinism()).isEqualTo(DeterminismMode.SINGLE_THREADED);
        }

        @ParameterizedTest
        @ValueSource(longs = {1L, 2L, 3L, 17L, 101L})
        void holdOnEveryPinnedSeed(long seed) {
            // given the single-writer contention arm
            Scenario scenario = HuntScenarios.appendRejectedAfterMarkerSingleWriter();

            // when the pinned seed is run
            ScenarioResult result =
                    ScenarioRunner.run(scenario, Tier.SMOKE, seed, HuntHistories.directory("regression"));

            // then nothing may be found broken
            assertThat(result.violations()).as("violations on pinned seed %d: %s", seed, result).isEmpty();
            assertThat(result.verdict()).as("verdict on pinned seed %d: %s", seed, result).isEqualTo(Verdict.PASS);
        }
    }

    @Nested
    class PinnedHistoriesOfRunsNoSeedReproduces {

        @Test
        void areAllStillJudgedTheWayTheyWereRecorded() {
            // given every history this build ships
            List<Path> shipped = shippedHistories();
            assertThat(shipped).as("shipped histories under src/test/resources/hunt-histories").isNotEmpty();

            // when each is re-judged offline
            // then the clean ones stay clean and the broken one stays broken, whatever the thread schedule of the
            // machine replaying them
            for (Path history : shipped) {
                ScenarioResult result = ScenarioRunner.replay(history);
                System.out.println(result);
                Verdict expected = history.getFileName().toString().contains("bypass") ? Verdict.FAIL : Verdict.PASS;
                assertThat(result.verdict()).as("replaying %s: %s", history, result).isEqualTo(expected);
            }
        }

        private static List<Path> shippedHistories() {
            try {
                Path directory = Path.of(RegressionSeedsTest.class.getResource("/hunt-histories").toURI());
                try (Stream<Path> files = Files.list(directory)) {
                    return files.filter(file -> file.getFileName().toString().endsWith(".jsonl")).sorted().toList();
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to list the shipped histories.", e);
            } catch (java.net.URISyntaxException e) {
                throw new IllegalStateException("Unable to locate the shipped histories.", e);
            }
        }
    }
}
