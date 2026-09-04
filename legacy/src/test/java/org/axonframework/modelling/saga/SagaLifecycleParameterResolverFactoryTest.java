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

package org.axonframework.modelling.saga;

import org.axonframework.messaging.core.annotation.ParameterResolver;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.junit.jupiter.api.*;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test class validating the {@link SagaLifecycleParameterResolverFactory}, in particular whether it correctly
 * recognizes {@link SagaEventHandler @SagaEventHandler} methods declaring a {@link SagaLifecycle}-typed parameter, and
 * whether the resulting {@link ParameterResolver} resolves the {@link SagaLifecycle} registered on the
 * {@link ProcessingContext}.
 */
class SagaLifecycleParameterResolverFactoryTest {

    private final SagaLifecycleParameterResolverFactory testSubject = new SagaLifecycleParameterResolverFactory();

    @Nested
    class CreateInstance {

        @Test
        void returnsResolverForSagaEventHandlerMethodWithSagaLifecycleParameter() throws NoSuchMethodException {
            var resolver = createInstanceFor(SomeSaga.class, "handle", Object.class, SagaLifecycle.class);

            assertThat(resolver).isNotNull();
        }

        @Test
        void returnsResolverWhenSagaEventHandlerIsMetaAnnotated() throws NoSuchMethodException {
            var resolver = createInstanceFor(SomeSaga.class, "handleWithEndSaga", Object.class, SagaLifecycle.class);

            assertThat(resolver).isNotNull();
        }

        @Test
        void returnsNullWhenParameterIsNotOfTypeSagaLifecycle() throws NoSuchMethodException {
            var resolver = createInstanceFor(SomeSaga.class, "handleWithoutLifecycle", Object.class);

            assertThat(resolver).isNull();
        }

        @Test
        void returnsNullWhenMethodIsNotAnnotatedWithSagaEventHandler() throws NoSuchMethodException {
            var resolver = createInstanceFor(SomeSaga.class, "handleWithoutAnnotation", Object.class, SagaLifecycle.class);

            assertThat(resolver).isNull();
        }

        private ParameterResolver<?> createInstanceFor(Class<?> declaringClass,
                                                        String methodName,
                                                        Class<?>... parameterTypes) throws NoSuchMethodException {
            Method method = declaringClass.getDeclaredMethod(methodName, parameterTypes);
            Parameter[] parameters = method.getParameters();
            return testSubject.createInstance(method, parameters, parameters.length - 1);
        }
    }

    /**
     * The two halves of a {@link ParameterResolver} answer different questions, and a
     * {@link SagaLifecycle} parameter is where confusing them is expensive.
     * <p>
     * {@link ParameterResolver#matches(ProcessingContext)} asks whether this resolver can supply the parameter at all,
     * and is consulted while <b>selecting</b> handlers, not only before invoking one. The component managing the Sagas
     * selects handlers to learn which {@link AssociationValue AssociationValues} to look Sagas up by and which
     * {@link SagaCreationPolicy} applies, and it does that before any Saga is on the
     * {@link ProcessingContext} - when starting one, before a Saga exists at all.
     * {@link ParameterResolver#resolveParameterValue(ProcessingContext)} is the half that needs the resource, and it
     * is only ever reached through an {@link AnnotatedSaga}, which registers the resource first.
     */
    @Nested
    class Resolving {

        private final ParameterResolver<SagaLifecycle> resolver = createLifecycleParameterResolver();

        @Test
        void matchesReturnsTrueWhenSagaLifecycleIsRegisteredOnContext() {
            SagaLifecycle lifecycle = stubLifecycle();
            ProcessingContext context = new StubProcessingContext().withResource(SagaLifecycle.RESOURCE_KEY, lifecycle);

            assertThat(resolver.matches(context)).isTrue();
        }

        /**
         * Reporting the resource's presence here instead would read like a safety check and behave like a filter: the
         * handler would be dropped from the metamodel, leaving no association value to search on and a
         * {@link SagaCreationPolicy#NONE} creation policy, so a Saga declaring a {@link SagaLifecycle} parameter would
         * never be started and never be found. Nothing would throw and nothing would be logged, which is why the
         * regression net that matters sits at the manager level, in
         * {@code AnnotatedSagaManagerTest.SagaLifecycleInjection}.
         */
        @Test
        void matchesReturnsTrueBeforeAnySagaIsOnTheContext() {
            ProcessingContext context = new StubProcessingContext();

            assertThat(resolver.matches(context)).isTrue();
        }

        @RepeatedTest(100)
        void resolveParameterValueReturnsSagaLifecycleRegisteredOnContext() {
            SagaLifecycle lifecycle = stubLifecycle();
            ProcessingContext context = new StubProcessingContext().withResource(SagaLifecycle.RESOURCE_KEY, lifecycle);

            SagaLifecycle resolved = resolver.resolveParameterValue(context)
                                             .orTimeout(50, TimeUnit.MILLISECONDS)
                                             .join();

            assertThat(resolved).isSameAs(lifecycle);
        }

        @Test
        void resolveParameterValueThrowsWhenNoSagaLifecycleIsRegisteredOnContext() {
            ProcessingContext context = new StubProcessingContext();

            assertThatThrownBy(() -> resolver.resolveParameterValue(context))
                    .isInstanceOf(IllegalStateException.class);
        }

        @SuppressWarnings("unchecked")
        private ParameterResolver<SagaLifecycle> createLifecycleParameterResolver() {
            try {
                Method method = SomeSaga.class.getDeclaredMethod("handle", Object.class, SagaLifecycle.class);
                Parameter[] parameters = method.getParameters();
                return (ParameterResolver<SagaLifecycle>) testSubject.createInstance(method, parameters, 1);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(e);
            }
        }

        private SagaLifecycle stubLifecycle() {
            return new SagaLifecycle() {
                @Override
                public void associateWith(AssociationValue associationValue) {
                    // not used in this test
                }

                @Override
                public void removeAssociationWith(AssociationValue associationValue) {
                    // not used in this test
                }

                @Override
                public void end() {
                    // not used in this test
                }

                @Override
                public Set<AssociationValue> associationValues() {
                    return Set.of();
                }
            };
        }
    }

    @SuppressWarnings("unused")
    private static class SomeSaga {

        @SagaEventHandler(associationProperty = "propertyName")
        public void handle(Object event, SagaLifecycle lifecycle) {
        }

        @EndSaga
        @SagaEventHandler(associationProperty = "propertyName")
        public void handleWithEndSaga(Object event, SagaLifecycle lifecycle) {
        }

        @SagaEventHandler(associationProperty = "propertyName")
        public void handleWithoutLifecycle(Object event) {
        }

        public void handleWithoutAnnotation(Object event, SagaLifecycle lifecycle) {
        }
    }
}
