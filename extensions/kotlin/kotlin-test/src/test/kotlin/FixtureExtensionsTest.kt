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

package org.axonframework.extension.kotlin.test

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.axonframework.test.fixture.AxonTestPhase
import kotlin.test.Test
import kotlin.test.assertSame

internal class FixtureExtensionsTest {

    @Test
    fun `Whenever on Setup should delegate to when()`() {
        val mockWhen = mockk<AxonTestPhase.When>()
        val setup = mockk<AxonTestPhase.Setup>()
        every { setup.whenever() } returns mockWhen

        val result = setup.whenever()

        assertSame(mockWhen, result)
        verify(exactly = 1) { setup.whenever() }
    }

    @Test
    fun `Whenever with command on Setup should delegate to when() then command()`() {
        val cmd = Any()
        val mockWhenCommand = mockk<AxonTestPhase.When.Command>()
        val mockWhen = mockk<AxonTestPhase.When>()
        val setup = mockk<AxonTestPhase.Setup>()
        every { setup.whenever() } returns mockWhen
        every { mockWhen.command(cmd) } returns mockWhenCommand

        val result = setup.whenever(cmd)

        assertSame(mockWhenCommand, result)
        verify(exactly = 1) { setup.whenever() }
        verify(exactly = 1) { mockWhen.command(cmd) }
    }

    @Test
    fun `Whenever on Given should delegate to when()`() {
        val mockWhen = mockk<AxonTestPhase.When>()
        val given = mockk<AxonTestPhase.Given>()
        every { given.whenever() } returns mockWhen

        val result = given.whenever()

        assertSame(mockWhen, result)
        verify(exactly = 1) { given.whenever() }
    }

    @Test
    fun `Whenever with command on Given should delegate to when() then command()`() {
        val cmd = Any()
        val mockWhenCommand = mockk<AxonTestPhase.When.Command>()
        val mockWhen = mockk<AxonTestPhase.When>()
        val given = mockk<AxonTestPhase.Given>()
        every { given.whenever() } returns mockWhen
        every { mockWhen.command(cmd) } returns mockWhenCommand

        val result = given.whenever(cmd)

        assertSame(mockWhenCommand, result)
        verify(exactly = 1) { given.whenever() }
        verify(exactly = 1) { mockWhen.command(cmd) }
    }

    @Test
    fun `Exception extension on ThenMessage should accept KClass`() {
        val thenNothing = mockk<AxonTestPhase.Then.Nothing>()
        every { thenNothing.exception(Exception::class.java) } returns thenNothing

        val result = thenNothing.exception(Exception::class)

        assertSame(thenNothing, result)
        verify(exactly = 1) { thenNothing.exception(Exception::class.java) }
    }

    @Test
    fun `Exception with message extension on ThenMessage should accept KClass`() {
        val thenNothing = mockk<AxonTestPhase.Then.Nothing>()
        every { thenNothing.exception(Exception::class.java, "some message") } returns thenNothing

        val result = thenNothing.exception(Exception::class, "some message")

        assertSame(thenNothing, result)
        verify(exactly = 1) { thenNothing.exception(Exception::class.java, "some message") }
    }
}