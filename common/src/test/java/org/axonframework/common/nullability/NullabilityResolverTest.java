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

import org.junit.jupiter.api.*;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Parameter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating nullability resolution through {@link NullabilityResolver#nullabilityOf(Parameter)}, which
 * consults the {@link java.util.ServiceLoader}-discovered resolvers and then the built-in annotation check.
 * <p>
 * Each nullability flavor lives in its own holder class, since the resolver matches on the annotation's simple name
 * and several holders therefore need an annotation named exactly {@code Nullable}.
 *
 * @author Mateusz Nowak
 */
class NullabilityResolverTest {

    private static Parameter parameterOf(Class<?> holder, String methodName) throws NoSuchMethodException {
        return holder.getDeclaredMethod(methodName, Object.class).getParameters()[0];
    }

    @Nested
    class BuiltInAnnotationFallback {

        @Test
        void detectsTypeUseNullable() throws NoSuchMethodException {
            // given a jspecify-style @Nullable, which occupies the type-use position
            Parameter parameter = parameterOf(TypeUseHolder.class, "nullableParameter");

            // when / then
            assertThat(NullabilityResolver.nullabilityOf(parameter)).isEqualTo(Nullability.NULLABLE);
        }

        @Test
        void detectsDeclarationOnlyNullable() throws NoSuchMethodException {
            // given a JSR-305-style @Nullable, which occupies the declaration position only
            Parameter parameter = parameterOf(DeclarationHolder.class, "nullableParameter");

            // when / then
            assertThat(NullabilityResolver.nullabilityOf(parameter)).isEqualTo(Nullability.NULLABLE);
        }

        @Test
        void matchesTheAnnotationNameIgnoringCase() throws NoSuchMethodException {
            // given an annotation named NULLABLE rather than Nullable
            Parameter parameter = parameterOf(OtherHolder.class, "differentlyCasedNullable");

            // when / then
            assertThat(NullabilityResolver.nullabilityOf(parameter)).isEqualTo(Nullability.NULLABLE);
        }

        @Test
        void reportsUnknownRatherThanNonNullForAnUnannotatedParameter() throws NoSuchMethodException {
            // given: absence of an annotation is not evidence of non-nullness in Java, so the fallback must abstain
            // rather than claim NON_NULL
            Parameter parameter = parameterOf(OtherHolder.class, "unannotated");

            // when / then
            assertThat(NullabilityResolver.nullabilityOf(parameter)).isEqualTo(Nullability.UNKNOWN);
        }

        @Test
        void ignoresAnUnrelatedAnnotation() throws NoSuchMethodException {
            // given
            Parameter parameter = parameterOf(OtherHolder.class, "unrelatedAnnotation");

            // when / then
            assertThat(NullabilityResolver.nullabilityOf(parameter)).isEqualTo(Nullability.UNKNOWN);
        }
    }

    @Nested
    class ChainResolution {

        @Test
        void resolvesNullableThroughTheServiceLoader() throws NoSuchMethodException {
            // given
            Parameter parameter = parameterOf(TypeUseHolder.class, "nullableParameter");

            // when / then
            assertThat(NullabilityResolver.nullabilityOf(parameter)).isEqualTo(Nullability.NULLABLE);
            assertThat(NullabilityResolver.isNullable(parameter)).isTrue();
        }

        @Test
        void resolvesUnknownWhenNoResolverCanTell() throws NoSuchMethodException {
            // given
            Parameter parameter = parameterOf(OtherHolder.class, "unannotated");

            // when / then: isNullable collapses UNKNOWN and NON_NULL, so callers default to the stricter contract
            assertThat(NullabilityResolver.nullabilityOf(parameter)).isEqualTo(Nullability.UNKNOWN);
            assertThat(NullabilityResolver.isNullable(parameter)).isFalse();
        }
    }

    @SuppressWarnings("unused")
    private static class TypeUseHolder {

        void nullableParameter(@Nullable Object value) {
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE_USE)
        @interface Nullable {

        }
    }

    @SuppressWarnings("unused")
    private static class DeclarationHolder {

        void nullableParameter(@Nullable Object value) {
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.PARAMETER)
        @interface Nullable {

        }
    }

    @SuppressWarnings("unused")
    private static class OtherHolder {

        void differentlyCasedNullable(@NULLABLE Object value) {
        }

        void unrelatedAnnotation(@NotRelated Object value) {
        }

        void unannotated(Object value) {
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.PARAMETER)
        @interface NULLABLE {

        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.PARAMETER)
        @interface NotRelated {

        }
    }
}
