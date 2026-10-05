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
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.BeanFactoryAnnotationUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.convert.converter.Converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Reproduces, through a real {@link AnnotationConfigApplicationContext} refresh, the
 * {@code BeanCurrentlyInCreationException} that motivated deferring {@code @ConfigurationPropertiesBinding}-qualified
 * beans in {@link SpringComponentRegistry}.
 * <p>
 * Unlike {@link SpringComponentRegistryInitializationTest}, which calls
 * {@link SpringComponentRegistry#postProcessAfterInitialization(Object, String)} directly, this test lets Spring
 * drive bean creation itself, exercising its real "bean currently in creation" tracking.
 * <p>
 * The qualified converter is resolved as a nested dependency of an unrelated {@code rootBean} - mirroring how
 * Flyway's converter is pulled in by Tomcat's web server factory chain in the original crash - rather than as its own
 * top-level bean. That's deliberate: Spring Framework 6.2+ catches and logs (rather than propagates) a
 * {@code BeanCurrentlyInCreationException} a top-level singleton throws for itself during pre-instantiation, which
 * would otherwise mask this exact bug.
 * <p>
 * Without the fix, the qualified converter becomes the first non-infrastructure bean processed, triggering
 * {@link SpringComponentRegistry#initialize()} mid-construction. The {@link ConfigurationEnhancer} below then
 * re-resolves every bean carrying the same qualifier - exactly what Spring Boot's {@code ConversionServiceDeducer}
 * does during {@code @ConfigurationProperties} binding - re-entering the still-in-creation bean.
 *
 * @author Steven van Beelen
 */
class SpringComponentRegistryConfigurationPropertiesBindingIntegrationTest {

    private static final String CONFIGURATION_PROPERTIES_BINDING_QUALIFIER =
            "org.springframework.boot.context.properties.ConfigurationPropertiesBinding";

    @Test
    void contextRefreshesWithoutBeanCurrentlyInCreationException() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            // Matches Spring Boot's default (disabled since Boot 2.6) - otherwise a bean still under construction
            // resolves via an early reference instead of throwing, masking the race this test reproduces.
            context.getDefaultListableBeanFactory().setAllowCircularReferences(false);

            // Infrastructure beans, so they don't themselves trigger initialize().
            context.registerBean(
                    "springLifecycleRegistry",
                    SpringLifecycleRegistry.class,
                    () -> {
                        SpringLifecycleRegistry registry = new SpringLifecycleRegistry();
                        registry.setBeanFactory(context.getDefaultListableBeanFactory());
                        return registry;
                    },
                    beanDefinition -> beanDefinition.setRole(BeanDefinition.ROLE_INFRASTRUCTURE)
            );
            context.registerBean(
                    "springComponentRegistry",
                    SpringComponentRegistry.class,
                    () -> new SpringComponentRegistry(
                            context.getDefaultListableBeanFactory(),
                            context.getBean(SpringLifecycleRegistry.class)
                    ),
                    beanDefinition -> beanDefinition.setRole(BeanDefinition.ROLE_INFRASTRUCTURE)
            );

            // Stands in for Tomcat's web server factory chain: pulls the qualified converter in as a nested
            // dependency, so the converter is never its own top-level pre-instantiation entry.
            context.registerBean("rootBean", Object.class, () -> {
                context.getBean(QualifiedConverterBean.class);
                return new Object();
            });
            // Stands in for a third-party @ConfigurationPropertiesBinding converter, e.g. Flyway's converter.
            context.registerBean("qualifiedConverter", QualifiedConverterBean.class, QualifiedConverterBean::new);
            // Stands in for an enhancer like PersistentStreamConfigurationEnhancer, which eagerly resolves a
            // @ConfigurationProperties bean and thereby walks every @ConfigurationPropertiesBinding-qualified bean.
            context.registerBean(
                    "reentrantEnhancer",
                    ReentrantEnhancer.class,
                    () -> new ReentrantEnhancer(context.getDefaultListableBeanFactory())
            );
            // A trailing bean for initialize() to trigger on, once the qualified converter is safely out of the way.
            context.registerBean("trailingBean", Object.class, Object::new);

            assertThatCode(context::refresh).doesNotThrowAnyException();
            assertThat(context.getBean(ReentrantEnhancer.class).invoked).isTrue();
        }
    }

    @Qualifier(CONFIGURATION_PROPERTIES_BINDING_QUALIFIER)
    private static class QualifiedConverterBean implements Converter<String, Integer> {

        @Override
        public Integer convert(String source) {
            return Integer.valueOf(source);
        }
    }

    private static class ReentrantEnhancer implements ConfigurationEnhancer {

        private final ListableBeanFactory beanFactory;

        private boolean invoked = false;

        private ReentrantEnhancer(ListableBeanFactory beanFactory) {
            this.beanFactory = beanFactory;
        }

        @Override
        public void enhance(ComponentRegistry registry) {
            invoked = true;
            BeanFactoryAnnotationUtils.qualifiedBeansOfType(
                    beanFactory, Converter.class, CONFIGURATION_PROPERTIES_BINDING_QUALIFIER
            );
        }
    }
}
