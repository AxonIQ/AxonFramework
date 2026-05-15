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
package org.axonframework.extension.kotlin.eventsourcing

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class EvolveIfTest {

    @Test
    fun `evolveIf should evolve value when condition is true`() {
        val result = 1.evolveIf(true) { it + 1 }

        assertThat(result).isEqualTo(2)
    }

    @Test
    fun `evolveIf should return original value when condition is false`() {
        val result = 1.evolveIf(false) { it + 1 }

        assertThat(result).isEqualTo(1)
    }
}
