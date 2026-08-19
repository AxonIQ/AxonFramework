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

package org.axonframework.extension.kotlin.common

import org.assertj.core.api.Assertions.assertThat
import org.axonframework.extension.kotlin.ExampleQuery
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream
import kotlin.reflect.KFunction

private fun topLevelFunction() = Unit

private fun String.topLevelExtensionFunction() = this.length

class ReflectKtTest {
    companion object {
        @JvmStatic
        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        fun `verify isKotlinClass`(): Stream<Arguments> = Stream.of(
            arguments(ExampleQuery::class.java, true),
            arguments(ByteArray::class.java, true),
            arguments(IntArray::class.java, true),
            arguments(Integer::class.java, false),
            arguments(String::class.java, false),
            arguments(java.util.UUID::class.java, false),
        )

        @JvmStatic
        fun `verify isTopLevel`(): Stream<Arguments> = Stream.of(
            arguments(::topLevelFunction, true),
            arguments(ExampleQuery::copy, false),
            arguments(String::topLevelExtensionFunction, false),
        )
    }

    @ParameterizedTest
    @MethodSource
    fun `verify isKotlinClass`(klass: Class<*>, expected: Boolean) {
        assertThat(klass.isKotlinClass()).isEqualTo(expected)
    }

    @ParameterizedTest
    @MethodSource
    fun `verify isTopLevel`(function: KFunction<*>, expected: Boolean) {
        assertThat(function.isTopLevel()).isEqualTo(expected)
    }

}
