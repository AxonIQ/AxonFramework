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

import org.axonframework.common.annotation.Internal;

import java.lang.reflect.Parameter;
import java.util.ServiceLoader;

/**
 * Determines whether a {@link Parameter} is declared as accepting {@code null}.
 * <p>
 * Not every language expresses nullability through annotations that survive to runtime. Java typically uses a
 * {@code Nullable} annotation, which is read out of the box. Kotlin, on the other hand, encodes nullability in its
 * type system and compiles it to an annotation with {@link java.lang.annotation.RetentionPolicy#CLASS} retention,
 * which reflection cannot observe. This interface exists so such languages can contribute their own resolution
 * without the framework depending on their tooling.
 * <p>
 * Implementations are discovered through the {@link ServiceLoader} mechanism, by declaring them in a
 * {@code META-INF/services/org.axonframework.common.nullability.NullabilityResolver} file, and are consulted in
 * descending {@link org.axonframework.common.Priority} order. The first to return something other than
 * {@link Nullability#UNKNOWN} wins; implementations must report {@link Nullability#UNKNOWN} for parameters they know
 * nothing about, so that lower-priority resolvers, and finally the built-in annotation check, still get their turn.
 * <p>
 * Resolution happens while message handlers are being inspected, which is during application startup rather than per
 * message. Implementations may therefore favor accuracy over speed, but must be thread-safe.
 * <p>
 * Marked {@link Internal} as the contract is still provisional. It is public only so that implementations shipping in
 * separate artifacts, such as the Kotlin extension, can reach it.
 *
 * @author Mateusz Nowak
 * @see Nullability
 * @since 5.3.0
 */
@Internal
public interface NullabilityResolver {

    /**
     * Determines the declared {@link Nullability} of the given {@code parameter}.
     *
     * @param parameter the parameter to determine the nullability of
     * @return the declared nullability, or {@link Nullability#UNKNOWN} when this resolver cannot tell
     */
    Nullability resolve(Parameter parameter);

    /**
     * Determines the declared {@link Nullability} of the given {@code parameter}, consulting every
     * {@code NullabilityResolver} found on the classpath before falling back to the built-in annotation check.
     *
     * @param parameter the parameter to determine the nullability of
     * @return the declared nullability, or {@link Nullability#UNKNOWN} when no resolver could tell
     */
    static Nullability nullabilityOf(Parameter parameter) {
        return NullabilityResolverChain.resolve(parameter);
    }

    /**
     * Indicates whether the given {@code parameter} is explicitly declared as accepting {@code null}.
     * <p>
     * Note that a {@code false} result covers both an explicitly non-null parameter and one whose nullability could
     * not be determined. Use {@link #nullabilityOf(Parameter)} to tell those apart.
     *
     * @param parameter the parameter to inspect
     * @return {@code true} if the parameter is explicitly declared as accepting {@code null}
     */
    static boolean isNullable(Parameter parameter) {
        return nullabilityOf(parameter) == Nullability.NULLABLE;
    }
}
