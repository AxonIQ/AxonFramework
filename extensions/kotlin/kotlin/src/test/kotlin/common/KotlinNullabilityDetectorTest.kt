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
import org.axonframework.common.nullability.Nullability
import org.axonframework.common.nullability.NullabilityDetector
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.lang.reflect.Parameter

class KotlinNullabilityDetectorTest {

    private val testSubject = KotlinNullabilityDetector()

    private fun parameterOf(methodName: String, index: Int = 0): Parameter =
        Handlers::class.java.declaredMethods.single { it.name == methodName }.parameters[index]

    @Nested
    inner class KotlinDeclarations {

        @Test
        fun `nullable type resolves to NULLABLE`() {
            // given a Kotlin parameter declared as 'State?'
            val parameter = parameterOf("nullableState")

            // when
            val result = testSubject.detect(parameter)

            // then
            assertThat(result).isEqualTo(Nullability.NULLABLE)
        }

        @Test
        fun `non-null type resolves to NON_NULL`() {
            // given a Kotlin parameter declared as 'State'
            val parameter = parameterOf("nonNullState")

            // when
            val result = testSubject.detect(parameter)

            // then
            assertThat(result).isEqualTo(Nullability.NON_NULL)
        }

        @Test
        fun `nullability is read per parameter, not per method`() {
            // given a method mixing a non-null and a nullable parameter
            val command = parameterOf("mixed", 0)
            val state = parameterOf("mixed", 1)

            // when / then
            assertThat(testSubject.detect(command)).isEqualTo(Nullability.NON_NULL)
            assertThat(testSubject.detect(state)).isEqualTo(Nullability.NULLABLE)
        }

        @Test
        fun `overloads are distinguished by their JVM signature`() {
            // given two same-named methods differing only in parameter type, as command handlers commonly do
            val nullableOverload = Overloads::class.java.declaredMethods
                .single { it.name == "handle" && it.parameterTypes[0] == State::class.java }
                .parameters[0]
            val nonNullOverload = Overloads::class.java.declaredMethods
                .single { it.name == "handle" && it.parameterTypes[0] == OtherState::class.java }
                .parameters[0]

            // when / then
            assertThat(testSubject.detect(nullableOverload)).isEqualTo(Nullability.NULLABLE)
            assertThat(testSubject.detect(nonNullOverload)).isEqualTo(Nullability.NON_NULL)
        }

        @Test
        fun `constructor parameters are supported`() {
            // given
            val constructor = WithConstructor::class.java.declaredConstructors.single()

            // when / then
            assertThat(testSubject.detect(constructor.parameters[0])).isEqualTo(Nullability.NON_NULL)
            assertThat(testSubject.detect(constructor.parameters[1])).isEqualTo(Nullability.NULLABLE)
        }

        @Test
        fun `primitive parameters resolve to NON_NULL`() {
            // given a Kotlin 'Int', which is a non-null type compiled to a JVM primitive
            val parameter = parameterOf("primitive")

            // when / then
            assertThat(testSubject.detect(parameter)).isEqualTo(Nullability.NON_NULL)
        }
    }

    @Nested
    inner class Abstains {

        @Test
        fun `java declarations resolve to UNKNOWN`() {
            // given a class not compiled by Kotlin, so carrying no @Metadata
            val parameter = java.util.ArrayList::class.java.getDeclaredMethod("add", Any::class.java).parameters[0]

            // when / then
            assertThat(testSubject.detect(parameter)).isEqualTo(Nullability.UNKNOWN)
        }

        @Test
        fun `suspend functions resolve to UNKNOWN rather than misreading the Continuation`() {
            // given a suspend function, whose JVM signature carries a trailing Continuation that Kotlin does not
            // model as a value parameter
            val method = Handlers::class.java.declaredMethods.single { it.name == "suspending" }

            // when / then
            assertThat(testSubject.detect(method.parameters[0])).isEqualTo(Nullability.UNKNOWN)
        }
    }

    @Nested
    inner class ThroughTheServiceLoaderChain {

        @Test
        fun `detector is discovered and outranks the annotation-based default`() {
            // given a Kotlin nullable parameter carrying no Nullable annotation whatsoever
            val parameter = parameterOf("nullableState")

            // when resolving through the chain rather than the detector directly
            val result = NullabilityDetector.nullabilityOf(parameter)

            // then
            assertThat(result).isEqualTo(Nullability.NULLABLE)
            assertThat(NullabilityDetector.isNullable(parameter)).isTrue()
        }

        @Test
        fun `non-null Kotlin parameter is not reported as nullable`() {
            // given
            val parameter = parameterOf("nonNullState")

            // when / then
            assertThat(NullabilityDetector.isNullable(parameter)).isFalse()
        }

        @Test
        fun `Kotlin type wins over a contradicting Nullable annotation`() {
            // given a non-null Kotlin type carrying a jspecify @Nullable, which the annotation-based default would
            // report as NULLABLE. Kotlin's own type is authoritative: injecting null would violate the contract the
            // compiler already enforces at the call boundary.
            val parameter = parameterOf("contradictorilyAnnotated")

            // when
            val result = NullabilityDetector.nullabilityOf(parameter)

            // then
            assertThat(result).isEqualTo(Nullability.NON_NULL)
        }
    }

    class State
    class OtherState

    @Suppress("unused", "UNUSED_PARAMETER", "RedundantSuspendModifier")
    class Handlers {

        fun nullableState(state: State?) {}

        fun nonNullState(state: State) {}

        fun mixed(command: String, state: State?) {}

        fun primitive(amount: Int) {}

        suspend fun suspending(state: State?) {}

        fun contradictorilyAnnotated(state: @org.jspecify.annotations.Nullable State) {}
    }

    @Suppress("unused", "UNUSED_PARAMETER")
    class Overloads {

        fun handle(state: State?) {}

        fun handle(state: OtherState) {}
    }

    @Suppress("unused")
    class WithConstructor(val id: String, val state: State?)
}
