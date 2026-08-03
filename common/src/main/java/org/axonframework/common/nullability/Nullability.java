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

package org.axonframework.common.nullability;

import kotlin.reflect.KFunction;
import kotlin.reflect.KParameter;
import kotlin.reflect.jvm.ReflectJvmMapping;
import org.axonframework.common.annotation.AnnotationUtils;
import org.axonframework.common.annotation.Internal;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.List;

/**
 * The declared nullability of a {@link Parameter}.
 * <p>
 * This is deliberately three-valued. Absence of a {@code Nullable} annotation is not evidence that a parameter is
 * non-null, since most Java code carries no nullability annotations at all, so anything undeclared reports
 * {@link #UNKNOWN} rather than {@link #NON_NULL}.
 * <p>
 * Kotlin encodes nullability in its type system and compiles it to an annotation with
 * {@link java.lang.annotation.RetentionPolicy#CLASS} retention, which reflection cannot observe. Kotlin declarations
 * are therefore read through {@code kotlin-reflect}, which is an optional dependency: when it is absent, only the
 * annotation check applies.
 *
 * @author Mateusz Nowak
 * @since 5.3.0
 */
@Internal
public enum Nullability {

    /**
     * The parameter is explicitly declared as accepting {@code null}.
     */
    NULLABLE,

    /**
     * The parameter is explicitly declared as not accepting {@code null}.
     */
    NON_NULL,

    /**
     * No nullability could be determined for the parameter.
     */
    UNKNOWN;

    private static final String NULLABLE_NAME = "nullable";

    /**
     * Determines the declared nullability of the given {@code parameter}.
     *
     * @param parameter the parameter to determine the nullability of
     * @return the declared nullability, or {@link #UNKNOWN} when it cannot be determined
     */
    public static Nullability forParameter(Parameter parameter) {
        Class<?> declaringClass = parameter.getDeclaringExecutable().getDeclaringClass();
        if (KotlinSupport.isKotlinType(declaringClass)) {
            Nullability kotlinNullability = KotlinDelegate.forParameter(parameter);
            if (kotlinNullability != UNKNOWN) {
                return kotlinNullability;
            }
        }
        return AnnotationUtils.hasAnnotationNamed(parameter, NULLABLE_NAME) ? NULLABLE : UNKNOWN;
    }

    /**
     * Indicates whether the given {@code parameter} is explicitly declared as accepting {@code null}.
     *
     * @param parameter the parameter to inspect
     * @return {@code true} if the parameter is explicitly declared as accepting {@code null}
     */
    public static boolean isNullable(Parameter parameter) {
        return forParameter(parameter) == NULLABLE;
    }

    /**
     * Detects Kotlin without depending on it, by resolving {@code kotlin.Metadata} and the {@code kotlin-reflect}
     * entry point reflectively. Keeping this out of {@link KotlinDelegate} is what allows that class to reference
     * Kotlin types directly: it is only loaded once these checks pass.
     */
    private static final class KotlinSupport {

        @SuppressWarnings("unchecked")
        private static final Class<? extends Annotation> METADATA = (Class<? extends Annotation>) loadOrNull(
                "kotlin.Metadata"
        );
        private static final boolean REFLECT_PRESENT = loadOrNull("kotlin.reflect.jvm.ReflectJvmMapping") != null;

        private KotlinSupport() {
        }

        private static Class<?> loadOrNull(String className) {
            try {
                return Class.forName(className, false, Nullability.class.getClassLoader());
            } catch (ClassNotFoundException | LinkageError e) {
                return null;
            }
        }

        private static boolean isKotlinType(Class<?> clazz) {
            return METADATA != null && REFLECT_PRESENT && clazz.getDeclaredAnnotation(METADATA) != null;
        }
    }

    /**
     * Isolates every reference to {@code kotlin-reflect}, so the JVM only links those classes once
     * {@link KotlinSupport#isKotlinType(Class)} has confirmed they are present.
     */
    private static final class KotlinDelegate {

        private KotlinDelegate() {
        }

        private static Nullability forParameter(Parameter parameter) {
            Executable executable = parameter.getDeclaringExecutable();
            KFunction<?> function = kotlinFunctionOrNull(executable);
            if (function == null) {
                return UNKNOWN;
            }
            List<KParameter> valueParameters = function.getParameters()
                                                       .stream()
                                                       .filter(p -> p.getKind() == KParameter.Kind.VALUE)
                                                       .toList();
            int index = indexOf(executable, parameter) - headOffset(executable);
            if (index < 0 || index >= valueParameters.size()) {
                return UNKNOWN;
            }
            return valueParameters.get(index).getType().isMarkedNullable() ? NULLABLE : NON_NULL;
        }

        private static KFunction<?> kotlinFunctionOrNull(Executable executable) {
            try {
                if (executable instanceof Method method) {
                    return ReflectJvmMapping.getKotlinFunction(method);
                }
                if (executable instanceof Constructor<?> constructor) {
                    return ReflectJvmMapping.getKotlinFunction(constructor);
                }
                return null;
            } catch (RuntimeException | LinkageError e) {
                return null;
            }
        }

        private static int indexOf(Executable executable, Parameter parameter) {
            Parameter[] parameters = executable.getParameters();
            for (int i = 0; i < parameters.length; i++) {
                if (parameters[i].equals(parameter)) {
                    return i;
                }
            }
            return -1;
        }

        private static int headOffset(Executable executable) {
            Class<?> declaringClass = executable.getDeclaringClass();
            boolean isInner = declaringClass.isMemberClass() && !Modifier.isStatic(declaringClass.getModifiers());
            return executable instanceof Constructor<?> && isInner ? 1 : 0;
        }
    }
}
