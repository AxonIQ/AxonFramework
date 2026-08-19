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

import org.axonframework.common.Priority;
import org.junit.jupiter.api.*;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Parameter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating {@link NullabilityResolverChain}, reached through
 * {@link NullabilityResolver#nullabilityOf(Parameter)}.
 * <p>
 * The stub resolvers below are registered through this module's test {@code META-INF/services} file, deliberately
 * listed lowest priority first, so that a chain which failed to order by {@link Priority} would consult them in the
 * wrong sequence. Each answers only for the parameters it owns, reporting {@link Nullability#UNKNOWN} for everything
 * else, so no other test in this module is affected.
 *
 * @author Mateusz Nowak
 */
class NullabilityResolverChainTest {

    private static Parameter parameterOf(String methodName) throws NoSuchMethodException {
        return Subjects.class.getDeclaredMethod(methodName, Object.class).getParameters()[0];
    }

    @Nested
    class PriorityOrdering {

        @Test
        void theHighestPriorityResolverWithAnOpinionDecides() throws NoSuchMethodException {
            // given a parameter both stubs answer for, with conflicting answers, the lower priority one first in
            // the services file
            Parameter parameter = parameterOf("contested");

            // when
            Nullability nullability = NullabilityResolver.nullabilityOf(parameter);

            // then the @Priority(HIGH) stub decides, not the one discovered first
            assertThat(nullability).isEqualTo(Nullability.NON_NULL);
        }

        @Test
        void aLowerPriorityResolverStillAnswersWhenTheHigherOneAbstains() throws NoSuchMethodException {
            // given a parameter only the lower priority stub answers for
            Parameter parameter = parameterOf("ownedByLowPriority");

            // when / then abstaining passes the question on rather than ending it
            assertThat(NullabilityResolver.nullabilityOf(parameter)).isEqualTo(Nullability.NULLABLE);
        }
    }

    @Nested
    class Resilience {

        @Test
        void aResolverThatCannotBeInstantiatedIsSkippedRatherThanFailingTheLookup() throws NoSuchMethodException {
            // given: UninstantiableStub is registered and throws from its constructor, standing in for a resolver
            // whose optional dependency is absent
            Parameter parameter = parameterOf("contested");

            // when / then resolution still completes using the resolvers that did load
            assertThat(NullabilityResolver.nullabilityOf(parameter)).isEqualTo(Nullability.NON_NULL);
        }

        @Test
        void reportsUnknownWhenNoResolverHasAnOpinion() throws NoSuchMethodException {
            // given a parameter no stub answers for and that carries no Nullable annotation
            Parameter parameter = parameterOf("unowned");

            // when / then
            assertThat(NullabilityResolver.nullabilityOf(parameter)).isEqualTo(Nullability.UNKNOWN);
        }
    }

    @Nested
    class BuiltInAnnotationResolver {

        @Test
        void participatesAsAnOrdinaryResolverRatherThanASpecialCase() throws NoSuchMethodException {
            // given a parameter no stub answers for, carrying a @Nullable annotation. The answer can only come from
            // AnnotationBasedNullabilityResolver, discovered through common's own services file.
            Parameter parameter = parameterOf("unownedButAnnotated");

            // when / then
            assertThat(NullabilityResolver.nullabilityOf(parameter)).isEqualTo(Nullability.NULLABLE);
        }

        @Test
        void isOutrankedByAResolverThatClaimsTheParameter() throws NoSuchMethodException {
            // given a @Nullable annotated parameter that the @Priority(HIGH) stub reports as non-null
            Parameter parameter = parameterOf("annotatedButClaimedByHighPriority");

            // when / then the stub outranks the built-in annotation resolver, which sits at Priority.NEUTRAL
            assertThat(NullabilityResolver.nullabilityOf(parameter)).isEqualTo(Nullability.NON_NULL);
        }
    }

    @SuppressWarnings("unused")
    private static class Subjects {

        void contested(Object value) {
        }

        void ownedByLowPriority(Object value) {
        }

        void unowned(Object value) {
        }

        void unownedButAnnotated(@Nullable Object value) {
        }

        void annotatedButClaimedByHighPriority(@Nullable Object value) {
        }
    }

    /**
     * Stands in for a resolver backed by a language's own type information, such as the Kotlin extension's.
     */
    @Priority(Priority.HIGH)
    public static class HighPriorityStub implements NullabilityResolver {

        @Override
        public Nullability resolve(Parameter parameter) {
            String method = parameter.getDeclaringExecutable().getName();
            return "contested".equals(method) || "annotatedButClaimedByHighPriority".equals(method)
                    ? Nullability.NON_NULL
                    : Nullability.UNKNOWN;
        }
    }

    /**
     * Outranked by {@link HighPriorityStub}; reaching it for {@code contested} means the chain failed to order by
     * {@link Priority}, or failed to stop at the first resolver with an opinion.
     */
    @Priority(Priority.LOW)
    public static class LowPriorityStub implements NullabilityResolver {

        @Override
        public Nullability resolve(Parameter parameter) {
            String method = parameter.getDeclaringExecutable().getName();
            return "contested".equals(method) || "ownedByLowPriority".equals(method)
                    ? Nullability.NULLABLE
                    : Nullability.UNKNOWN;
        }
    }

    /**
     * Fails to instantiate, standing in for a resolver whose optional dependency is absent.
     */
    public static class UninstantiableStub implements NullabilityResolver {

        public UninstantiableStub() {
            throw new IllegalStateException("Stands in for a resolver whose optional dependency is missing.");
        }

        @Override
        public Nullability resolve(Parameter parameter) {
            throw new UnsupportedOperationException("Never constructed.");
        }
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    private @interface Nullable {

    }
}
