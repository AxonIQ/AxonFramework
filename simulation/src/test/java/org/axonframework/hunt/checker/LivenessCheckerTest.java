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

package org.axonframework.hunt.checker;

import org.axonframework.hunt.history.HistoryOps;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plants slow deliveries, unfinished commands and a node that never came up, and checks what the liveness oracle
 * makes of each.
 * <p>
 * The negative cases carry the weight here. A run that was cut short is not a slow one, an undetermined command
 * outcome is not a stopped command, and a lost event is somebody else's verdict; each of those, judged as a liveness
 * failure, would be a finding that is not there.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
class LivenessCheckerTest {

    private static final Map<String, String> TIGHT_HORIZON = Map.of(LivenessChecker.LIVENESS_HORIZON_MS, "50");
    private static final Map<String, String> WIDE_HORIZON = Map.of(LivenessChecker.LIVENESS_HORIZON_MS, "5000");

    @Nested
    class DeliveryLatency {

        @Test
        void reportsAnEventThatTookLongerThanTheDeclaredHorizon(@TempDir Path directory) {
            // given a commit whose event only reached a consumer well past the horizon the run declared
            SyntheticHistory history = new SyntheticHistory(directory, "slow_delivery", TIGHT_HORIZON);
            history.commit("e1");
            history.pause(120);
            history.deliver("e1");
            history.scan("e1");
            history.settled(true);

            // when the liveness oracle judges it
            CheckResult result = new LivenessChecker().check(history.view());

            // then the lateness must be reported
            assertThat(result.violations()).hasSize(1);
            assertThat(result.violations().getFirst().machineName())
                    .isEqualTo(LivenessChecker.COMMITTED_EVENT_DELIVERED_WITHIN_HORIZON);
            assertThat(result.violations().getFirst().detail()).contains("e1", "past the declared horizon of 50ms");
        }

        @Test
        void acceptsAnEventDeliveredInsideTheHorizon(@TempDir Path directory) {
            // given the same shape with a horizon nothing exceeded
            SyntheticHistory history = new SyntheticHistory(directory, "prompt_delivery", WIDE_HORIZON);
            history.commit("e1");
            history.deliver("e1");
            history.scan("e1");
            history.settled(true);

            // when the liveness oracle judges it
            CheckResult result = new LivenessChecker().check(history.view());

            // then nothing is reported
            assertThat(result.holds()).isTrue();
        }

        @Test
        void staysSilentAboutAnEventThatNeverArrivedAtAll(@TempDir Path directory) {
            // given a committed event with no delivery anywhere in the history
            SyntheticHistory history = new SyntheticHistory(directory, "never_delivered", TIGHT_HORIZON);
            history.commit("e1", "e2");
            history.deliver("e1");
            history.scan("e1", "e2");
            history.settled(true);

            // when the liveness oracle judges it
            CheckResult result = new LivenessChecker().check(history.view());

            // then it says nothing: an event that never arrived is loss, and loss belongs to the delivery oracle
            assertThat(result.holds()).isTrue();
        }

        @Test
        void staysSilentWhenTheRunNeverCaughtUp(@TempDir Path directory) {
            // given a late delivery in a run whose read side was still behind when it ended
            SyntheticHistory history = new SyntheticHistory(directory, "slow_but_interrupted", TIGHT_HORIZON);
            history.commit("e1");
            history.pause(120);
            history.deliver("e1");
            history.scan("e1");
            history.settled(false);

            // when the liveness oracle judges it
            CheckResult result = new LivenessChecker().check(history.view());

            // then a run that was cut short is not reported as a slow one
            assertThat(result.holds()).isTrue();
        }
    }

    @Nested
    class CommandCompletion {

        @Test
        void reportsACommandThatNeverReachedAnOutcome(@TempDir Path directory) {
            // given a dispatched command with no completion record of any kind
            SyntheticHistory history = new SyntheticHistory(directory, "unfinished_command", WIDE_HORIZON);
            history.writer().invoke(HistoryOps.TRANSFER, "acct-0", Map.of("kind", "transfer"));
            history.settled(true);

            // when the liveness oracle judges it
            CheckResult result = new LivenessChecker().check(history.view());

            // then the command that stopped must be reported
            assertThat(result.violations()).hasSize(1);
            assertThat(result.violations().getFirst().machineName())
                    .isEqualTo(LivenessChecker.ACCEPTED_COMMAND_COMPLETES);
        }

        @Test
        void reportsAnUndeterminedOutcomeWithoutBlamingIt(@TempDir Path directory) {
            // given a command that timed out, so it finished but what it did is unknown
            SyntheticHistory history = new SyntheticHistory(directory, "undetermined_command", WIDE_HORIZON);
            history.writer().invoke(HistoryOps.TRANSFER, "acct-0", Map.of("kind", "transfer"))
                   .indeterminate("TimeoutException", Map.of());
            history.settled(true);

            // when the liveness oracle judges it
            CheckResult result = new LivenessChecker().check(history.view());

            // then it is an ambiguity, reported, and not a liveness failure
            assertThat(result.violations()).isEmpty();
            assertThat(result.notes()).hasSize(1);
            assertThat(result.notes().getFirst()).contains("undetermined outcome");
        }
    }

    @Nested
    class ClusterAvailability {

        @Test
        void reportsANodeThatNeverCameUp(@TempDir Path directory) {
            // given a node whose last recorded lifecycle event is a failed start
            SyntheticHistory history = new SyntheticHistory(directory, "node_down", WIDE_HORIZON);
            history.nodeAction("node-0", "started");
            history.nodeAction("node-1", "start-failed");
            history.settled(true);

            // when the liveness oracle judges it
            CheckResult result = new LivenessChecker().check(history.view());

            // then the run cannot be a clean pass: it exercised a smaller cluster than it declared
            assertThat(result.violations()).isEmpty();
            assertThat(result.notes()).hasSize(1);
            assertThat(result.notes().getFirst()).contains("node-1", "never came up");
        }

        @Test
        void staysSilentAboutANodeThatCameUpOnARetry(@TempDir Path directory) {
            // given a node that failed once and then started
            SyntheticHistory history = new SyntheticHistory(directory, "node_recovered", WIDE_HORIZON);
            history.nodeAction("node-1", "start-failed");
            history.nodeAction("node-1", "started-after-retry");
            history.settled(true);

            // when the liveness oracle judges it
            CheckResult result = new LivenessChecker().check(history.view());

            // then it was up for the part of the run the oracles judge
            assertThat(result.holds()).isTrue();
        }
    }
}
