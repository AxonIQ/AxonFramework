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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks that the scheduling-bias points are inert until armed, and count what they perturb once they are.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
class BuggifyTest {

    @Test
    void anUnarmedPointDoesNothingAndCountsNothing() {
        // given
        Buggify buggify = new Buggify(1L, 1.0);

        // when
        for (int reached = 0; reached < 100; reached++) {
            buggify.fire(Buggify.BEFORE_COMMIT);
        }

        // then a run that did not ask for scheduling bias gets none
        assertThat(buggify.fires(Buggify.BEFORE_COMMIT)).isZero();
        assertThat(buggify.deactivate()).isEmpty();
    }

    @Test
    void anArmedPointPerturbsAndReportsHowOften() {
        // given every reached point armed to fire
        Buggify buggify = new Buggify(1L, 1.0);
        buggify.activate();

        // when
        for (int reached = 0; reached < 20; reached++) {
            buggify.fire(Buggify.BEFORE_COMMIT);
        }

        // then the count is the evidence that the bias landed
        assertThat(buggify.fires(Buggify.BEFORE_COMMIT)).isEqualTo(20);
        assertThat(buggify.deactivate()).containsEntry(Buggify.BEFORE_COMMIT, 20);
    }

    @Test
    void anInertInstanceStaysInertEvenWhenArmed() {
        // given
        Buggify buggify = Buggify.inert();
        buggify.activate();

        // when
        buggify.fire(Buggify.BEFORE_APPEND);

        // then
        assertThat(buggify.fires(Buggify.BEFORE_APPEND)).isZero();
    }
}
