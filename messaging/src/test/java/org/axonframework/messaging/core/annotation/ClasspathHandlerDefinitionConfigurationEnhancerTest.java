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

import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.DefaultComponentRegistry;
import org.axonframework.common.configuration.LifecycleRegistry;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.reflection.ClasspathHandlerDefinitionConfigurationEnhancer;
import org.junit.jupiter.api.*;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ClasspathHandlerDefinitionConfigurationEnhancerTest {

    private DefaultComponentRegistry componentRegistry;

    @BeforeEach
    void setUp() {
        componentRegistry = new DefaultComponentRegistry();
        componentRegistry.disableEnhancerScanning();
    }

    @Nested
    class DefaultRegistration {

        @Test
        void registersClasspathHandlerDefinitionAsComponent() {
            // given
            componentRegistry.registerEnhancer(new ClasspathHandlerDefinitionConfigurationEnhancer());

            // when
            Configuration configuration = componentRegistry.build(mock(LifecycleRegistry.class));

            // then
            assertThat(configuration.getComponent(HandlerDefinition.class))
                    .isInstanceOf(MultiHandlerDefinition.class);
        }

        @Test
        void leavesAnExplicitlyRegisteredHandlerDefinitionInPlace() {
            // given an application registering its own definition before the enhancer runs
            HandlerDefinition applicationDefinition = new NoOpHandlerDefinition();
            componentRegistry.registerComponent(HandlerDefinition.class, c -> applicationDefinition)
                             .registerEnhancer(new ClasspathHandlerDefinitionConfigurationEnhancer());

            // when
            Configuration configuration = componentRegistry.build(mock(LifecycleRegistry.class));

            // then
            assertThat(configuration.getComponent(HandlerDefinition.class)).isSameAs(applicationDefinition);
        }
    }

    @Nested
    class Decoration {

        @Test
        void registeredComponentCanBeDecorated() {
            // given the default plus a decorator, as the event sourcing module registers one
            componentRegistry.registerEnhancer(new ClasspathHandlerDefinitionConfigurationEnhancer())
                             .registerDecorator(HandlerDefinition.class,
                                                0,
                                                (config, name, delegate) -> new NoOpHandlerDefinition());

            // when
            Configuration configuration = componentRegistry.build(mock(LifecycleRegistry.class));

            // then the decorator applied, which it cannot do without a registered component
            assertThat(configuration.getComponent(HandlerDefinition.class))
                    .isInstanceOf(NoOpHandlerDefinition.class);
        }
    }

    private static class NoOpHandlerDefinition implements HandlerDefinition {

        @Override
        public <T> Optional<MessageHandlingMember<T>> createHandler(
                Class<T> declaringType,
                Method method,
                ParameterResolverFactory parameterResolverFactory,
                Function<Object, MessageStream<?>> messageStreamResolver
        ) {
            return Optional.empty();
        }
    }
}
