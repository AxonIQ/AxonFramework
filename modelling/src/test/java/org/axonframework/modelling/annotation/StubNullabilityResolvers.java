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

package org.axonframework.modelling.annotation;

import org.axonframework.common.Priority;
import org.axonframework.common.nullability.Nullability;
import org.axonframework.common.nullability.NullabilityResolver;

import java.lang.reflect.Parameter;

/**
 * {@link NullabilityResolver} implementations registered through this module's test
 * {@code META-INF/services} file, so that {@link InjectEntityParameterResolverFactoryTest} exercises the real
 * {@link java.util.ServiceLoader} seam rather than only the built-in annotation check.
 * <p>
 * They stand in for the Kotlin extension: they report a parameter as nullable that carries no annotation at all,
 * exactly as a Kotlin {@code MyEntity?} declaration does. Each answers only for handler methods whose name it owns,
 * reporting {@link Nullability#UNKNOWN} for everything else, so that no other test in this module is affected.
 *
 * @author Mateusz Nowak
 */
class StubNullabilityResolvers {

    /**
     * Handler method name that only {@link NullableStub} answers for.
     */
    static final String NULLABLE_MARKER = "resolvedNullableByStubResolver";

    /**
     * Handler method name both {@link NullableStub} and {@link OutrankedStub} answer for, with conflicting answers,
     * so that their relative {@link Priority} decides the outcome.
     */
    static final String CONTESTED_MARKER = "contestedByStubResolvers";

    private StubNullabilityResolvers() {
        // not meant to be instantiated
    }

    /**
     * Outranks {@link OutrankedStub}, and therefore decides the outcome for {@link #CONTESTED_MARKER}.
     */
    @Priority(Priority.HIGH)
    public static class NullableStub implements NullabilityResolver {

        @Override
        public Nullability resolve(Parameter parameter) {
            String method = parameter.getDeclaringExecutable().getName();
            return NULLABLE_MARKER.equals(method) || CONTESTED_MARKER.equals(method)
                    ? Nullability.NULLABLE
                    : Nullability.UNKNOWN;
        }
    }

    /**
     * Outranked by {@link NullableStub}; reaching this one for {@link #CONTESTED_MARKER} means the chain failed to
     * order by {@link Priority}, or failed to stop at the first resolver with an opinion.
     */
    @Priority(Priority.LOW)
    public static class OutrankedStub implements NullabilityResolver {

        @Override
        public Nullability resolve(Parameter parameter) {
            return CONTESTED_MARKER.equals(parameter.getDeclaringExecutable().getName())
                    ? Nullability.NON_NULL
                    : Nullability.UNKNOWN;
        }
    }

    /**
     * Fails to instantiate, standing in for a resolver whose optional dependency is absent. The chain is expected to
     * log and skip it rather than let the whole lookup fail.
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
}
