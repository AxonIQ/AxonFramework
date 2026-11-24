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
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.DecoratorDefinition;
import org.axonframework.extension.spring.config.scanned.ScannedGreeterConfiguration;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportSelector;
import org.springframework.core.type.AnnotationMetadata;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating that the {@link SpringComponentRegistry} decorates and finds {@code @Bean} components
 * regardless of whether Spring reads the declaring configuration class with reflection or with ASM.
 * <p>
 * Spring reads a configuration class with ASM when it finds it by component scanning or imports it by class name, as
 * Spring Boot does for auto-configurations. The registered bean definition of such a {@code @Bean} carries no type,
 * whereas its merged bean definition does. A configuration class registered directly, as most tests do, is read with
 * reflection and thus hides the difference.
 */
public class SpringComponentRegistryAsmReadBeanTest {

    private static final String GREETER_NAME = "greeter";
    private static final TypeReference<Greeter> GREETER_TYPE_REF = new TypeReference<>() {
    };

    private AnnotationConfigApplicationContext context;

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Nested
    class Decoration {

        @Test
        void decoratesABeanOfAConfigurationClassRegisteredDirectly() {
            // given / when
            startContext(DirectGreeterConfiguration.class);

            // then
            assertThat(context.getBean(GREETER_NAME)).isInstanceOf(DecoratedGreeter.class);
        }

        @Test
        void decoratesABeanOfAComponentScannedConfigurationClass() {
            // given / when
            startContext(ScanningConfiguration.class);

            // then
            assertThat(context.getBean(GREETER_NAME)).isInstanceOf(DecoratedGreeter.class);
        }

        @Test
        void decoratesABeanOfAConfigurationClassImportedByClassName() {
            // given / when
            startContext(ImportingConfiguration.class);

            // then
            assertThat(context.getBean(GREETER_NAME)).isInstanceOf(DecoratedGreeter.class);
        }
    }

    @Nested
    class LookupByClassAndName {

        @Test
        void findsABeanOfAComponentScannedConfigurationClass() {
            // given
            Configuration configuration = startContext(ScanningConfiguration.class);

            // when / then
            assertThat(configuration.hasComponent(Greeter.class, GREETER_NAME)).isTrue();
            assertThat(configuration.getComponent(Greeter.class, GREETER_NAME)).isNotNull();
            assertThat(configuration.getOptionalComponent(Greeter.class, GREETER_NAME)).isPresent();
        }

        @Test
        void findsABeanOfAConfigurationClassImportedByClassName() {
            // given
            Configuration configuration = startContext(ImportingConfiguration.class);

            // when / then
            assertThat(configuration.hasComponent(Greeter.class, GREETER_NAME)).isTrue();
            assertThat(configuration.getComponent(Greeter.class, GREETER_NAME)).isNotNull();
            assertThat(configuration.getOptionalComponent(Greeter.class, GREETER_NAME)).isPresent();
        }
    }

    @Nested
    class LookupByTypeReferenceAndName {

        @Test
        void findsABeanOfAComponentScannedConfigurationClass() {
            // given
            Configuration configuration = startContext(ScanningConfiguration.class);

            // when / then
            assertThat(configuration.hasComponent(GREETER_TYPE_REF, GREETER_NAME)).isTrue();
            assertThat(configuration.getComponent(GREETER_TYPE_REF, GREETER_NAME)).isNotNull();
            assertThat(configuration.getOptionalComponent(GREETER_TYPE_REF, GREETER_NAME)).isPresent();
        }

        @Test
        void findsABeanOfAConfigurationClassImportedByClassName() {
            // given
            Configuration configuration = startContext(ImportingConfiguration.class);

            // when / then
            assertThat(configuration.hasComponent(GREETER_TYPE_REF, GREETER_NAME)).isTrue();
            assertThat(configuration.getComponent(GREETER_TYPE_REF, GREETER_NAME)).isNotNull();
            assertThat(configuration.getOptionalComponent(GREETER_TYPE_REF, GREETER_NAME)).isPresent();
        }
    }

    private Configuration startContext(Class<?> configurationClass) {
        context = new AnnotationConfigApplicationContext();
        DefaultListableBeanFactory beanFactory = context.getDefaultListableBeanFactory();

        SpringLifecycleRegistry lifecycleRegistry = new SpringLifecycleRegistry();
        lifecycleRegistry.setBeanFactory(beanFactory);
        SpringComponentRegistry componentRegistry = new SpringComponentRegistry(beanFactory, lifecycleRegistry);
        // Enhancers on the classpath would register the messaging defaults, which these tests do not need.
        componentRegistry.disableEnhancerScanning();
        componentRegistry.registerDecorator(
                DecoratorDefinition.forType(Greeter.class)
                                   .with((config, name, delegate) -> new DecoratedGreeter(delegate))
                                   .order(0)
        );

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
        context.register(configurationClass);
        context.refresh();

        return componentRegistry.configuration();
    }

    /**
     * The component type the decorator applies to.
     */
    public interface Greeter {

        String greet();
    }

    /**
     * The undecorated {@link Greeter}.
     */
    public record DefaultGreeter() implements Greeter {

        @Override
        public String greet() {
            return "hello";
        }
    }

    /**
     * The {@link Greeter} the decorator wraps around every {@link Greeter} component.
     */
    record DecoratedGreeter(Greeter delegate) implements Greeter {

        @Override
        public String greet() {
            return delegate.greet().toUpperCase();
        }
    }

    /**
     * Registered directly, so Spring reads it with reflection.
     */
    @org.springframework.context.annotation.Configuration
    static class DirectGreeterConfiguration {

        @Bean
        Greeter greeter() {
            return new DefaultGreeter();
        }
    }

    @org.springframework.context.annotation.Configuration
    @ComponentScan(basePackageClasses = ScannedGreeterConfiguration.class)
    static class ScanningConfiguration {

    }

    @org.springframework.context.annotation.Configuration
    @Import(GreeterImportSelector.class)
    static class ImportingConfiguration {

    }

    /**
     * Imports {@link ImportedGreeterConfiguration} by class name, as Spring Boot imports auto-configurations, so that
     * Spring reads it with ASM.
     */
    static class GreeterImportSelector implements ImportSelector {

        @Override
        public String[] selectImports(AnnotationMetadata importingClassMetadata) {
            return new String[]{ImportedGreeterConfiguration.class.getName()};
        }
    }

    @org.springframework.context.annotation.Configuration
    static class ImportedGreeterConfiguration {

        @Bean
        Greeter greeter() {
            return new DefaultGreeter();
        }
    }
}
