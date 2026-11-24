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

package extensions.kotlin.messaging

// tag::metadata[]
import org.axonframework.extension.kotlin.messaging.AxonMetadata
import org.axonframework.extension.kotlin.messaging.and
import org.axonframework.extension.kotlin.messaging.contains
import org.axonframework.extension.kotlin.messaging.metadata
import org.axonframework.messaging.core.Message

fun isFromGame(message: Message, gameId: String): Boolean {
    val expected = AxonMetadata.with("gameId", gameId)
    return message.metadata.contains(expected)
}

val gameMetadata = AxonMetadata.with("gameId", "game-1")
    .and("playerId" to "player-1")
// end::metadata[]
