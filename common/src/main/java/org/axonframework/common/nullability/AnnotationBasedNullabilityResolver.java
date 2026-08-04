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

import org.axonframework.common.annotation.AnnotationUtils;
import org.axonframework.common.annotation.Internal;

import java.lang.reflect.Parameter;

/**
 * {@link NullabilityResolver} reporting a parameter as {@link Nullability#NULLABLE} when it carries an annotation
 * whose simple name is {@code Nullable}, regardless of which library declares it.
 * <p>
 * This covers the runtime-visible variants, whichever position they occupy: jspecify's
 * {@code org.jspecify.annotations.Nullable} in the type-use position, and {@code jakarta.annotation.Nullable} or
 * JSR-305's {@code javax.annotation.Nullable} in the declaration position. It cannot cover
 * {@code org.jetbrains.annotations.Nullable}, which uses {@link java.lang.annotation.RetentionPolicy#CLASS} retention
 * and is therefore absent from the class file at runtime.
 * <p>
 * Registered through this module's {@code META-INF/services} file and left unannotated, so it sits at
 * {@link org.axonframework.common.Priority#NEUTRAL} and any resolver that can speak authoritatively for a subset of
 * parameters, such as one backed by a language's own type information, outranks it.
 * <p>
 * Never reports {@link Nullability#NON_NULL}: an unannotated Java parameter says nothing about whether it accepts
 * {@code null}, so anything other than an explicit {@code Nullable} annotation yields {@link Nullability#UNKNOWN}.
 * <p>
 * Marked {@link Internal} as it is registered automatically and not meant to be referenced directly.
 *
 * @author Mateusz Nowak
 * @see NullabilityResolver
 * @since 5.3.0
 */
@Internal
public class AnnotationBasedNullabilityResolver implements NullabilityResolver {

    private static final String NULLABLE_ANNOTATION_NAME = "nullable";

    /**
     * Constructs the resolver. Invoked by the {@link java.util.ServiceLoader}.
     */
    public AnnotationBasedNullabilityResolver() {
        // Required by the ServiceLoader.
    }

    @Override
    public Nullability resolve(Parameter parameter) {
        return AnnotationUtils.hasAnnotationNamed(parameter, NULLABLE_ANNOTATION_NAME)
                ? Nullability.NULLABLE
                : Nullability.UNKNOWN;
    }
}
