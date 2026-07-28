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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Planted-bad histories for the per-key ordering oracle.
 * <p>
 * The point of every case here is that the checker can fail. A checker that has only ever been shown holding is
 * indistinguishable from one that compares nothing, so each rule it enforces gets a history in which that rule is
 * broken on purpose, alongside a sound history it must not complain about.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
class OrderCheckerTest {

    private final OrderChecker checker = new OrderChecker();

    @TempDir
    private Path directory;

    @Nested
    class AHistoryThatObeysTheOrdering {

        @Test
        void holdsWhenEveryKeyIsDeliveredInAppendOrder() {
            // given two keys, each delivered in the order the store holds their events
            SyntheticHistory history = new SyntheticHistory(directory, "ordered");
            history.scan("a-0", "b-0", "a-1", "b-1", "a-2");
            history.deliverUnderKey("a-0", "key-a");
            history.deliverUnderKey("b-0", "key-b");
            history.deliverUnderKey("a-1", "key-a");
            history.deliverUnderKey("b-1", "key-b");
            history.deliverUnderKey("a-2", "key-a");

            // when
            CheckResult result = checker.check(history.view());

            // then
            assertThat(result.violations()).isEmpty();
            assertThat(result.notes()).isEmpty();
        }

        @Test
        void ignoresDeliveriesThatCarryNoSequenceIdentifier() {
            // given a workload that does not track sequencing, delivering wildly out of store order
            SyntheticHistory history = new SyntheticHistory(directory, "untracked");
            history.scan("e-0", "e-1", "e-2");
            history.deliver("e-2");
            history.deliver("e-0");
            history.deliver("e-1");

            // when
            CheckResult result = checker.check(history.view());

            // then a checker with no identifier to judge by must hold rather than invent one
            assertThat(result.violations()).isEmpty();
            assertThat(result.notes()).isEmpty();
        }
    }

    @Nested
    class AHistoryThatBreaksTheOrdering {

        @Test
        void isCaughtWhenOneKeyIsDeliveredOutOfAppendOrder() {
            // given one key whose second and third events arrive the wrong way round
            SyntheticHistory history = new SyntheticHistory(directory, "inverted");
            history.scan("a-0", "a-1", "a-2");
            history.deliverUnderKey("a-0", "key-a");
            history.deliverUnderKey("a-2", "key-a");
            history.deliverUnderKey("a-1", "key-a");

            // when
            CheckResult result = checker.check(history.view());

            // then
            assertThat(result.violations()).hasSize(1);
            assertThat(result.violations().getFirst().machineName())
                    .isEqualTo(OrderChecker.SEQUENCE_KEY_ORDER_PRESERVED);
            assertThat(result.violations().getFirst().detail()).contains("key-a").contains("a-1");
        }

        @Test
        void doesNotBlameOneKeyForAnotherKeysInterleaving() {
            // given two keys whose events interleave in an order neither key breaks on its own
            SyntheticHistory history = new SyntheticHistory(directory, "interleaved");
            history.scan("a-0", "b-0", "a-1", "b-1");
            history.deliverUnderKey("b-0", "key-b");
            history.deliverUnderKey("b-1", "key-b");
            history.deliverUnderKey("a-0", "key-a");
            history.deliverUnderKey("a-1", "key-a");

            // when
            CheckResult result = checker.check(history.view());

            // then sequencing orders a key against itself, never one key against another
            assertThat(result.violations()).isEmpty();
        }
    }

    @Nested
    class AHistoryTheCheckerCannotDecide {

        @Test
        void reportsANoteWhenTheRunRecordedNoAuthoritativeScan() {
            // given deliveries with no scan to place them against
            SyntheticHistory history = new SyntheticHistory(directory, "unscanned");
            history.deliverUnderKey("a-1", "key-a");
            history.deliverUnderKey("a-0", "key-a");

            // when
            CheckResult result = checker.check(history.view());

            // then the append order is unknown, so nothing may be decided
            assertThat(result.violations()).isEmpty();
            assertThat(result.notes()).anyMatch(note -> note.contains("no authoritative scan"));
        }

        @Test
        void reportsANoteWhenADeliveredEventIsAbsentFromTheScan() {
            // given a delivery of an event the store does not hold
            SyntheticHistory history = new SyntheticHistory(directory, "unplaceable");
            history.scan("a-0");
            history.deliverUnderKey("a-0", "key-a");
            history.deliverUnderKey("ghost", "key-a");

            // when
            CheckResult result = checker.check(history.view());

            // then
            assertThat(result.violations()).isEmpty();
            assertThat(result.notes()).anyMatch(note -> note.contains("absent from the authoritative scan"));
        }

        @Test
        void reportsARepeatedDeliveryAsADuplicationRatherThanAReordering() {
            // given the same event delivered twice
            SyntheticHistory history = new SyntheticHistory(directory, "repeated");
            history.scan("a-0", "a-1");
            history.deliverUnderKey("a-0", "key-a");
            history.deliverUnderKey("a-1", "key-a");
            history.deliverUnderKey("a-0", "key-a");

            // when
            CheckResult result = checker.check(history.view());

            // then the repeat is surfaced, and it is not reported as an ordering break
            assertThat(result.violations()).isEmpty();
            assertThat(result.notes()).anyMatch(note -> note.contains("repeated an event already delivered"));
        }
    }
}
