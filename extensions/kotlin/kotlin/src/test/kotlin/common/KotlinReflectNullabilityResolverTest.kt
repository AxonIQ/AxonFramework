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
import org.axonframework.common.nullability.NullabilityResolver
import org.jspecify.annotations.Nullable
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.lang.reflect.Parameter

/**
 * Test class validating [KotlinReflectNullabilityResolver], which reports the nullability Kotlin declared for a
 * parameter, and how it combines with the other resolvers on the [NullabilityResolver] chain.
 *
 * @author Mateusz Nowak
 */
class KotlinReflectNullabilityResolverTest {

    private val testSubject = KotlinReflectNullabilityResolver()

    private fun parameterOf(methodName: String, index: Int = 0): Parameter =
        Handlers::class.java.declaredMethods.single { it.name == methodName }.parameters[index]

    @Nested
    inner class KotlinDeclarations {

        @Test
        fun `nullable type resolves to NULLABLE`() {
            // given a Kotlin parameter declared as 'State?'
            val parameter = parameterOf("nullableState")

            // when
            val result = testSubject.resolve(parameter)

            // then
            assertThat(result).isEqualTo(Nullability.NULLABLE)
        }

        @Test
        fun `non-null type resolves to NON_NULL`() {
            // given a Kotlin parameter declared as 'State'
            val parameter = parameterOf("nonNullState")

            // when
            val result = testSubject.resolve(parameter)

            // then
            assertThat(result).isEqualTo(Nullability.NON_NULL)
        }

        @Test
        fun `nullability is read per parameter, not per method`() {
            // given a method mixing a non-null and a nullable parameter
            val command = parameterOf("mixed", 0)
            val state = parameterOf("mixed", 1)

            // when / then
            assertThat(testSubject.resolve(command)).isEqualTo(Nullability.NON_NULL)
            assertThat(testSubject.resolve(state)).isEqualTo(Nullability.NULLABLE)
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
            assertThat(testSubject.resolve(nullableOverload)).isEqualTo(Nullability.NULLABLE)
            assertThat(testSubject.resolve(nonNullOverload)).isEqualTo(Nullability.NON_NULL)
        }

        @Test
        fun `constructor parameters are supported`() {
            // given
            val constructor = WithConstructor::class.java.declaredConstructors.single()

            // when / then
            assertThat(testSubject.resolve(constructor.parameters[0])).isEqualTo(Nullability.NON_NULL)
            assertThat(testSubject.resolve(constructor.parameters[1])).isEqualTo(Nullability.NULLABLE)
        }

        @Test
        fun `declared parameters of a suspend function resolve past the Continuation`() {
            // given a suspend function declaring one nullable parameter
            val method = Handlers::class.java.declaredMethods.single { it.name == "suspending" }

            // when / then
            assertThat(testSubject.resolve(method.parameters[0])).isEqualTo(Nullability.NULLABLE)
        }

        @Test
        fun `declared parameters of an inner class constructor resolve past the outer instance`() {
            // given an inner class constructor declaring a non-null and a nullable parameter
            val constructor = Inner::class.java.declaredConstructors.single()

            // when / then
            assertThat(testSubject.resolve(constructor.parameters[1])).isEqualTo(Nullability.NON_NULL)
            assertThat(testSubject.resolve(constructor.parameters[2])).isEqualTo(Nullability.NULLABLE)
        }

        @Test
        fun `declared parameters of an enum constructor resolve past name and ordinal`() {
            // given an enum whose constructor declares three parameters. The JVM prepends name and ordinal, so
            // without an offset the first declared parameter would read the third one's nullability.
            val constructor = Suit::class.java.declaredConstructors.single()

            // when / then
            assertThat(testSubject.resolve(constructor.parameters[2])).isEqualTo(Nullability.NON_NULL)
            assertThat(testSubject.resolve(constructor.parameters[3])).isEqualTo(Nullability.NULLABLE)
            assertThat(testSubject.resolve(constructor.parameters[4])).isEqualTo(Nullability.NON_NULL)
        }

        @Test
        fun `primitive parameters resolve to NON_NULL`() {
            // given a Kotlin 'Int', which is a non-null type compiled to a JVM primitive
            val parameter = parameterOf("primitive")

            // when / then
            assertThat(testSubject.resolve(parameter)).isEqualTo(Nullability.NON_NULL)
        }
    }

    @Nested
    inner class Abstains {

        @Test
        fun `java declarations resolve to UNKNOWN`() {
            // given a class not compiled by Kotlin, so carrying no @Metadata
            val parameter = java.util.ArrayList::class.java.getDeclaredMethod("add", Any::class.java).parameters[0]

            // when / then
            assertThat(testSubject.resolve(parameter)).isEqualTo(Nullability.UNKNOWN)
        }

        @Test
        fun `the trailing Continuation of a suspend function resolves to UNKNOWN`() {
            // given a suspend function, whose JVM signature carries a Continuation Kotlin does not model
            val method = Handlers::class.java.declaredMethods.single { it.name == "suspending" }

            // when / then
            assertThat(testSubject.resolve(method.parameters[1])).isEqualTo(Nullability.UNKNOWN)
        }

        @Test
        fun `the synthetic name and ordinal of an enum constructor resolve to UNKNOWN`() {
            // given
            val constructor = Suit::class.java.declaredConstructors.single()

            // when / then
            assertThat(testSubject.resolve(constructor.parameters[0])).isEqualTo(Nullability.UNKNOWN)
            assertThat(testSubject.resolve(constructor.parameters[1])).isEqualTo(Nullability.UNKNOWN)
        }

        @Test
        fun `the outer instance of an inner class constructor resolves to UNKNOWN`() {
            // given an inner class constructor, whose JVM signature carries the outer instance first
            val constructor = Inner::class.java.declaredConstructors.single()

            // when / then
            assertThat(testSubject.resolve(constructor.parameters[0])).isEqualTo(Nullability.UNKNOWN)
        }
    }

    @Nested
    inner class ThroughTheServiceLoaderChain {

        @Test
        fun `resolver is discovered through the chain`() {
            // given a Kotlin nullable parameter carrying no Nullable annotation whatsoever
            val parameter = parameterOf("nullableState")

            // when resolving through the chain rather than the resolver directly
            val result = NullabilityResolver.nullabilityOf(parameter)

            // then
            assertThat(result).isEqualTo(Nullability.NULLABLE)
            assertThat(NullabilityResolver.isNullable(parameter)).isTrue()
        }

        @Test
        fun `non-null Kotlin parameter is not reported as nullable`() {
            // given
            val parameter = parameterOf("nonNullState")

            // when / then
            assertThat(NullabilityResolver.isNullable(parameter)).isFalse()
        }

        @Test
        fun `a contradicting Nullable annotation does not make a non-null Kotlin type nullable`() {
            // given a non-null Kotlin type carrying a jspecify @Nullable, which the annotation-based resolver would
            // report as NULLABLE. Kotlin's own type is authoritative: injecting null would violate the contract the
            // compiler already enforces at the call boundary.
            // Note this asserts the outcome, not the ordering that produces it: with only one resolver able to answer
            // NON_NULL, the assertion holds regardless of chain order. Priority ordering itself is verified in
            // modelling's InjectEntityParameterResolverFactoryTest, where two stubs deliberately disagree.
            val parameter = parameterOf("contradictorilyAnnotated")

            // when
            val result = NullabilityResolver.nullabilityOf(parameter)

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

        fun contradictorilyAnnotated(state: @Nullable State) {}
    }

    @Suppress("unused", "UNUSED_PARAMETER")
    class Overloads {

        fun handle(state: State?) {}

        fun handle(state: OtherState) {}
    }

    @Suppress("unused")
    class WithConstructor(val id: String, val state: State?)

    @Suppress("unused")
    inner class Inner(val id: String, val state: State?)

    @Suppress("unused")
    enum class Suit(val label: String, val state: State?, val rank: Int) {
        SPADES("spades", null, 1)
    }
}
