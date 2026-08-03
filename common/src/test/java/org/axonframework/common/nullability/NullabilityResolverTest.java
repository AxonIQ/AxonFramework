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
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating the {@link NullabilityResolver} contract, its {@link AnnotationBasedNullabilityResolver}
 * default implementation, and the {@link java.util.ServiceLoader}-backed chain resolving through both.
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
    class AnnotationBasedDetection {

        private final AnnotationBasedNullabilityResolver testSubject = new AnnotationBasedNullabilityResolver();

        @Test
        void detectsTypeUseNullable() throws NoSuchMethodException {
            // given a jspecify-style @Nullable, which occupies the type-use position
            Parameter parameter = parameterOf(TypeUseHolder.class, "nullableParameter");

            // when / then
            assertThat(testSubject.resolve(parameter)).isEqualTo(Nullability.NULLABLE);
        }

        @Test
        void detectsDeclarationOnlyNullable() throws NoSuchMethodException {
            // given a JSR-305-style @Nullable, which occupies the declaration position only
            Parameter parameter = parameterOf(DeclarationHolder.class, "nullableParameter");

            // when / then
            assertThat(testSubject.resolve(parameter)).isEqualTo(Nullability.NULLABLE);
        }

        @Test
        void matchesTheAnnotationNameIgnoringCase() throws NoSuchMethodException {
            // given an annotation named NULLABLE rather than Nullable
            Parameter parameter = parameterOf(OtherHolder.class, "differentlyCasedNullable");

            // when / then
            assertThat(testSubject.resolve(parameter)).isEqualTo(Nullability.NULLABLE);
        }

        @Test
        void reportsUnknownRatherThanNonNullForAnUnannotatedParameter() throws NoSuchMethodException {
            // given: absence of an annotation is not evidence of non-nullness in Java, so the resolver must abstain
            // and leave room for a lower-priority resolver to answer
            Parameter parameter = parameterOf(OtherHolder.class, "unannotated");

            // when / then
            assertThat(testSubject.resolve(parameter)).isEqualTo(Nullability.UNKNOWN);
        }

        @Test
        void ignoresAnUnrelatedAnnotation() throws NoSuchMethodException {
            // given
            Parameter parameter = parameterOf(OtherHolder.class, "unrelatedAnnotation");

            // when / then
            assertThat(testSubject.resolve(parameter)).isEqualTo(Nullability.UNKNOWN);
        }

        @Test
        void defaultsToTheLowestPriority() {
            // given: the general-purpose default must be outrankable by language-specific resolvers
            assertThat(testSubject.priority()).isZero();
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

            // when / then
            assertThat(NullabilityResolver.nullabilityOf(parameter)).isEqualTo(Nullability.UNKNOWN);
        }

        @Test
        void reportsUnknownAsNotNullable() throws NoSuchMethodException {
            // given: isNullable collapses UNKNOWN and NON_NULL, so callers default to the stricter contract
            Parameter parameter = parameterOf(OtherHolder.class, "unannotated");

            // when / then
            assertThat(NullabilityResolver.isNullable(parameter)).isFalse();
        }
    }

    @Nested
    class PriorityOrdering {

        @Test
        void higherPriorityResolverAnswersFirst() throws NoSuchMethodException {
            // given two resolvers disagreeing about the same parameter
            NullabilityResolver low = resolver(Nullability.NULLABLE, 0);
            NullabilityResolver high = resolver(Nullability.NON_NULL, 1000);
            Parameter parameter = parameterOf(OtherHolder.class, "unannotated");

            // when ordering them the way the chain does
            List<NullabilityResolver> ordered = orderedAsChain(List.of(low, high));

            // then
            assertThat(firstAnswer(ordered, parameter)).isEqualTo(Nullability.NON_NULL);
        }

        @Test
        void anAbstainingResolverDefersToTheNextOne() throws NoSuchMethodException {
            // given a high-priority resolver that cannot tell, as the Kotlin resolver does for Java classes
            NullabilityResolver abstaining = resolver(Nullability.UNKNOWN, 1000);
            NullabilityResolver answering = resolver(Nullability.NULLABLE, 0);
            Parameter parameter = parameterOf(OtherHolder.class, "unannotated");

            // when
            List<NullabilityResolver> ordered = orderedAsChain(List.of(abstaining, answering));

            // then
            assertThat(firstAnswer(ordered, parameter)).isEqualTo(Nullability.NULLABLE);
        }

        private static NullabilityResolver resolver(Nullability answer, int priority) {
            return new NullabilityResolver() {
                @Override
                public Nullability resolve(Parameter parameter) {
                    return answer;
                }

                @Override
                public int priority() {
                    return priority;
                }
            };
        }

        private static List<NullabilityResolver> orderedAsChain(List<NullabilityResolver> resolvers) {
            return resolvers.stream()
                            .sorted(Comparator.comparingInt(NullabilityResolver::priority).reversed())
                            .toList();
        }

        private static Nullability firstAnswer(List<NullabilityResolver> resolvers, Parameter parameter) {
            return resolvers.stream()
                            .map(resolver -> resolver.resolve(parameter))
                            .filter(nullability -> nullability != Nullability.UNKNOWN)
                            .findFirst()
                            .orElse(Nullability.UNKNOWN);
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
