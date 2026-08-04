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

import org.axonframework.common.Priority
import org.axonframework.common.annotation.Internal
import org.axonframework.common.nullability.Nullability
import org.axonframework.common.nullability.NullabilityResolver
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Parameter
import kotlin.reflect.KFunction
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.kotlinFunction

/**
 * [NullabilityResolver] that reports the nullability Kotlin declared for a parameter, so that a nullable type such as
 * `state: MyEntity?` is honored the same way a Java `@Nullable` parameter is.
 *
 * Kotlin does not express nullability through an annotation that survives to runtime: `T?` compiles to
 * `org.jetbrains.annotations.Nullable`, which has [java.lang.annotation.RetentionPolicy.CLASS] retention and is
 * therefore absent from the class file at runtime. Reflecting over the JVM signature can never see it, so this
 * resolver reads the declaration as Kotlin itself models it.
 *
 * A Kotlin declaration does not always have one value parameter per JVM parameter. An inner class constructor
 * receives the outer instance ahead of the declared ones, an enum constructor receives the name and ordinal, and a
 * `suspend` function carries a trailing `Continuation`. Leading synthetic parameters are skipped via [headOffset],
 * and anything left unmapped, such as that trailing `Continuation`, reports [Nullability.UNKNOWN].
 *
 * Also reports [Nullability.UNKNOWN], leaving the decision to the built-in annotation check, when the declaring class
 * was not compiled by Kotlin or the declaration cannot be mapped to a Kotlin function at all. The former matters more
 * than it looks: `kotlin-reflect` maps Java members too, and would report their platform types as non-null.
 *
 * Marked [Internal] as it is registered automatically and not meant to be referenced directly.
 *
 * @author Mateusz Nowak
 * @see NullabilityResolver
 * @since 5.3.0
 */
@Internal
@Priority(Priority.HIGH)
class KotlinReflectNullabilityResolver : NullabilityResolver {

    override fun resolve(parameter: Parameter): Nullability {
        val executable = parameter.declaringExecutable
        // kotlin-reflect happily maps Java members too, but reports their platform types as non-null. Without this
        // guard every Java parameter would resolve to NON_NULL, short-circuiting the annotation check that is
        // supposed to handle them.
        if (!executable.declaringClass.isKotlinClass()) {
            return Nullability.UNKNOWN
        }
        val function = executable.kotlinFunctionOrNull() ?: return Nullability.UNKNOWN
        val valueParameters = function.valueParameters
        val index = executable.parameters.indexOf(parameter) - headOffset(executable)
        if (index !in valueParameters.indices) {
            return Nullability.UNKNOWN
        }
        return if (valueParameters[index].type.isMarkedNullable) Nullability.NULLABLE else Nullability.NON_NULL
    }

    /**
     * Resolving the Kotlin declaration throws rather than returning `null` for some synthetic members, so both
     * outcomes are folded into `null`.
     */
    private fun Executable.kotlinFunctionOrNull(): KFunction<*>? = runCatching {
        when (this) {
            is Method -> kotlinFunction
            is Constructor<*> -> kotlinFunction
            else -> null
        }
    }.getOrNull()

    /**
     * The number of JVM parameters preceding the first Kotlin value parameter.
     *
     * Only constructors carry any: an enum constructor is handed its name and ordinal, and an inner class constructor
     * its outer instance. Everything else, including the trailing `Continuation` of a `suspend` function, starts at
     * zero and is bounded by the value-parameter count instead.
     */
    private fun headOffset(executable: Executable): Int {
        if (executable !is Constructor<*>) {
            return 0
        }
        val declaringClass = executable.declaringClass
        return when {
            declaringClass.isEnum -> ENUM_CONSTRUCTOR_HEAD_PARAMETERS
            declaringClass.isMemberClass && !Modifier.isStatic(declaringClass.modifiers) -> 1
            else -> 0
        }
    }

    private companion object {

        /**
         * An enum constructor receives `(String name, int ordinal)` before its declared parameters.
         */
        private const val ENUM_CONSTRUCTOR_HEAD_PARAMETERS = 2
    }
}
