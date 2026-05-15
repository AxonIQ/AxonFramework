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

import org.axonframework.test.fixture.AxonTestPhase
import kotlin.reflect.KClass

/**
 * Alias for [AxonTestPhase.Setup.when] to avoid conflict with Kotlin's reserved `when` keyword.
 *
 * @return the [AxonTestPhase.When] phase
 * @see AxonTestPhase.Setup.when
 * @since 5.1.0
 */
fun AxonTestPhase.Setup.whenever(): AxonTestPhase.When = this.`when`()

/**
 * Convenience alias that combines [AxonTestPhase.Setup.when] and [AxonTestPhase.When.command] in one call,
 * avoiding Kotlin's reserved `when` keyword.
 *
 * @param command the command to dispatch in the when-phase
 * @return the [AxonTestPhase.When.Command] phase
 * @see AxonTestPhase.Setup.when
 * @see AxonTestPhase.When.command
 * @since 5.1.0
 */
fun AxonTestPhase.Setup.whenever(command: Any): AxonTestPhase.When.Command = this.whenever().command(command)

/**
 * Alias for [AxonTestPhase.Given.when] to avoid conflict with Kotlin's reserved `when` keyword.
 *
 * @return the [AxonTestPhase.When] phase
 * @see AxonTestPhase.Given.when
 * @since 5.1.0
 */
fun AxonTestPhase.Given.whenever(): AxonTestPhase.When = this.`when`()

/**
 * Convenience alias that combines [AxonTestPhase.Given.when] and [AxonTestPhase.When.command] in one call,
 * avoiding Kotlin's reserved `when` keyword.
 *
 * @param command the command to dispatch in the when-phase
 * @return the [AxonTestPhase.When.Command] phase
 * @see AxonTestPhase.Given.when
 * @see AxonTestPhase.When.command
 * @since 5.1.0
 */
fun AxonTestPhase.Given.whenever(command: Any): AxonTestPhase.When.Command = this.whenever().command(command)

/**
 * Accepts a [KClass] exception type instead of [Class], to provide idiomatic Kotlin usage.
 *
 * @param T the type of then-message
 * @param expectedException the [KClass] of the expected exception
 * @return this, for fluent interfacing
 * @see AxonTestPhase.Then.MessageAssertions.exception
 * @since 5.1.0
 */
fun <T : AxonTestPhase.Then.MessageAssertions<T>> T.exception(expectedException: KClass<out Throwable>): T =
    this.exception(expectedException.java)

/**
 * Accepts a [KClass] exception type with message instead of [Class], to provide idiomatic Kotlin usage.
 *
 * @param T the type of then-message
 * @param expectedException the [KClass] of the expected exception
 * @param message the expected exception message substring
 * @return this, for fluent interfacing
 * @see AxonTestPhase.Then.MessageAssertions.exception
 * @since 5.1.0
 */
fun <T : AxonTestPhase.Then.MessageAssertions<T>> T.exception(expectedException: KClass<out Throwable>, message: String): T =
    this.exception(expectedException.java, message)
