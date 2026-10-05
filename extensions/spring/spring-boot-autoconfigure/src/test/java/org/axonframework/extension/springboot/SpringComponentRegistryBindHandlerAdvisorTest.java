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

package org.axonframework.extension.springboot;

import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.extension.spring.config.SpringComponentRegistry;
import org.axonframework.extension.spring.config.SpringLifecycleRegistry;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindHandlerAdvisor;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindingPostProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Reproduces, through a real {@link AnnotationConfigApplicationContext} refresh with Spring Boot's
 * {@code @ConfigurationProperties} binding, the {@code BeanCurrentlyInCreationException} that occurs when
 * {@link SpringComponentRegistry} initializes while a {@link ConfigurationPropertiesBindHandlerAdvisor} is still in
 * creation.
 * <p>
 * Spring Boot resolves every {@link ConfigurationPropertiesBindHandlerAdvisor} when it binds
 * {@code @ConfigurationProperties}, so the first binding creates the advisor, and with it the bean declaring it. That
 * declaring bean (Spring Cloud's {@code CommonsConfigAutoConfiguration} in the original failure) is the first bean
 * post-processed, triggering {@link SpringComponentRegistry#initialize()}. An enhancer that then resolves another
 * {@code @ConfigurationProperties} bean starts a second binding, which re-enters the still-in-creation advisor.
 *
 * @author Allard Buijze
 */
class SpringComponentRegistryBindHandlerAdvisorTest {

    @Test
    void contextRefreshesWhileBindHandlerAdvisorIsInCreation() {
        // given
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            // Matches Spring Boot's default - otherwise the in-creation advisor resolves via an early reference.
            context.getDefaultListableBeanFactory().setAllowCircularReferences(false);
            ConfigurationPropertiesBindingPostProcessor.register(context);

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

            // The first bean to be bound, which creates the advisor, and so the bean declaring it.
            context.registerBean("firstProperties", FirstProperties.class, FirstProperties::new);
            // Stands in for a configuration class declaring an advisor, such as CommonsConfigAutoConfiguration.
            context.registerBean("advisorDeclaration", AdvisorDeclaration.class, AdvisorDeclaration::new);
            context.registerBeanDefinition(
                    "bindHandlerAdvisor",
                    BeanDefinitionBuilder.genericBeanDefinition(ConfigurationPropertiesBindHandlerAdvisor.class)
                                         .setFactoryMethodOnBean("bindHandlerAdvisor", "advisorDeclaration")
                                         .getBeanDefinition()
            );
            // Stands in for an enhancer that resolves a @ConfigurationProperties bean, such as Axon Server's.
            context.registerBean("secondProperties", SecondProperties.class, SecondProperties::new);
            // Stands in for an enhancer bean that depends on a @ConfigurationProperties bean, such as the
            // distributed tracing enhancer.
            context.registerBean("thirdProperties", ThirdProperties.class, ThirdProperties::new);
            context.registerBean(
                    "propertiesDependentEnhancer",
                    PropertiesDependentEnhancer.class,
                    () -> new PropertiesDependentEnhancer(context.getBean(ThirdProperties.class))
            );
            context.registerBean(
                    "propertiesResolvingEnhancer",
                    PropertiesResolvingEnhancer.class,
                    () -> new PropertiesResolvingEnhancer(context.getDefaultListableBeanFactory())
            );

            // when / then
            assertThatCode(context::refresh).doesNotThrowAnyException();
            assertThat(context.getBean(PropertiesResolvingEnhancer.class).invoked).isTrue();
            assertThat(context.getBean(PropertiesDependentEnhancer.class).invoked).isTrue();
        }
    }

    @ConfigurationProperties("first")
    static class FirstProperties {

    }

    @ConfigurationProperties("second")
    static class SecondProperties {

    }

    @ConfigurationProperties("third")
    static class ThirdProperties {

    }

    static class PropertiesDependentEnhancer implements ConfigurationEnhancer {

        private boolean invoked = false;

        PropertiesDependentEnhancer(ThirdProperties properties) {
        }

        @Override
        public void enhance(ComponentRegistry registry) {
            invoked = true;
        }
    }

    static class AdvisorDeclaration {

        ConfigurationPropertiesBindHandlerAdvisor bindHandlerAdvisor() {
            return bindHandler -> bindHandler;
        }
    }

    static class PropertiesResolvingEnhancer implements ConfigurationEnhancer {

        private final BeanFactory beanFactory;

        private boolean invoked = false;

        PropertiesResolvingEnhancer(BeanFactory beanFactory) {
            this.beanFactory = beanFactory;
        }

        @Override
        public void enhance(ComponentRegistry registry) {
            invoked = true;
            beanFactory.getBean(SecondProperties.class);
        }
    }
}
