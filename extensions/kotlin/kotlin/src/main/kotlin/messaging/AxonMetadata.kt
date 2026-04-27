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

import org.axonframework.messaging.core.Message
import org.axonframework.messaging.core.Metadata

/**
 * An alias for [Metadata] to avoid confusion with kotlins internal [kotlin.Metadata] class.
 *
 * @since 5.1.0
 */
typealias AxonMetadata = Metadata

/**
 * Convenience extension function to access the [AxonMetadata] of a [Message].
 * Kotlin supports `metadata["key"]` for getting a value from a map and would otherwise be `metadata()["key"]`.
 *
 * @since 5.1.0
 */
val Message.metadata: AxonMetadata get() = metadata()


/**
 * Convenience extension function to add a key-value pair to the [AxonMetadata] in kotlin style.
 * @param entry The key-value pair to add to the metadata
 *
 * @since 5.1.0
 */
fun AxonMetadata.and(entry: Pair<String, String?>): AxonMetadata = this.and(entry.first, entry.second)

/**
 * Checks whether this [Metadata][AxonMetadata] contains all entries from the [other] metadata.
 *
 * Returns `true` if every key-value pair in [other] is present in this metadata
 * with the same value. An empty [other] metadata always returns `true`.
 *
 * @param other The metadata whose entries must all be present in this metadata
 * @return `true` if this metadata contains all key-value pairs from [other], `false` otherwise
 * @since 5.1.0
 */
fun AxonMetadata.contains(other: AxonMetadata): Boolean = other.entries.all { (key, value) ->
    this[key] == value
}
