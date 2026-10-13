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

import org.axonframework.common.TypeReference;
import org.axonframework.common.configuration.ComponentDefinition;
import org.axonframework.common.configuration.ComponentNotFoundException;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.DefaultComponentRegistry;
import org.axonframework.common.configuration.StubLifecycleRegistry;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.junit.jupiter.api.*;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test class validating the {@link InjectComponentParameterResolverFactory}.
 *
 * @author Allard Buijze
 */
class InjectComponentParameterResolverFactoryTest {

    private static final Greeter DEFAULT_GREETER = new Greeter("default");
    private static final Greeter NAMED_GREETER = new Greeter("named");
    private static final List<String> STRINGS = List.of("strings");
    private static final List<Integer> INTEGERS = List.of(42);
    private static final List<String> NAMED_STRINGS = List.of("named-strings");
    private static final List<?> RAW_LIST = List.of("raw");
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<Integer>> INTEGER_LIST = new TypeReference<>() {
    };

    private Method method;
    private Parameter[] parameters;

    @BeforeEach
    void setUp() throws Exception {
        method = getClass().getDeclaredMethod("donorMethod",
                                              Greeter.class, Greeter.class, Greeter.class, Greeter.class,
                                              List.class, List.class, List.class, List.class, List.class);
        parameters = method.getParameters();
    }

    @Nested
    class WhenComponentsAreAvailable {

        private InjectComponentParameterResolverFactory testSubject;

        @BeforeEach
        void setUp() {
            DefaultComponentRegistry componentRegistry = new DefaultComponentRegistry();
            componentRegistry.disableEnhancerScanning()
                             .registerComponent(Greeter.class, c -> DEFAULT_GREETER)
                             .registerComponent(Greeter.class, "namedGreeter", c -> NAMED_GREETER)
                             .registerComponent(List.class, c -> RAW_LIST)
                             .registerComponent(ComponentDefinition.ofType(STRING_LIST).withInstance(STRINGS))
                             .registerComponent(ComponentDefinition.ofType(INTEGER_LIST).withInstance(INTEGERS))
                             .registerComponent(ComponentDefinition.ofTypeAndName(STRING_LIST, "namedStrings")
                                                                   .withInstance(NAMED_STRINGS));
            Configuration configuration = componentRegistry.build(new StubLifecycleRegistry());
            testSubject = new InjectComponentParameterResolverFactory(configuration);
        }

        @Test
        void returnsNullForParameterWithoutAnnotation() {
            // when
            ParameterResolver<?> result = testSubject.createInstance(method, parameters, 0);

            // then
            assertThat(result).isNull();
        }

        @Test
        void resolvesComponentByTypeWhenNoNameIsGiven() {
            // when
            ParameterResolver<?> result = testSubject.createInstance(method, parameters, 1);

            // then
            assertThat(resolvedValue(result)).isSameAs(DEFAULT_GREETER);
        }

        @Test
        void resolvesComponentByName() {
            // when
            ParameterResolver<?> result = testSubject.createInstance(method, parameters, 2);

            // then
            assertThat(resolvedValue(result)).isSameAs(NAMED_GREETER);
        }

        @Test
        void resolvesComponentByNameFromMetaAnnotation() {
            // when
            ParameterResolver<?> result = testSubject.createInstance(method, parameters, 3);

            // then
            assertThat(resolvedValue(result)).isSameAs(NAMED_GREETER);
        }

        @Test
        void resolvesComponentByGenericTypeOfParameter() {
            // given - components of type List<String> and List<Integer> are registered
            // when
            ParameterResolver<?> stringsResult = testSubject.createInstance(method, parameters, 4);
            ParameterResolver<?> integersResult = testSubject.createInstance(method, parameters, 5);

            // then
            assertThat(resolvedValue(stringsResult)).isSameAs(STRINGS);
            assertThat(resolvedValue(integersResult)).isSameAs(INTEGERS);
        }

        @Test
        void resolvesComponentByGenericTypeAndName() {
            // when
            ParameterResolver<?> result = testSubject.createInstance(method, parameters, 6);

            // then
            assertThat(resolvedValue(result)).isSameAs(NAMED_STRINGS);
        }

        @Test
        void resolvesRawComponentForParameterOfRawType() {
            // when
            ParameterResolver<?> result = testSubject.createInstance(method, parameters, 7);

            // then
            assertThat(resolvedValue(result)).isSameAs(RAW_LIST);
        }

        @Test
        void doesNotResolveRawComponentForParameterOfGenericType() {
            // given - only a raw List and List<String> and List<Integer> are registered, no List<Long>
            // when / then
            assertThatThrownBy(() -> testSubject.createInstance(method, parameters, 8))
                    .isInstanceOf(ComponentNotFoundException.class)
                    .hasMessageContaining(List.class.getName());
        }

        private static Object resolvedValue(ParameterResolver<?> resolver) {
            assertThat(resolver).isNotNull();
            return resolver.resolveParameterValue(new StubProcessingContext())
                           .orTimeout(5, TimeUnit.SECONDS)
                           .join();
        }
    }

    @Nested
    class WhenComponentsAreMissing {

        private InjectComponentParameterResolverFactory testSubject;

        @BeforeEach
        void setUp() {
            DefaultComponentRegistry componentRegistry = new DefaultComponentRegistry();
            componentRegistry.disableEnhancerScanning();
            Configuration configuration = componentRegistry.build(new StubLifecycleRegistry());
            testSubject = new InjectComponentParameterResolverFactory(configuration);
        }

        @Test
        void returnsNullForParameterWithoutAnnotation() {
            // when
            ParameterResolver<?> result = testSubject.createInstance(method, parameters, 0);

            // then
            assertThat(result).isNull();
        }

        @Test
        void throwsComponentNotFoundExceptionNamingTheTypeWhenNoNameIsGiven() {
            // when / then
            assertThatThrownBy(() -> testSubject.createInstance(method, parameters, 1))
                    .isInstanceOf(ComponentNotFoundException.class)
                    .hasMessageContaining(Greeter.class.getName())
                    .hasMessageNotContaining("name [");
        }

        @Test
        void throwsComponentNotFoundExceptionNamingTheTypeAndName() {
            // when / then
            assertThatThrownBy(() -> testSubject.createInstance(method, parameters, 2))
                    .isInstanceOf(ComponentNotFoundException.class)
                    .hasMessageContaining(Greeter.class.getName())
                    .hasMessageContaining("namedGreeter");
        }

        @Test
        void throwsComponentNotFoundExceptionNamingTheRawTypeOfGenericParameter() {
            // when / then
            assertThatThrownBy(() -> testSubject.createInstance(method, parameters, 4))
                    .isInstanceOf(ComponentNotFoundException.class)
                    .hasMessageContaining(List.class.getName());
        }
    }

    @SuppressWarnings("unused")
    private void donorMethod(Greeter unannotated,
                             @InjectComponent Greeter byType,
                             @InjectComponent(name = "namedGreeter") Greeter byName,
                             @NamedGreeter Greeter byMetaAnnotation,
                             @InjectComponent List<String> strings,
                             @InjectComponent List<Integer> integers,
                             @InjectComponent(name = "namedStrings") List<String> namedStrings,
                             @SuppressWarnings("rawtypes") @InjectComponent List rawList,
                             @InjectComponent List<Long> longs) {
    }

    @Target(ElementType.PARAMETER)
    @Retention(RetentionPolicy.RUNTIME)
    @InjectComponent(name = "namedGreeter")
    private @interface NamedGreeter {

    }

    private record Greeter(String greeting) {

    }
}
