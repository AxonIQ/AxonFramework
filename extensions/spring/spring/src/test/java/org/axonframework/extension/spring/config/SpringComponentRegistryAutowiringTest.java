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

import org.axonframework.common.configuration.ComponentRegistry;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test class validating how components registered with the {@link SpringComponentRegistry} take part in Spring's
 * autowiring, once the registry has registered them with the application context.
 * <p>
 * A component registered by type only is the component Axon Framework itself resolves for that type. Autowiring by
 * type resolves to that same instance, also when named components of the same type are present, since the registry
 * registers named components as fallback beans. A named component is autowired through a
 * {@link Qualifier @Qualifier} carrying the component's name, or by type when it is the only candidate. A fallback
 * bean never competes with a {@link Primary @Primary} bean, so a primary bean of the same type keeps winning.
 *
 * @author Allard Buijze
 */
class SpringComponentRegistryAutowiringTest {

    @Nested
    class TypeLevelComponent {

        @Test
        void isAutowiredByTypeWhenNamedComponentsOfTheSameTypeArePresent() {
            // given
            TestComponent typeLevelComponent = new TestComponent();
            try (AnnotationConfigApplicationContext context = contextWithComponents(
                    registry -> registry
                            .registerComponent(TestComponent.class, c -> typeLevelComponent)
                            .registerComponent(TestComponent.class, "special", c -> new TestComponent())
                            .registerComponent(TestComponent.class, "other", c -> new TestComponent())
            )) {
                context.register(ByTypeConsumer.class);

                // when
                context.refresh();

                // then
                assertThat(context.getBean(ByTypeConsumer.class).component).isSameAs(typeLevelComponent);
            }
        }

        @Test
        void isTheUniqueCandidateOfAnObjectProviderWhenNamedComponentsOfTheSameTypeArePresent() {
            // given
            TestComponent typeLevelComponent = new TestComponent();
            try (AnnotationConfigApplicationContext context = contextWithComponents(
                    registry -> registry
                            .registerComponent(TestComponent.class, c -> typeLevelComponent)
                            .registerComponent(TestComponent.class, "special", c -> new TestComponent())
            )) {
                context.register(ByProviderConsumer.class);

                // when
                context.refresh();

                // then
                ObjectProvider<TestComponent> provider = context.getBean(ByProviderConsumer.class).provider;
                assertThat(provider.getIfUnique()).isSameAs(typeLevelComponent);
            }
        }

        @Test
        void yieldsToAPrimarySpringBeanOfTheSameType() {
            // given - Axon's own auto-configuration declares such a primary bean for the ParameterResolverFactory
            try (AnnotationConfigApplicationContext context = contextWithComponents(
                    registry -> registry
                            .registerComponent(TestComponent.class, c -> new TestComponent())
                            .registerComponent(TestComponent.class, "special", c -> new TestComponent())
            )) {
                context.register(PrimaryBeanConfiguration.class, ByTypeConsumer.class);

                // when
                context.refresh();

                // then
                assertThat(context.getBean(ByTypeConsumer.class).component)
                        .isSameAs(context.getBean("primaryComponent"));
            }
        }
    }

    @Nested
    class NamedComponent {

        @Test
        void isAutowiredByTypeWhenItIsTheOnlyComponentOfItsType() {
            // given
            TestComponent onlyComponent = new TestComponent();
            try (AnnotationConfigApplicationContext context = contextWithComponents(
                    registry -> registry.registerComponent(TestComponent.class, "special", c -> onlyComponent)
            )) {
                context.register(ByTypeConsumer.class);

                // when
                context.refresh();

                // then
                assertThat(context.getBean(ByTypeConsumer.class).component).isSameAs(onlyComponent);
            }
        }

        @Test
        void isAutowiredThroughAQualifierCarryingItsName() {
            // given
            TestComponent specialComponent = new TestComponent();
            try (AnnotationConfigApplicationContext context = contextWithComponents(
                    registry -> registry
                            .registerComponent(TestComponent.class, c -> new TestComponent())
                            .registerComponent(TestComponent.class, "special", c -> specialComponent)
                            .registerComponent(TestComponent.class, "other", c -> new TestComponent())
            )) {
                context.register(ByQualifierConsumer.class);

                // when
                context.refresh();

                // then
                assertThat(context.getBean(ByQualifierConsumer.class).component).isSameAs(specialComponent);
            }
        }

        @Test
        void leavesAutowiringByTypeAmbiguousWhenNoTypeLevelComponentIsPresent() {
            // given - only named components, none of which is the default for the type
            try (AnnotationConfigApplicationContext context = contextWithComponents(
                    registry -> registry
                            .registerComponent(TestComponent.class, "special", c -> new TestComponent())
                            .registerComponent(TestComponent.class, "other", c -> new TestComponent())
            )) {
                context.register(ByTypeConsumer.class);

                // when / then
                assertThatThrownBy(context::refresh)
                        .hasRootCauseInstanceOf(NoUniqueBeanDefinitionException.class);
            }
        }
    }

    /**
     * Prepares an application context carrying a {@link SpringComponentRegistry} with the given {@code components}.
     * The context is not refreshed, so a test can register the beans whose autowiring it validates first.
     */
    private static AnnotationConfigApplicationContext contextWithComponents(Consumer<ComponentRegistry> components) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        DefaultListableBeanFactory beanFactory = context.getDefaultListableBeanFactory();

        SpringLifecycleRegistry lifecycleRegistry = new SpringLifecycleRegistry();
        lifecycleRegistry.setBeanFactory(beanFactory);
        SpringComponentRegistry componentRegistry = new SpringComponentRegistry(beanFactory, lifecycleRegistry);
        // Enhancers on the classpath would register the messaging defaults, which these tests do not need.
        componentRegistry.disableEnhancerScanning();
        components.accept(componentRegistry);

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
        return context;
    }

    static class TestComponent {

    }

    static class ByTypeConsumer {

        private final TestComponent component;

        ByTypeConsumer(TestComponent component) {
            this.component = component;
        }
    }

    static class ByQualifierConsumer {

        private final TestComponent component;

        ByQualifierConsumer(@Qualifier("special") TestComponent component) {
            this.component = component;
        }
    }

    static class ByProviderConsumer {

        private final ObjectProvider<TestComponent> provider;

        ByProviderConsumer(ObjectProvider<TestComponent> provider) {
            this.provider = provider;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PrimaryBeanConfiguration {

        @Bean
        @Primary
        TestComponent primaryComponent() {
            return new TestComponent();
        }
    }
}
