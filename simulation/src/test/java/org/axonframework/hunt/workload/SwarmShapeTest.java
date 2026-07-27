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

package org.axonframework.hunt.workload;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks that the shape of a run's contention is a function of its seed, and that the hot-key shape is genuinely hot.
 * <p>
 * The second half matters more than it looks. A distribution that is merely "a bit skewed" produces almost no
 * conflicts, and a conflict scenario with no conflicts in it passes while testing nothing.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
class SwarmShapeTest {

    @Nested
    class DerivedEntirelyFromTheSeed {

        @ParameterizedTest
        @ValueSource(longs = {1L, 42L, 12345L})
        void theSameSeedAlwaysProducesTheSameShape(long seed) {
            // given / when
            SwarmShape first = SwarmShape.of(seed);
            SwarmShape second = SwarmShape.of(seed);

            // then
            assertThat(first).isEqualTo(second);
            assertThat(first.describe()).isEqualTo(second.describe());
        }

        @Test
        void differentSeedsProduceDifferentShapes() {
            // given / when / then
            assertThat(SwarmShape.of(1L)).isNotEqualTo(SwarmShape.of(2L));
        }

        @Test
        void theHotKeyFactoryPinsTheDistributionAndLeavesTheRestToTheSeed() {
            // given / when
            SwarmShape shape = SwarmShape.zipf(7L);

            // then
            assertThat(shape.distribution()).isEqualTo(SwarmShape.Distribution.ZIPF);
            assertThat(shape.writers()).isIn(2, 4, 8, 16);
            assertThat(shape.accounts()).isGreaterThanOrEqualTo(2);
            assertThat(shape.describe()).containsEntry("zipfExponent", "1.0");
        }
    }

    @Nested
    class TheHotKeyDistribution {

        @Test
        void putsFarMoreTrafficOnTheFirstAccountThanAUniformSpreadWould() {
            // given a hot-key shape and a uniform one over the same account pool
            SwarmShape hot = SwarmShape.zipf(7L);
            int samples = 20_000;

            // when the first account's share of the traffic is measured
            Map<Integer, Integer> counts = new HashMap<>();
            Random random = new Random(1L);
            for (int sample = 0; sample < samples; sample++) {
                counts.merge(hot.pickAccount(random), 1, Integer::sum);
            }
            double hottestShare = counts.getOrDefault(0, 0) / (double) samples;
            double uniformShare = 1.0 / hot.accounts();

            // then the hottest account must take a clear multiple of its uniform share, or the conflict paths this
            // shape exists to reach will not be reached
            assertThat(hottestShare).as("share of traffic on the hottest of %d accounts", hot.accounts())
                                    .isGreaterThan(uniformShare * 2);
        }

        @Test
        void neverPicksAnAccountOutsideThePool() {
            // given
            SwarmShape shape = SwarmShape.zipf(3L);
            Random random = new Random(5L);

            // when / then
            for (int sample = 0; sample < 5_000; sample++) {
                assertThat(shape.pickAccount(random)).isBetween(0, shape.accounts() - 1);
                assertThat(shape.pickBatch(random)).isBetween(shape.minBatch(), shape.maxBatch());
            }
        }
    }
}
