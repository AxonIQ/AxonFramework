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

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.axonframework.extension.kotlin.ExampleCommand
import org.axonframework.messaging.commandhandling.gateway.CommandGateway
import org.axonframework.messaging.commandhandling.gateway.CommandResult
import java.util.concurrent.CompletableFuture
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertSame

/**
 * Tests Command Gateway extensions.
 *
 * @author Stefan Andjelkovic
 */
internal class CommandGatewayExtensionsTest {
    private val subjectGateway = mockk<CommandGateway>()
    private val exampleCommand = ExampleCommand("1")

    @AfterTest
    fun tearDown() {
        clearMocks(subjectGateway)
    }

    @Test
    fun `sendWithResult extension should invoke send with result class`() {
        val future = CompletableFuture.completedFuture("result")
        every { subjectGateway.send(exampleCommand as Any, String::class.java) } returns future

        val result = subjectGateway.sendWithResult<String>(exampleCommand)

        assertSame(future, result)
        verify(exactly = 1) { subjectGateway.send(exampleCommand as Any, String::class.java) }
    }

    @Test
    fun `sendAndWait extension should invoke sendAndWait with result class`() {
        every { subjectGateway.sendAndWait(exampleCommand as Any, String::class.java) } returns "42"

        val result = subjectGateway.sendAndWait<String>(exampleCommand)

        kotlin.test.assertEquals("42", result)
        verify(exactly = 1) { subjectGateway.sendAndWait(exampleCommand as Any, String::class.java) }
    }
}
