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
 * Plants an acknowledgement the store did not keep, and checks that the oracle catches it and only it.
 * <p>
 * The three client verdicts are exercised separately, because the whole value of this oracle is that it decides two of
 * them and refuses to decide the third. An append the client saw succeed is bound to the store; one whose conversation
 * was lost is bound to nothing, and a checker that held it to either answer would invent findings on every partition run.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
class DurabilityCheckerTest {

    private static Map<String, String> shape(String declaredFaults) {
        Map<String, String> shape = new LinkedHashMap<>();
        shape.put("declaredFaults", declaredFaults);
        return Map.copyOf(shape);
    }

    @Nested
    class AnAppendTheClientSawSucceed {

        @Test
        void isAViolationWhenTheStoreDoesNotHoldIt(@TempDir Path directory) {
            // given two acknowledged appends of which the store only kept one
            SyntheticHistory history = new SyntheticHistory(directory, "acked_but_absent", shape("store-crash"));
            history.appendOkWith("e1");
            history.appendOkWith("e2");
            history.scan("e1");
            history.settled(true);

            // when the durability oracle judges it
            CheckResult result = new DurabilityChecker().check(history.view());

            // then the acknowledgement the store did not keep is the violation, and it names the event
            assertThat(result.violations()).hasSize(1);
            assertThat(result.violations().getFirst().machineName())
                    .isEqualTo(DurabilityChecker.ACKNOWLEDGED_APPEND_IS_DURABLE);
            assertThat(result.violations().getFirst().detail()).contains("e2");
        }

        @Test
        void isAViolationWhenTheStoreHoldsItTwice(@TempDir Path directory) {
            // given one acknowledged append whose event the store kept twice
            SyntheticHistory history = new SyntheticHistory(directory, "acked_but_doubled", shape("store-crash"));
            history.appendOkWith("e1");
            history.scan("e1", "e1");
            history.settled(true);

            // when the durability oracle judges it
            CheckResult result = new DurabilityChecker().check(history.view());

            // then exactly once is part of the statement, so a duplicate the caller never asked for is a violation too
            assertThat(result.violations()).hasSize(1);
            assertThat(result.violations().getFirst().detail()).contains("e1 x2");
        }

        @Test
        void holdsWhenTheStoreHoldsItExactlyOnce(@TempDir Path directory) {
            // given two acknowledged appends the store kept, each once
            SyntheticHistory history = new SyntheticHistory(directory, "acked_and_present", shape("store-crash"));
            history.appendOkWith("e1");
            history.appendOkWith("e2");
            history.scan("e1", "e2");
            history.settled(true);

            // when the durability oracle judges it
            CheckResult result = new DurabilityChecker().check(history.view());

            // then nothing is broken, and the client's verdict set is published so that a reader can see what was tested
            assertThat(result.violations()).isEmpty();
            assertThat(result.measurements()).anySatisfy(measured -> assertThat(measured).contains("2 acknowledged"));
        }
    }

    @Nested
    class AnAppendWhoseConversationWasLost {

        @Test
        void isHeldToNothingWhateverTheStoreDidWithIt(@TempDir Path directory) {
            // given one append that failed with a driver exception rather than with a decision, and which the store turns
            // out to have applied anyway -- which is precisely what a reply lost on a broken connection looks like
            SyntheticHistory history = new SyntheticHistory(directory, "ambiguous_but_stored", shape("store-partition"));
            history.appendLostConversation("e1");
            history.appendLostConversation("e2");
            history.scan("e1");
            history.settled(true);

            // when the durability oracle judges it
            CheckResult result = new DurabilityChecker().check(history.view());

            // then neither answer is a violation: the request may have landed and the reply may have been lost, so the
            // store is free either way. Holding it to the failure the client saw is how a partition scenario invents
            // findings.
            assertThat(result.violations()).isEmpty();

            // and the ambiguity is published with how much of it the store turned out to hold, because a run that
            // produced none of it has not tested this at all
            assertThat(result.measurements())
                    .anySatisfy(measured -> assertThat(measured).contains("2 event(s) left ambiguous",
                                                                          "of which 1 turned out to be stored"));
        }
    }

    @Nested
    class ARunThatSoughtAmbiguityAndFoundNone {

        @Test
        void givesUpItsVerdictInsteadOfReportingDurabilityItDidNotTest(@TempDir Path directory) {
            // given a run that declared a fault whose whole purpose is to make an acknowledgement ambiguous, and in which
            // every append got a clean answer
            SyntheticHistory history = new SyntheticHistory(directory, "nemesis_missed", shape("store-partition"));
            history.appendOkWith("e1");
            history.scan("e1");
            history.settled(true);

            // when the durability oracle judges it
            CheckResult result = new DurabilityChecker().check(history.view());

            // then nothing is broken and nothing is claimed: the nemesis never reached a commit window, so the run says so
            // and gives up its pass rather than reporting a guarantee it did not exercise
            assertThat(result.violations()).isEmpty();
            assertThat(result.notes())
                    .anySatisfy(note -> assertThat(note).contains("produced no ambiguous append at all"));
        }

        @Test
        void saysNothingAtAllWhenNoSuchFaultWasDeclared(@TempDir Path directory) {
            // given a run with no infrastructure fault, where a clean answer to every append is simply the normal case
            SyntheticHistory history = new SyntheticHistory(directory, "no_nemesis", shape(""));
            history.appendOkWith("e1");
            history.scan("e1");
            history.settled(true);

            // when the durability oracle judges it
            CheckResult result = new DurabilityChecker().check(history.view());

            // then the verdict stands: a note on every fault-free run would cost the three-valued verdict its meaning
            assertThat(result.violations()).isEmpty();
            assertThat(result.notes()).isEmpty();
        }
    }

    @Nested
    class ARunWhoseStoreTheHarnessItselfRewrote {

        @Test
        void isNotApplicableRatherThanBroken(@TempDir Path directory) {
            // given a run in which a fault made the store hold something other than what was offered, and an
            // acknowledged append whose events are consequently absent
            SyntheticHistory history = new SyntheticHistory(directory, "harness_destroyed_it", shape("write-then-vanish"));
            history.appendOkWith("e1");
            history.storePerturbed("vanish");
            history.scan();
            history.settled(true);

            // when the durability oracle judges it
            CheckResult result = new DurabilityChecker().check(history.view());

            // then the store is not blamed for data the harness destroyed, and the run records that the invariant was
            // inexpressible rather than passing quietly
            assertThat(result.violations()).isEmpty();
            assertThat(result.notApplicable())
                    .anySatisfy(statement -> assertThat(statement)
                            .contains(DurabilityChecker.ACKNOWLEDGED_APPEND_IS_DURABLE, "not expressible"));
        }
    }
}
