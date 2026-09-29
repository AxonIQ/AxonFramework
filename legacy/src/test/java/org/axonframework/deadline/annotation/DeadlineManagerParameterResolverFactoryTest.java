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

package org.axonframework.deadline.annotation;

import org.axonframework.deadline.CurrentDeadlineManager;
import org.axonframework.deadline.DeadlineManager;
import org.axonframework.messaging.core.ScopeDescriptor;
import org.axonframework.messaging.core.annotation.ParameterResolver;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.modelling.saga.SagaEventHandler;
import org.junit.jupiter.api.*;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test class validating the {@link DeadlineManagerParameterResolverFactory}, in particular that -- unlike
 * {@code ScopeDescriptorParameterResolverFactory} -- it only recognizes a {@link DeadlineManager}-typed parameter on a
 * {@link SagaEventHandler @SagaEventHandler} or {@link DeadlineHandler @DeadlineHandler} method, and that the
 * resulting {@link ParameterResolver} resolves the {@link DeadlineManager} registered on the
 * {@link ProcessingContext}, or fails loudly when none is registered.
 */
class DeadlineManagerParameterResolverFactoryTest {

    private final DeadlineManagerParameterResolverFactory testSubject = new DeadlineManagerParameterResolverFactory();

    @Nested
    class CreateInstance {

        @Test
        void returnsResolverForSagaEventHandlerMethodWithDeadlineManagerParameter() throws NoSuchMethodException {
            var resolver = createInstanceFor(SomeSaga.class, "handle", Object.class, DeadlineManager.class);

            assertThat(resolver).isNotNull();
        }

        @Test
        void returnsResolverForDeadlineHandlerMethodWithDeadlineManagerParameter() throws NoSuchMethodException {
            var resolver = createInstanceFor(SomeSaga.class, "handleDeadline", Object.class, DeadlineManager.class);

            assertThat(resolver).isNotNull();
        }

        @Test
        void returnsNullWhenParameterIsNotOfTypeDeadlineManager() throws NoSuchMethodException {
            var resolver = createInstanceFor(SomeSaga.class, "handleWithoutDeadlineManager", Object.class);

            assertThat(resolver).isNull();
        }

        @Test
        void returnsNullWhenMethodIsNeitherASagaEventHandlerNorADeadlineHandler() throws NoSuchMethodException {
            var resolver =
                    createInstanceFor(SomeSaga.class, "handleWithoutAnnotation", Object.class, DeadlineManager.class);

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

    @Nested
    class Resolving {

        private final ParameterResolver<DeadlineManager> resolver = createDeadlineManagerParameterResolver();

        @Test
        void matchesAlwaysReturnsTrue() {
            assertThat(resolver.matches(new StubProcessingContext())).isTrue();
        }

        @Test
        void resolveParameterValueReturnsDeadlineManagerRegisteredOnContext() {
            DeadlineManager deadlineManager = stubDeadlineManager();
            ProcessingContext context =
                    new StubProcessingContext().withResource(CurrentDeadlineManager.RESOURCE_KEY, deadlineManager);

            DeadlineManager resolved = resolver.resolveParameterValue(context)
                                               .orTimeout(50, TimeUnit.MILLISECONDS)
                                               .join();

            assertThat(resolved).isSameAs(deadlineManager);
        }

        @Test
        void resolveParameterValueThrowsWhenNoDeadlineManagerIsRegisteredOnContext() {
            ProcessingContext context = new StubProcessingContext();

            assertThatThrownBy(() -> resolver.resolveParameterValue(context))
                    .isInstanceOf(IllegalStateException.class);
        }

        @SuppressWarnings("unchecked")
        private ParameterResolver<DeadlineManager> createDeadlineManagerParameterResolver() {
            try {
                Method method = SomeSaga.class.getDeclaredMethod("handle", Object.class, DeadlineManager.class);
                Parameter[] parameters = method.getParameters();
                return (ParameterResolver<DeadlineManager>) testSubject.createInstance(method, parameters, 1);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(e);
            }
        }

        private DeadlineManager stubDeadlineManager() {
            return new DeadlineManager() {
                @Override
                public String schedule(Instant triggerDateTime, String deadlineName, Object messageOrPayload,
                                       ScopeDescriptor deadlineScope) {
                    throw new UnsupportedOperationException("not used in this test");
                }

                @Override
                public void cancelSchedule(String deadlineName, String scheduleId) {
                    throw new UnsupportedOperationException("not used in this test");
                }

                @Override
                public void cancelAll(String deadlineName) {
                    throw new UnsupportedOperationException("not used in this test");
                }

                @Override
                public void cancelAllWithinScope(String deadlineName, ScopeDescriptor scope) {
                    throw new UnsupportedOperationException("not used in this test");
                }
            };
        }
    }

    @SuppressWarnings("unused")
    private static class SomeSaga {

        @SagaEventHandler(associationProperty = "propertyName")
        public void handle(Object event, DeadlineManager deadlineManager) {
        }

        @DeadlineHandler
        public void handleDeadline(Object event, DeadlineManager deadlineManager) {
        }

        @SagaEventHandler(associationProperty = "propertyName")
        public void handleWithoutDeadlineManager(Object event) {
        }

        public void handleWithoutAnnotation(Object event, DeadlineManager deadlineManager) {
        }
    }
}
