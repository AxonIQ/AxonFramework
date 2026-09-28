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

package org.axonframework.messaging.core.annotation;

import org.axonframework.messaging.core.CurrentScope;
import org.axonframework.messaging.core.NoScopeDescriptor;
import org.axonframework.messaging.core.ScopeDescriptor;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.junit.jupiter.api.*;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating the {@link ScopeDescriptorParameterResolverFactory}, in particular that it recognizes any
 * handler method declaring a {@link ScopeDescriptor}-typed parameter (no annotation restriction, unlike
 * {@code SagaLifecycleParameterResolverFactory}), and that the resulting {@link ParameterResolver} resolves
 * {@link CurrentScope#describeCurrentScope(ProcessingContext)}.
 */
class ScopeDescriptorParameterResolverFactoryTest {

    private final ScopeDescriptorParameterResolverFactory testSubject = new ScopeDescriptorParameterResolverFactory();

    @Nested
    class CreateInstance {

        @Test
        void returnsResolverForAnyMethodWithScopeDescriptorParameter() throws NoSuchMethodException {
            var resolver = createInstanceFor(SomeHandler.class, "handleWithoutAnnotation", Object.class,
                                             ScopeDescriptor.class);

            assertThat(resolver).isNotNull();
        }

        @Test
        void returnsResolverForAnnotatedMethodWithScopeDescriptorParameterToo() throws NoSuchMethodException {
            var resolver = createInstanceFor(SomeHandler.class, "handleWithSomeAnnotation", Object.class,
                                             ScopeDescriptor.class);

            assertThat(resolver).isNotNull();
        }

        @Test
        void returnsNullWhenParameterIsNotOfTypeScopeDescriptor() throws NoSuchMethodException {
            var resolver = createInstanceFor(SomeHandler.class, "handleWithoutScopeDescriptor", Object.class);

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

        private final ParameterResolver<ScopeDescriptor> resolver = createScopeDescriptorParameterResolver();

        @Test
        void matchesAlwaysReturnsTrue() {
            assertThat(resolver.matches(new StubProcessingContext())).isTrue();
        }

        @Test
        void resolveParameterValueReturnsScopeDescriptorRegisteredOnContext() {
            ScopeDescriptor descriptor = new StubScopeDescriptor();
            ProcessingContext context = new StubProcessingContext().withResource(CurrentScope.RESOURCE_KEY, descriptor);

            ScopeDescriptor resolved = resolver.resolveParameterValue(context)
                                               .orTimeout(50, TimeUnit.MILLISECONDS)
                                               .join();

            assertThat(resolved).isSameAs(descriptor);
        }

        @Test
        void resolveParameterValueFallsBackToNoScopeDescriptorWhenNothingIsRegistered() {
            ProcessingContext context = new StubProcessingContext();

            ScopeDescriptor resolved = resolver.resolveParameterValue(context)
                                               .orTimeout(50, TimeUnit.MILLISECONDS)
                                               .join();

            assertThat(resolved).isSameAs(NoScopeDescriptor.INSTANCE);
        }

        private ParameterResolver<ScopeDescriptor> createScopeDescriptorParameterResolver() {
            try {
                Method method = SomeHandler.class.getDeclaredMethod("handleWithoutAnnotation", Object.class,
                                                                    ScopeDescriptor.class);
                Parameter[] parameters = method.getParameters();
                return testSubject.createInstance(method, parameters, 1);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(e);
            }
        }

        private record StubScopeDescriptor() implements ScopeDescriptor {

            @Override
            public String scopeDescription() {
                return "StubScopeDescriptor";
            }
        }
    }

    @SuppressWarnings("unused")
    private static class SomeHandler {

        public void handleWithoutAnnotation(Object event, ScopeDescriptor scope) {
        }

        @Deprecated
        public void handleWithSomeAnnotation(Object event, ScopeDescriptor scope) {
        }

        public void handleWithoutScopeDescriptor(Object event) {
        }
    }
}
