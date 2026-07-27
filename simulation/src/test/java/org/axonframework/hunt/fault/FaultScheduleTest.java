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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Checks the fault-schedule grammar: its phases, and the composition count the tiers are enforced against.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
class FaultScheduleTest {

    private static Fault fault(String kind) {
        return new Fault() {
            @Override
            public String kind() {
                return kind;
            }

            @Override
            public void activate(FaultSite site, FaultEvidence evidence) {
                // Nothing to install: this schedule is never run.
            }

            @Override
            public void deactivate(FaultSite site) {
                // Nothing to remove.
            }
        };
    }

    @Nested
    class CountingSimultaneousFaults {

        @Test
        void aFaultFreeScheduleComposesNothing() {
            // given / when
            FaultSchedule schedule = FaultSchedule.none(Duration.ofSeconds(1));

            // then
            assertThat(schedule.maxConcurrentFaults()).isZero();
            assertThat(schedule.declaredFaults()).isEmpty();
            assertThat(schedule.faultPhase()).isEqualTo(Duration.ZERO);
        }

        @Test
        void twoWindowsThatDoNotOverlapComposeOneFaultAtATime() {
            // given two windows separated in time
            FaultSchedule schedule = FaultSchedule.builder()
                                                  .window(new FaultWindow("first", Duration.ZERO,
                                                                          Duration.ofMillis(100), java.util.List.of(
                                                          fault("a"))))
                                                  .window(new FaultWindow("second", Duration.ofMillis(200),
                                                                          Duration.ofMillis(100), java.util.List.of(
                                                          fault("b"))))
                                                  .build();

            // then a smoke tier may run it, because a failure is still attributable to one fault
            assertThat(schedule.maxConcurrentFaults()).isEqualTo(1);
            assertThat(schedule.faultPhase()).isEqualTo(Duration.ofMillis(300));
        }

        @Test
        void twoWindowsThatOverlapComposeTwoFaultsAtOnce() {
            // given two windows open at the same time
            FaultSchedule schedule = FaultSchedule.builder()
                                                  .window(new FaultWindow("first", Duration.ZERO,
                                                                          Duration.ofMillis(200), java.util.List.of(
                                                          fault("a"))))
                                                  .window(new FaultWindow("second", Duration.ofMillis(100),
                                                                          Duration.ofMillis(200), java.util.List.of(
                                                          fault("b"))))
                                                  .build();

            // then
            assertThat(schedule.maxConcurrentFaults()).isEqualTo(2);
        }

        @Test
        void oneWindowHoldingTwoFaultsComposesTwo() {
            // given / when
            FaultSchedule schedule = FaultSchedule.builder()
                                                  .window(FaultWindow.immediately("both", Duration.ofMillis(100),
                                                                                  fault("a"), fault("b")))
                                                  .build();

            // then
            assertThat(schedule.maxConcurrentFaults()).isEqualTo(2);
        }
    }

    @Nested
    class RejectingSchedulesThatCannotRun {

        @Test
        void aWindowWithNoFaultInIt() {
            // given / when / then
            assertThatThrownBy(() -> new FaultWindow("empty", Duration.ZERO, Duration.ofMillis(1), java.util.List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least one fault");
        }

        @Test
        void aNegativePhaseLength() {
            // given / when / then
            assertThatThrownBy(() -> FaultSchedule.builder().warmup(Duration.ofMillis(-1)).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("negative phase length");
        }
    }

    @Nested
    class DescribingItselfForTheHistoryHeader {

        @Test
        void namesEveryPhaseAndEveryDeclaredFault() {
            // given a single-fault schedule
            FaultSchedule schedule = FaultSchedule.single(fault("slow-store"), Duration.ofMillis(50),
                                                          Duration.ofMillis(200), Duration.ofSeconds(3));

            // when
            var described = schedule.describe();

            // then a reader of the history knows what was declared without reading the code
            assertThat(described).containsEntry("warmupMs", "50")
                                 .containsEntry("faultPhaseMs", "200")
                                 .containsEntry("settleMs", "3000")
                                 .containsEntry("declaredFaults", "slow-store")
                                 .containsEntry("maxConcurrentFaults", "1");
        }
    }
}
