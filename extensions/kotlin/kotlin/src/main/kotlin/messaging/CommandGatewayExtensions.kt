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

import org.axonframework.messaging.commandhandling.gateway.CommandGateway
import java.util.concurrent.CompletableFuture

/**
 * Sends the given [command] and returns a [CompletableFuture] with the result typed as [R].
 *
 * @param command The command to send
 * @param R the expected result type
 * @return [CompletableFuture] of [R]
 * @see CommandGateway.send
 * @since 5.1.0
 */
inline fun <reified R : Any> CommandGateway.sendWithResult(command: Any): CompletableFuture<R> =
    this.send(command, R::class.java)

/**
 * Send the given [command] and waits for the result converted to the resultType.
 * Uses `inline/reified` to specify Java class parameters.
 *
 * @param command The command to send
 * @param R the generic type of the expected response
 * @return the result of type [R]
 * @see CommandGateway.sendAndWait
 * @since 5.1.0
 */
inline fun <reified R : Any> CommandGateway.sendAndWait(command: Any): R =
    // Cast to Any to route to CommandGateway.sendAndWait(Object, Class<R>) and avoid recursion.
    // The Java method is @Nullable; fail fast with a clear message rather than propagating null silently.
    this.sendAndWait(command, R::class.java) ?: error("sendAndWait returned null; if a null result is expected, use sendWithResult instead")
