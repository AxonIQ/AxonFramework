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

import org.axonframework.common.annotation.Internal
import org.axonframework.common.nullability.Nullability
import org.axonframework.common.nullability.NullabilityResolver
import java.lang.invoke.MethodType
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Parameter
import kotlin.metadata.KmDeclarationContainer
import kotlin.metadata.KmValueParameter
import kotlin.metadata.isNullable
import kotlin.metadata.jvm.JvmMethodSignature
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.signature

/**
 * [NullabilityResolver] that reports the nullability Kotlin declared for a parameter, so that a nullable type such as
 * `state: MyEntity?` is honored the same way a Java `@Nullable` parameter is.
 *
 * Kotlin does not express nullability through an annotation that survives to runtime: `T?` compiles to
 * `org.jetbrains.annotations.Nullable`, which has [java.lang.annotation.RetentionPolicy.CLASS] retention and is
 * therefore absent from the class file at runtime. Reflection can never see it. The information does survive in the
 * [Metadata] annotation the compiler attaches to every Kotlin class, which is `RUNTIME`-retained, and that is what
 * this resolver decodes.
 *
 * Only that single annotation is read and decoded; no `kotlin-reflect` model is built. Resolution happens once per
 * handler during startup, never per message.
 *
 * Reports [Nullability.UNKNOWN], leaving the decision to lower-priority resolvers, when:
 *  - the declaring class was not compiled by Kotlin;
 *  - the metadata cannot be read, for instance because it was written by a newer compiler;
 *  - no declaration matching the JVM signature is found, as happens for `@JvmStatic` companion members whose
 *    metadata lives in the companion rather than in the class carrying the JVM method;
 *  - the JVM parameter count differs from the Kotlin value-parameter count, which is the case for `suspend`
 *    functions carrying a trailing `Continuation`, and for constructors of inner classes carrying the outer
 *    instance. Rather than risk reading the nullability of the wrong parameter, no answer is given.
 *
 * Marked [Internal] as it is registered automatically and not meant to be referenced directly.
 *
 * @author Mateusz Nowak
 * @see NullabilityResolver
 * @since 5.3.0
 */
@Internal
class KotlinMetadataNullabilityResolver : NullabilityResolver {

    /**
     * Outranks the annotation-based default, as Kotlin's own type information is authoritative for Kotlin sources.
     */
    override fun priority(): Int = 1000

    override fun resolve(parameter: Parameter): Nullability {
        val executable = parameter.declaringExecutable
        val index = executable.parameters.indexOf(parameter)
        if (index < 0) {
            return Nullability.UNKNOWN
        }
        val valueParameters = valueParametersOf(executable) ?: return Nullability.UNKNOWN
        // A count mismatch means the JVM signature carries parameters Kotlin does not model, so indices cannot be
        // trusted to line up.
        if (valueParameters.size != executable.parameterCount) {
            return Nullability.UNKNOWN
        }
        return if (valueParameters[index].type.isNullable) Nullability.NULLABLE else Nullability.NON_NULL
    }

    private fun valueParametersOf(executable: Executable): List<KmValueParameter>? {
        val metadata = executable.declaringClass.getAnnotation(Metadata::class.java) ?: return null
        val classMetadata = runCatching { KotlinClassMetadata.readLenient(metadata) }.getOrNull() ?: return null
        val signature = executable.jvmMethodSignature() ?: return null
        return when (executable) {
            is Constructor<*> -> (classMetadata as? KotlinClassMetadata.Class)
                ?.kmClass
                ?.constructors
                ?.firstOrNull { it.signature == signature }
                ?.valueParameters

            is Method -> classMetadata.declarationContainer()
                ?.functions
                ?.firstOrNull { it.signature == signature }
                ?.valueParameters

            else -> null
        }
    }

    private fun KotlinClassMetadata.declarationContainer(): KmDeclarationContainer? = when (this) {
        is KotlinClassMetadata.Class -> kmClass
        is KotlinClassMetadata.FileFacade -> kmPackage
        is KotlinClassMetadata.MultiFileClassPart -> kmPackage
        else -> null
    }

    private fun Executable.jvmMethodSignature(): JvmMethodSignature? = runCatching {
        JvmMethodSignature(
            if (this is Constructor<*>) "<init>" else name,
            MethodType.methodType(
                if (this is Method) returnType else Void.TYPE,
                parameterTypes
            ).toMethodDescriptorString()
        )
    }.getOrNull()
}
