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

import org.axonframework.common.nullability.Nullability;
import org.axonframework.common.nullability.NullabilityResolver;

import java.lang.reflect.Parameter;

/**
 * {@link NullabilityResolver} implementations registered through this module's test
 * {@code META-INF/services} file, so that {@link InjectEntityParameterResolverFactoryTest} exercises the real
 * {@link java.util.ServiceLoader} seam rather than only the built-in annotation check.
 * <p>
 * Ordering, short-circuiting and tolerance of a resolver that fails to load are the chain's own concerns and are
 * covered by {@code NullabilityResolverChainTest} in the common module; what matters here is only that a contributed
 * resolver reaches the factory. Answers only for the handler method it owns, reporting {@link Nullability#UNKNOWN}
 * for everything else, so that no other test in this module is affected.
 *
 * @author Mateusz Nowak
 */
class StubNullabilityResolvers {

    /**
     * Handler method name that only {@link NullableStub} answers for.
     */
    static final String NULLABLE_MARKER = "resolvedNullableByStubResolver";

    private StubNullabilityResolvers() {
        // not meant to be instantiated
    }

    /**
     * Reports the marked parameter as nullable although it carries no annotation, exactly as the Kotlin extension
     * does for a {@code MyEntity?} declaration.
     */
    public static class NullableStub implements NullabilityResolver {

        @Override
        public Nullability resolve(Parameter parameter) {
            return NULLABLE_MARKER.equals(parameter.getDeclaringExecutable().getName())
                    ? Nullability.NULLABLE
                    : Nullability.UNKNOWN;
        }
    }
}
