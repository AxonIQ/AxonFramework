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

package org.axonframework.common.annotation;

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
 * Test class validating the {@link NullabilityDetector} contract, its {@link AnnotationBasedNullabilityDetector}
 * default implementation, and the {@link java.util.ServiceLoader}-backed chain resolving through both.
 * <p>
 * Each nullability flavor lives in its own holder class, since the detector matches on the annotation's simple name
 * and several holders therefore need an annotation named exactly {@code Nullable}.
 *
 * @author Mateusz Nowak
 */
class NullabilityDetectorTest {

    private static Parameter parameterOf(Class<?> holder, String methodName) throws NoSuchMethodException {
        return holder.getDeclaredMethod(methodName, Object.class).getParameters()[0];
    }

    @Nested
    class AnnotationBasedDetection {

        private final AnnotationBasedNullabilityDetector testSubject = new AnnotationBasedNullabilityDetector();

        @Test
        void detectsTypeUseNullable() throws NoSuchMethodException {
            // given a jspecify-style @Nullable, which occupies the type-use position
            Parameter parameter = parameterOf(TypeUseHolder.class, "nullableParameter");

            // when / then
            assertThat(testSubject.detect(parameter)).isEqualTo(Nullability.NULLABLE);
        }

        @Test
        void detectsDeclarationOnlyNullable() throws NoSuchMethodException {
            // given a JSR-305-style @Nullable, which occupies the declaration position only
            Parameter parameter = parameterOf(DeclarationHolder.class, "nullableParameter");

            // when / then
            assertThat(testSubject.detect(parameter)).isEqualTo(Nullability.NULLABLE);
        }

        @Test
        void matchesTheAnnotationNameIgnoringCase() throws NoSuchMethodException {
            // given an annotation named NULLABLE rather than Nullable
            Parameter parameter = parameterOf(OtherHolder.class, "differentlyCasedNullable");

            // when / then
            assertThat(testSubject.detect(parameter)).isEqualTo(Nullability.NULLABLE);
        }

        @Test
        void reportsUnknownRatherThanNonNullForAnUnannotatedParameter() throws NoSuchMethodException {
            // given: absence of an annotation is not evidence of non-nullness in Java, so the detector must abstain
            // and leave room for a lower-priority detector to answer
            Parameter parameter = parameterOf(OtherHolder.class, "unannotated");

            // when / then
            assertThat(testSubject.detect(parameter)).isEqualTo(Nullability.UNKNOWN);
        }

        @Test
        void ignoresAnUnrelatedAnnotation() throws NoSuchMethodException {
            // given
            Parameter parameter = parameterOf(OtherHolder.class, "unrelatedAnnotation");

            // when / then
            assertThat(testSubject.detect(parameter)).isEqualTo(Nullability.UNKNOWN);
        }

        @Test
        void defaultsToTheLowestPriority() {
            // given: the general-purpose default must be outrankable by language-specific detectors
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
            assertThat(NullabilityDetector.nullabilityOf(parameter)).isEqualTo(Nullability.NULLABLE);
            assertThat(NullabilityDetector.isNullable(parameter)).isTrue();
        }

        @Test
        void resolvesUnknownWhenNoDetectorCanTell() throws NoSuchMethodException {
            // given
            Parameter parameter = parameterOf(OtherHolder.class, "unannotated");

            // when / then
            assertThat(NullabilityDetector.nullabilityOf(parameter)).isEqualTo(Nullability.UNKNOWN);
        }

        @Test
        void reportsUnknownAsNotNullable() throws NoSuchMethodException {
            // given: isNullable collapses UNKNOWN and NON_NULL, so callers default to the stricter contract
            Parameter parameter = parameterOf(OtherHolder.class, "unannotated");

            // when / then
            assertThat(NullabilityDetector.isNullable(parameter)).isFalse();
        }
    }

    @Nested
    class PriorityOrdering {

        @Test
        void higherPriorityDetectorAnswersFirst() throws NoSuchMethodException {
            // given two detectors disagreeing about the same parameter
            NullabilityDetector low = detector(Nullability.NULLABLE, 0);
            NullabilityDetector high = detector(Nullability.NON_NULL, 1000);
            Parameter parameter = parameterOf(OtherHolder.class, "unannotated");

            // when ordering them the way the chain does
            List<NullabilityDetector> ordered = orderedAsChain(List.of(low, high));

            // then
            assertThat(firstAnswer(ordered, parameter)).isEqualTo(Nullability.NON_NULL);
        }

        @Test
        void anAbstainingDetectorDefersToTheNextOne() throws NoSuchMethodException {
            // given a high-priority detector that cannot tell, as the Kotlin detector does for Java classes
            NullabilityDetector abstaining = detector(Nullability.UNKNOWN, 1000);
            NullabilityDetector answering = detector(Nullability.NULLABLE, 0);
            Parameter parameter = parameterOf(OtherHolder.class, "unannotated");

            // when
            List<NullabilityDetector> ordered = orderedAsChain(List.of(abstaining, answering));

            // then
            assertThat(firstAnswer(ordered, parameter)).isEqualTo(Nullability.NULLABLE);
        }

        private static NullabilityDetector detector(Nullability answer, int priority) {
            return new NullabilityDetector() {
                @Override
                public Nullability detect(Parameter parameter) {
                    return answer;
                }

                @Override
                public int priority() {
                    return priority;
                }
            };
        }

        private static List<NullabilityDetector> orderedAsChain(List<NullabilityDetector> detectors) {
            return detectors.stream()
                            .sorted(Comparator.comparingInt(NullabilityDetector::priority).reversed())
                            .toList();
        }

        private static Nullability firstAnswer(List<NullabilityDetector> detectors, Parameter parameter) {
            return detectors.stream()
                            .map(detector -> detector.detect(parameter))
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
