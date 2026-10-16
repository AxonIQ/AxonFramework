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

package org.axonframework.extension.spring.config;

import org.axonframework.common.TypeReference;
import org.axonframework.common.configuration.AmbiguousComponentMatchException;
import org.axonframework.common.configuration.ComponentNotFoundException;
import org.axonframework.common.configuration.Configuration;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test class validating lookups by type through the {@link Configuration} of a {@link SpringComponentRegistry} when
 * several Spring beans match the requested type.
 * <p>
 * Such a lookup resolves to the {@link Primary @Primary} bean, or to the bean Axon registered under the name of the
 * type. Without either, the lookup is ambiguous: it fails with an {@link AmbiguousComponentMatchException} rather than
 * reporting the component as absent, so a default is never used in place of the beans.
 *
 * @author Allard Buijze
 */
class SpringComponentRegistryAmbiguousLookupTest {

    private static final TypeReference<Supplier<String>> AMBIGUOUS_SUPPLIER = new TypeReference<>() {
    };
    private static final TypeReference<Supplier<Long>> PRIMARY_SUPPLIER = new TypeReference<>() {
    };
    private static final TypeReference<Supplier<Boolean>> TYPE_NAMED_SUPPLIER = new TypeReference<>() {
    };
    private static final TypeReference<Supplier<Integer>> SINGLE_SUPPLIER = new TypeReference<>() {
    };
    private static final TypeReference<Supplier<Double>> MISSING_SUPPLIER = new TypeReference<>() {
    };

    private AnnotationConfigApplicationContext context;
    private Configuration testSubject;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext();
        DefaultListableBeanFactory beanFactory = context.getDefaultListableBeanFactory();

        SpringLifecycleRegistry lifecycleRegistry = new SpringLifecycleRegistry();
        lifecycleRegistry.setBeanFactory(beanFactory);
        SpringComponentRegistry componentRegistry = new SpringComponentRegistry(beanFactory, lifecycleRegistry);
        // Enhancers on the classpath would register the messaging defaults, which these tests do not need.
        componentRegistry.disableEnhancerScanning();

        // Infrastructure beans, so they don't themselves trigger initialize().
        context.registerBean(
                "springLifecycleRegistry",
                SpringLifecycleRegistry.class,
                () -> lifecycleRegistry,
                beanDefinition -> beanDefinition.setRole(BeanDefinition.ROLE_INFRASTRUCTURE)
        );
        context.registerBean(
                "springComponentRegistry",
                SpringComponentRegistry.class,
                () -> componentRegistry,
                beanDefinition -> beanDefinition.setRole(BeanDefinition.ROLE_INFRASTRUCTURE)
        );
        context.register(ServiceConfiguration.class);
        // The name under which Axon registers a component of this type without a name.
        context.registerBean(TypeNamedService.class.getName(), TypeNamedService.class, TypeNamedService::new);
        context.registerBean("otherTypeNamedService", TypeNamedService.class, TypeNamedService::new);
        context.refresh();

        testSubject = componentRegistry.configuration();
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Nested
    class HasComponent {

        @Test
        void returnsTrueWhenSeveralBeansMatch() {
            // when / then
            assertThat(testSubject.hasComponent(AmbiguousService.class)).isTrue();
            assertThat(testSubject.hasComponent(AMBIGUOUS_SUPPLIER)).isTrue();
        }

        @Test
        void returnsTrueWhenASingleBeanMatches() {
            // when / then
            assertThat(testSubject.hasComponent(SingleService.class)).isTrue();
            assertThat(testSubject.hasComponent(SINGLE_SUPPLIER)).isTrue();
        }

        @Test
        void returnsFalseWhenNoBeanMatches() {
            // when / then
            assertThat(testSubject.hasComponent(MissingService.class)).isFalse();
            assertThat(testSubject.hasComponent(MISSING_SUPPLIER)).isFalse();
        }
    }

    @Nested
    class GetComponent {

        @Test
        void returnsTheSingleMatchingBean() {
            // when / then
            assertThat(testSubject.getComponent(SingleService.class)).isSameAs(context.getBean("singleService"));
            assertThat(testSubject.getComponent(SINGLE_SUPPLIER)).isSameAs(context.getBean("integerSupplier"));
        }

        @Test
        void returnsThePrimaryBeanWhenSeveralBeansMatch() {
            // when / then
            assertThat(testSubject.getComponent(PrimaryService.class))
                    .isSameAs(context.getBean("primaryPrimaryService"));
            assertThat(testSubject.getComponent(PRIMARY_SUPPLIER)).isSameAs(context.getBean("primaryLongSupplier"));
        }

        @Test
        void returnsTheBeanNamedAfterTheTypeWhenSeveralBeansMatch() {
            // when / then
            assertThat(testSubject.getComponent(TypeNamedService.class))
                    .isSameAs(context.getBean(TypeNamedService.class.getName()));
            assertThat(testSubject.getComponent(TYPE_NAMED_SUPPLIER))
                    .isSameAs(context.getBean(Supplier.class.getName()));
        }

        @Test
        void throwsAmbiguousComponentMatchExceptionWhenSeveralBeansMatch() {
            // when / then
            assertThatThrownBy(() -> testSubject.getComponent(AmbiguousService.class))
                    .isInstanceOf(AmbiguousComponentMatchException.class)
                    .hasCauseInstanceOf(NoUniqueBeanDefinitionException.class);
            assertThatThrownBy(() -> testSubject.getComponent(AMBIGUOUS_SUPPLIER))
                    .isInstanceOf(AmbiguousComponentMatchException.class)
                    .hasCauseInstanceOf(NoUniqueBeanDefinitionException.class);
        }

        @Test
        void throwsComponentNotFoundExceptionWhenNoBeanMatches() {
            // when / then
            assertThatThrownBy(() -> testSubject.getComponent(MissingService.class))
                    .isInstanceOf(ComponentNotFoundException.class);
            assertThatThrownBy(() -> testSubject.getComponent(MISSING_SUPPLIER))
                    .isInstanceOf(ComponentNotFoundException.class);
        }
    }

    @Nested
    class GetOptionalComponent {

        @Test
        void returnsThePrimaryBeanWhenSeveralBeansMatch() {
            // when / then
            assertThat(testSubject.getOptionalComponent(PrimaryService.class))
                    .containsSame(context.getBean("primaryPrimaryService", PrimaryService.class));
            assertThat(testSubject.getOptionalComponent(PRIMARY_SUPPLIER).map(Supplier::get)).contains(1L);
        }

        @Test
        void throwsAmbiguousComponentMatchExceptionWhenSeveralBeansMatch() {
            // when / then
            assertThatThrownBy(() -> testSubject.getOptionalComponent(AmbiguousService.class))
                    .isInstanceOf(AmbiguousComponentMatchException.class);
            assertThatThrownBy(() -> testSubject.getOptionalComponent(AmbiguousService.class, null))
                    .isInstanceOf(AmbiguousComponentMatchException.class);
            assertThatThrownBy(() -> testSubject.getOptionalComponent(AMBIGUOUS_SUPPLIER))
                    .isInstanceOf(AmbiguousComponentMatchException.class);
        }

        @Test
        void returnsEmptyWhenNoBeanMatches() {
            // when / then
            assertThat(testSubject.getOptionalComponent(MissingService.class)).isEmpty();
            assertThat(testSubject.getOptionalComponent(MISSING_SUPPLIER)).isEmpty();
        }
    }

    @Nested
    class GetComponentWithDefault {

        @Test
        void throwsAmbiguousComponentMatchExceptionInsteadOfUsingTheDefaultWhenSeveralBeansMatch() {
            // when / then
            assertThatThrownBy(() -> testSubject.getComponent(AmbiguousService.class, AmbiguousService::new))
                    .isInstanceOf(AmbiguousComponentMatchException.class);
        }

        @Test
        void usesTheDefaultWhenNoBeanMatches() {
            // given
            MissingService defaultService = new MissingService();

            // when / then
            assertThat(testSubject.getComponent(MissingService.class, () -> defaultService))
                    .isSameAs(defaultService);
        }
    }

    static class ServiceConfiguration {

        @Bean
        AmbiguousService firstAmbiguousService() {
            return new AmbiguousService();
        }

        @Bean
        AmbiguousService secondAmbiguousService() {
            return new AmbiguousService();
        }

        @Bean
        @Primary
        PrimaryService primaryPrimaryService() {
            return new PrimaryService();
        }

        @Bean
        PrimaryService otherPrimaryService() {
            return new PrimaryService();
        }

        @Bean
        SingleService singleService() {
            return new SingleService();
        }

        @Bean
        Supplier<String> firstStringSupplier() {
            return () -> "first";
        }

        @Bean
        Supplier<String> secondStringSupplier() {
            return () -> "second";
        }

        @Bean
        @Primary
        Supplier<Long> primaryLongSupplier() {
            return () -> 1L;
        }

        @Bean
        Supplier<Long> otherLongSupplier() {
            return () -> 2L;
        }

        // The name under which Axon registers a component of this type without a name.
        @Bean("java.util.function.Supplier")
        Supplier<Boolean> typeNamedBooleanSupplier() {
            return () -> true;
        }

        @Bean
        Supplier<Boolean> otherBooleanSupplier() {
            return () -> false;
        }

        @Bean
        Supplier<Integer> integerSupplier() {
            return () -> 1;
        }
    }

    static class AmbiguousService {

    }

    static class PrimaryService {

    }

    static class TypeNamedService {

    }

    static class SingleService {

    }

    static class MissingService {

    }
}
