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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plants losses and repeats and checks which ones the delivery oracle catches, under each declared mode.
 * <p>
 * Both invariants get their own planted defect, and both modes get exercised, because the two modes differ on exactly
 * one question: whether a repeat inside a recovery window is permitted. A checker that only ever ran under one of
 * them would have half its logic unwatched.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
class DeliveryCheckerTest {

    private static Map<String, String> shape(String mode) {
        Map<String, String> shape = new LinkedHashMap<>();
        shape.put(DeliveryChecker.DELIVERY_MODE, mode);
        shape.put(OwnershipChecker.CLAIM_TIMEOUT_MS, "200");
        shape.put(OwnershipChecker.SKEW_ALLOWANCE_MS, "0");
        return Map.copyOf(shape);
    }

    @Nested
    class AtLeastOnceDelivery {

        @Test
        void reportsACommittedEventThatNeverReachedAConsumer(@TempDir Path directory) {
            // given two committed events of which only one was delivered
            SyntheticHistory history =
                    new SyntheticHistory(directory, "lost_event", shape("AT_LEAST_ONCE_NO_LOSS"));
            history.commit("e1", "e2");
            history.deliver("e1");
            history.scan("e1", "e2");
            history.settled(true);

            // when the delivery oracle judges it
            CheckResult result = new DeliveryChecker().check(history.view());

            // then the loss must be reported, and loss is never permitted under any mode
            assertThat(result.violations()).hasSize(1);
            assertThat(result.violations().getFirst().machineName())
                    .isEqualTo(DeliveryChecker.NO_COMMITTED_EVENT_GOES_UNDELIVERED);
            assertThat(result.violations().getFirst().detail()).contains("e2");
        }

        @Test
        void reportsARepeatWithNothingHappeningAroundIt(@TempDir Path directory) {
            // given an event delivered twice in a run where no claim changed hands and no node moved
            SyntheticHistory history =
                    new SyntheticHistory(directory, "unlicensed_repeat", shape("AT_LEAST_ONCE_NO_LOSS"));
            history.commit("e1");
            history.deliver("e1");
            history.deliver("e1");
            history.scan("e1");
            history.settled(true);

            // when the delivery oracle judges it
            CheckResult result = new DeliveryChecker().check(history.view());

            // then the repeat must be reported: nothing licensed it
            assertThat(result.violations()).hasSize(1);
            assertThat(result.violations().getFirst().machineName())
                    .isEqualTo(DeliveryChecker.DUPLICATE_DELIVERY_ONLY_INSIDE_RECOVERY_WINDOW);
        }

        @Test
        void permitsARepeatInsideAClaimHandover(@TempDir Path directory) {
            // given a segment changing hands, and the repeat landing inside the window that opens
            SyntheticHistory history =
                    new SyntheticHistory(directory, "repeat_after_handover", shape("AT_LEAST_ONCE_NO_LOSS"));
            history.commit("e1");
            history.deliver("e1");
            history.claimGranted("node-a", 0);
            history.claimGranted("node-b", 0);
            history.deliver("e1");
            history.scan("e1");
            history.settled(true);

            // when the delivery oracle judges it
            CheckResult result = new DeliveryChecker().check(history.view());

            // then it is permitted, and counted rather than passed over in silence
            assertThat(result.violations()).isEmpty();
            assertThat(result.notes()).hasSize(1);
            assertThat(result.notes().getFirst()).contains("1 repeat(s) inside a recovery window");
        }

        @Test
        void permitsARepeatInsideANodeRecovery(@TempDir Path directory) {
            // given a node dropped and brought back, and the repeat landing inside the window that opens
            SyntheticHistory history =
                    new SyntheticHistory(directory, "repeat_after_crash", shape("AT_LEAST_ONCE_NO_LOSS"));
            history.commit("e1");
            history.deliver("e1");
            history.nodeAction("node-a", "crashed");
            history.deliver("e1");
            history.scan("e1");
            history.settled(true);

            // when the delivery oracle judges it
            CheckResult result = new DeliveryChecker().check(history.view());

            // then it is permitted
            assertThat(result.violations()).isEmpty();
            assertThat(result.notes().getFirst()).contains("1 repeat(s) inside a recovery window");
        }

        @Test
        void reportsARepeatThatArrivedLongAfterTheWindowClosed(@TempDir Path directory) {
            // given a handover, and a repeat arriving well past the claim timeout that bounds its window
            SyntheticHistory history =
                    new SyntheticHistory(directory, "repeat_after_window", shape("AT_LEAST_ONCE_NO_LOSS"));
            history.commit("e1");
            history.deliver("e1");
            history.claimGranted("node-a", 0);
            history.claimGranted("node-b", 0);
            history.pause(260);
            history.deliver("e1");
            history.scan("e1");
            history.settled(true);

            // when the delivery oracle judges it
            CheckResult result = new DeliveryChecker().check(history.view());

            // then the window had closed, so the repeat is a failure
            assertThat(result.violations()).hasSize(1);
            assertThat(result.violations().getFirst().machineName())
                    .isEqualTo(DeliveryChecker.DUPLICATE_DELIVERY_ONLY_INSIDE_RECOVERY_WINDOW);
        }
    }

    @Nested
    class ExactlyOnceDelivery {

        @Test
        void reportsARepeatEvenInsideAClaimHandover(@TempDir Path directory) {
            // given exactly the history the at-least-once mode permits
            SyntheticHistory history =
                    new SyntheticHistory(directory, "exactly_once_repeat", shape(DeliveryChecker.EXACTLY_ONCE));
            history.commit("e1");
            history.deliver("e1");
            history.claimGranted("node-a", 0);
            history.claimGranted("node-b", 0);
            history.deliver("e1");
            history.scan("e1");
            history.settled(true);

            // when the delivery oracle judges it under the mode the deployment declared
            CheckResult result = new DeliveryChecker().check(history.view());

            // then nothing licenses a repeat here, which is the whole difference between the two modes
            assertThat(result.violations()).hasSize(1);
            assertThat(result.violations().getFirst().detail()).contains("exactly-once");
        }
    }

    @Nested
    class AHistoryTheOracleMustNotDecide {

        @Test
        void reportsRatherThanBlamesWhenTheReadSideNeverCaughtUp(@TempDir Path directory) {
            // given an undelivered event in a run whose read side was still behind when it ended
            SyntheticHistory history =
                    new SyntheticHistory(directory, "not_quiesced", shape("AT_LEAST_ONCE_NO_LOSS"));
            history.commit("e1", "e2");
            history.deliver("e1");
            history.scan("e1", "e2");
            history.settled(false);

            // when the delivery oracle judges it
            CheckResult result = new DeliveryChecker().check(history.view());

            // then it says what it saw and refuses to call it loss
            assertThat(result.violations()).isEmpty();
            assertThat(result.notes().getFirst()).contains("not judged as loss because",
                                                           "the read side had not caught up");
        }

        @Test
        void reportsRatherThanBlamesWhenAFaultRewroteTheStore(@TempDir Path directory) {
            // given an undelivered event in a run where a fault made the store keep something else
            SyntheticHistory history =
                    new SyntheticHistory(directory, "store_perturbed", shape("AT_LEAST_ONCE_NO_LOSS"));
            history.commit("e1", "e2");
            history.deliver("e1");
            history.scan("e1", "e2");
            history.storePerturbed("vanished");
            history.settled(true);

            // when the delivery oracle judges it
            CheckResult result = new DeliveryChecker().check(history.view());

            // then the harness's own damage is not reported as the framework's
            assertThat(result.violations()).isEmpty();
            assertThat(result.notes().getFirst()).contains("a fault made the store hold something other than what "
                                                                   + "was offered");
        }

        @Test
        void holdsSilentlyWhenTheRunNeverSaidWhetherItCaughtUp(@TempDir Path directory) {
            // given a history written before the run recorded that field
            SyntheticHistory history = new SyntheticHistory(directory, "no_settle_record");
            history.commit("e1", "e2");
            history.deliver("e1");
            history.scan("e1", "e2");

            // when the delivery oracle judges it
            CheckResult result = new DeliveryChecker().check(history.view());

            // then it leaves the history alone rather than calling an interruption a loss
            assertThat(result.holds()).isTrue();
            assertThat(result.notes()).isEmpty();
        }
    }
}
