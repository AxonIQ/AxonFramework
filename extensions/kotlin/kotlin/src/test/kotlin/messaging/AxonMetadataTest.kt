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

package org.axonframework.extension.kotlin.messaging

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class AxonMetadataTest {

    @Test
    fun `returns true when both are empty`() {
        val metadata = AxonMetadata.emptyInstance()
        val other = AxonMetadata.emptyInstance()

        assertThat(metadata.contains(other)).isTrue()
    }

    @Test
    fun `returns true when other is empty`() {
        val metadata = AxonMetadata.with("key", "value")
        val other = AxonMetadata.emptyInstance()

        assertThat(metadata.contains(other)).isTrue()
    }

    @Test
    fun `returns true when both have same single entry`() {
        val metadata = AxonMetadata.with("gameId", "game-1")
        val other = AxonMetadata.with("gameId", "game-1")

        assertThat(metadata.contains(other)).isTrue()
    }

    @Test
    fun `returns true when metadata is a superset`() {
        val metadata = AxonMetadata.with("gameId", "game-1")
            .and("playerId" to "player-1")
            .and("extra" to "data")
        val other = AxonMetadata.with("gameId", "game-1")
            .and("playerId" to "player-1")

        assertThat(metadata.contains(other)).isTrue()
    }

    @Test
    fun `returns false when key is missing`() {
        val metadata = AxonMetadata.with("gameId", "game-1")
        val other = AxonMetadata.with("gameId", "game-1")
            .and("playerId" to "player-1")

        assertThat(metadata.contains(other)).isFalse()
    }

    @Test
    fun `returns false when value differs`() {
        val metadata = AxonMetadata.with("gameId", "game-1")
        val other = AxonMetadata.with("gameId", "game-2")

        assertThat(metadata.contains(other)).isFalse()
    }

    @Test
    fun `returns false when this is empty but other is not`() {
        val metadata = AxonMetadata.emptyInstance()
        val other = AxonMetadata.with("gameId", "game-1")

        assertThat(metadata.contains(other)).isFalse()
    }

    @Test
    fun `returns true with multiple matching entries among extras`() {
        val metadata = AxonMetadata.with("a", "1")
            .and("b" to "2")
            .and("c" to "3")
            .and("d" to "4")
        val other = AxonMetadata.with("b", "2")
            .and("d" to "4")

        assertThat(metadata.contains(other)).isTrue()
    }
}
