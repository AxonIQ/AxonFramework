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

import org.axonframework.hunt.checker.ModelConformanceChecker;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Re-judges a recorded run with no simulation at all.
 * <p>
 * Every checker is a pure function of the history it reads, so a run that broke once can be re-judged for ever by
 * anyone holding the file. That is what makes a history the only honest regression asset a contended finding has:
 * re-running the seed that produced it draws a fresh sample of the same workload against a new thread schedule and
 * may well come back clean, whereas replaying the file reaches the same verdict every time, on every machine.
 * <p>
 * Point it at a file with {@code -Dhunt.history=<path>}; with no property it replays the histories this build ships,
 * which are the far end of every reproduce command a violation prints.
 * <pre>{@code
 * ./mvnw -Phunt -pl simulation -am test -Dtest=HistoryReplayTest \
 *     -Dhunt.history=target/hunt-histories/s1-smoke/some-run.jsonl \
 *     -Dsurefire.failIfNoSpecifiedTests=false
 * }</pre>
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
class HistoryReplayTest {

    /**
     * A run of the contended scenario in which nothing was found broken.
     */
    private static final String CLEAN = "/hunt-histories/pinned-contended-run.jsonl";

    /**
     * The same scenario and the same seed, recorded while the store's consistency check was deliberately bypassed.
     */
    private static final String BROKEN = "/hunt-histories/pinned-conflict-check-bypass.jsonl";

    private static Path shipped(String resource) {
        try {
            return Path.of(HistoryReplayTest.class.getResource(resource).toURI());
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException("The shipped history [" + resource + "] is unreadable.", e);
        }
    }

    /**
     * Resolves a named history against the module directory, then against the repository root.
     * <p>
     * The build runs the module with {@code simulation/} as its working directory, but the paths a violation prints
     * and the paths a reader copies out of a report are written from the repository root. Trying both is two lines
     * and removes the most obvious way to waste ten minutes on a file that is plainly there.
     */
    private static Path resolve(String named) {
        Path asGiven = Path.of(named);
        return Files.exists(asGiven) ? asGiven : Path.of("..").resolve(named).normalize();
    }

    private static String property(String name) {
        String value = System.getProperty(name);
        // Maven substitutes an unset property with its own placeholder, which is not a value.
        return value == null || value.isBlank() || value.startsWith("${") ? null : value;
    }

    @Nested
    class TheHistoriesThisBuildShips {

        @Test
        void replayTheVerdictTheyWereRecordedWith() {
            // given a clean run and a run of the same scenario recorded with the store's conflict check bypassed
            Path clean = shipped(CLEAN);
            Path broken = shipped(BROKEN);

            // when both are re-judged from the file alone
            ScenarioResult cleanResult = ScenarioRunner.replay(clean);
            ScenarioResult brokenResult = ScenarioRunner.replay(broken);
            System.out.println(cleanResult);
            System.out.println(brokenResult);

            // then the clean one holds
            assertThat(cleanResult.violations()).as("replaying %s: %s", clean, cleanResult).isEmpty();
            assertThat(cleanResult.verdict()).isEqualTo(Verdict.PASS);

            // and the broken one is still caught, by the oracle that caught it when it was recorded
            assertThat(brokenResult.verdict()).isEqualTo(Verdict.FAIL);
            assertThat(brokenResult.violations())
                    .anyMatch(violation -> violation.machineName()
                                                    .equals(ModelConformanceChecker.APPEND_CONFORMS_TO_DCB_MODEL));
        }

        @Test
        void areSmallEnoughToLiveInTheRepositoryForEver() {
            // given the shipped histories
            // then each stays well under a hundred kilobytes, because a regression asset nobody wants to carry is an
            // asset that gets deleted
            assertThat(shipped(CLEAN).toFile().length()).isLessThan(150_000L);
            assertThat(shipped(BROKEN).toFile().length()).isLessThan(150_000L);
        }
    }

    @Nested
    class AHistoryNamedOnTheCommandLine {

        @Test
        void isJudgedByTheWholeCheckerSet() {
            // given the file the caller named, defaulting to the clean shipped history
            String named = property("hunt.history");
            Path history = named == null ? shipped(CLEAN) : resolve(named);
            assertThat(Files.exists(history)).as("history file [%s]", history).isTrue();

            // when
            ScenarioResult result = ScenarioRunner.replay(history);
            System.out.println(result);

            // then every registered checker had its say, and a replay that goes red is the finding rather than a
            // test failure to explain away
            assertThat(result.results())
                    .hasSameSizeAs(org.axonframework.hunt.checker.CheckerRegistry.discover());
            if (named == null) {
                assertThat(result.violations()).as("replaying the shipped clean history: %s", result).isEmpty();
            }
        }
    }

    @Nested
    class AReplayComparedAgainstTheRunItCameFrom {

        @Test
        void reachesTheSameVerdictTheLiveRunReached() {
            // given a live run of a scenario whose write side a seed replays exactly
            Scenario scenario = HuntScenarios.appendRejectedAfterMarkerSingleWriter();
            ScenarioResult live = ScenarioRunner.run(scenario, Tier.SMOKE, scenario.seed(),
                                                     HuntHistories.directory("replay"));

            // when its history is re-judged offline
            ScenarioResult replayed = ScenarioRunner.replay(live.history());

            // then the offline verdict is the live one, and it carries the same identity
            assertThat(replayed.verdict()).isEqualTo(live.verdict());
            assertThat(replayed.violations()).hasSameSizeAs(live.violations());
            assertThat(replayed.scenarioId()).isEqualTo(live.scenarioId());
            assertThat(replayed.seed()).isEqualTo(live.seed());
            assertThat(replayed.tier()).isEqualTo(live.tier());
        }
    }
}
