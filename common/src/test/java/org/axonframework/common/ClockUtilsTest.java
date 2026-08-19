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

package org.axonframework.common;

import org.junit.jupiter.api.*;

import java.lang.reflect.Constructor;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClockUtilsTest {

    @AfterEach
    void resetClock() {
        ClockUtils.reset();
    }

    @Nested
    class GetSetReset {

        @Test
        void getReturnsDefaultClockByDefault() {
            // when
            Clock clock = ClockUtils.get();

            // then
            assertThat(clock).isEqualTo(Clock.systemUTC());
        }

        @Test
        void setStoresAndReturnsProvidedClock() {
            // given
            Clock clock = Clock.fixed(Instant.parse("2024-01-01T12:30:45Z"), ZoneOffset.UTC);

            // when
            Clock returnedClock = ClockUtils.set(clock);

            // then
            assertThat(returnedClock).isSameAs(clock);
            assertThat(ClockUtils.get()).isSameAs(clock);
        }

        @Test
        void resetRestoresDefaultClock() {
            // given
            Clock clock = Clock.fixed(Instant.parse("2024-01-01T12:30:45Z"), ZoneOffset.UTC);
            ClockUtils.set(clock);

            // when
            Clock resetClock = ClockUtils.reset();

            // then
            assertThat(resetClock).isEqualTo(Clock.systemUTC());
            assertThat(ClockUtils.get()).isEqualTo(Clock.systemUTC());
        }
    }

    @Nested
    class InstantSourceBehavior {

        @Test
        void instantDelegatesToConfiguredClock() throws Exception {
            // given
            Instant instant = Instant.parse("2024-01-01T12:30:45Z");
            Clock clock = Clock.fixed(instant, ZoneOffset.UTC);
            ClockUtils.set(clock);

            // when
            Instant result = ClockUtils.instant();

            // then
            assertThat(result).isEqualTo(instant);
        }
    }

    @Nested
    class UtilityClass {

        @Test
        void constructorIsNotAccessible() throws Exception {
            // when
            Constructor<ClockUtils> constructor = ClockUtils.class.getDeclaredConstructor();

            // then
            assertThatThrownBy(constructor::newInstance)
                    .isInstanceOf(IllegalAccessException.class);
        }
    }
}
