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
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test class validating that named lookups through the {@link Configuration} of a {@link SpringComponentRegistry} find
 * Spring beans by their bean name, as well as by their {@link Qualifier @Qualifier}.
 *
 * @author Allard Buijze
 */
class SpringComponentRegistryNamedLookupTest {

    private static final TypeReference<Supplier<String>> STRING_SUPPLIER = new TypeReference<>() {
    };
    private static final TypeReference<Supplier<Integer>> INTEGER_SUPPLIER = new TypeReference<>() {
    };
    // Counts instantiations of the lazy beans, to validate that lookups don't instantiate them prematurely.
    private static final AtomicInteger LAZY_INSTANTIATIONS = new AtomicInteger();

    private AnnotationConfigApplicationContext context;
    private Configuration testSubject;

    @BeforeEach
    void setUp() {
        LAZY_INSTANTIATIONS.set(0);
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
        context.register(GreeterConfiguration.class, QualifiedGreeter.class);
        context.registerAlias("plainGreeter", "greeterAlias");
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
        void findsBeanByBeanName() {
            // when / then
            assertThat(testSubject.hasComponent(Greeter.class, "plainGreeter")).isTrue();
        }

        @Test
        void findsBeanByQualifierOnFactoryMethod() {
            // when / then
            assertThat(testSubject.hasComponent(Greeter.class, "special")).isTrue();
        }

        @Test
        void findsBeanByQualifierOnBeanClass() {
            // when / then
            assertThat(testSubject.hasComponent(Greeter.class, "classLevel")).isTrue();
        }

        @Test
        void findsQualifiedBeanWithoutInstantiatingIt() {
            // when
            boolean result = testSubject.hasComponent(Greeter.class, "lazy");

            // then
            assertThat(result).isTrue();
            assertThat(LAZY_INSTANTIATIONS).hasValue(0);
        }

        @Test
        void findsBeanByAliasBeforeBeanCarryingTheAliasAsQualifier() {
            // given - "greeterAlias" is an alias of "plainGreeter" and the qualifier of "aliasQualifiedGreeter"
            // when / then
            assertThat(testSubject.hasComponent(Greeter.class, "greeterAlias")).isTrue();
        }

        @Test
        void findsFactoryBeanProductByQualifierOnItsClassWithoutInitializingTheFactoryBean() {
            // when
            boolean result = testSubject.hasComponent(Greeter.class, "fromFactory");

            // then
            assertThat(result).isTrue();
            assertThat(LAZY_INSTANTIATIONS).hasValue(0);
        }

        @Test
        void findsBeanByQualifierAndGenericType() {
            // when / then
            assertThat(testSubject.hasComponent(STRING_SUPPLIER, "generic")).isTrue();
            assertThat(testSubject.hasComponent(INTEGER_SUPPLIER, "generic")).isTrue();
        }

        @Test
        void returnsFalseWhenNoBeanMatchesNameOrQualifier() {
            // when / then
            assertThat(testSubject.hasComponent(Greeter.class, "unknown")).isFalse();
            assertThat(testSubject.hasComponent(STRING_SUPPLIER, "unknown")).isFalse();
        }

        @Test
        void returnsTrueWhenSeveralBeansCarryTheQualifier() {
            // when / then
            assertThat(testSubject.hasComponent(Greeter.class, "shared")).isTrue();
        }

        @Test
        void findsBeanByNameWhenOtherBeansCarryTheNameAsQualifier() {
            // given - "sharedGreeter" is a bean name, while "firstNamedSharedGreeter" and "secondNamedSharedGreeter"
            // both carry it as qualifier
            // when / then
            assertThat(testSubject.hasComponent(Greeter.class, "sharedGreeter")).isTrue();
        }
    }

    @Nested
    class GetOptionalComponent {

        @Test
        void findsBeanByBeanName() {
            // when / then
            assertThat(testSubject.getOptionalComponent(Greeter.class, "plainGreeter"))
                    .containsSame(context.getBean("plainGreeter", Greeter.class));
        }

        @Test
        void findsBeanByQualifierOnFactoryMethod() {
            // when / then
            assertThat(testSubject.getOptionalComponent(Greeter.class, "special"))
                    .containsSame(context.getBean("specialGreeter", Greeter.class));
        }

        @Test
        void findsBeanByQualifierOnBeanClass() {
            // when / then
            assertThat(testSubject.getOptionalComponent(Greeter.class, "classLevel"))
                    .containsSame(context.getBean(QualifiedGreeter.class));
        }

        @Test
        void prefersBeanNameOverQualifierOfAnotherBean() {
            // given - "impostorGreeter" carries qualifier "plainGreeter", which is the name of another bean
            // when / then
            assertThat(testSubject.getOptionalComponent(Greeter.class, "plainGreeter"))
                    .containsSame(context.getBean("plainGreeter", Greeter.class));
        }

        @Test
        void findsBeanByAliasBeforeBeanCarryingTheAliasAsQualifier() {
            // when / then
            assertThat(testSubject.getOptionalComponent(Greeter.class, "greeterAlias"))
                    .containsSame(context.getBean("plainGreeter", Greeter.class));
        }

        @Test
        void findsBeanByQualifierAndGenericType() {
            // when / then
            assertThat(testSubject.getOptionalComponent(STRING_SUPPLIER, "generic"))
                    .get().isSameAs(context.getBean("stringSupplier"));
            assertThat(testSubject.getOptionalComponent(INTEGER_SUPPLIER, "generic"))
                    .get().isSameAs(context.getBean("integerSupplier"));
        }

        @Test
        void returnsEmptyWhenNoBeanMatchesNameOrQualifier() {
            // when / then
            assertThat(testSubject.getOptionalComponent(Greeter.class, "unknown")).isEmpty();
            assertThat(testSubject.getOptionalComponent(STRING_SUPPLIER, "unknown")).isEmpty();
        }

        @Test
        void throwsAmbiguousComponentMatchExceptionWhenSeveralBeansCarryTheQualifier() {
            // when / then
            assertThatThrownBy(() -> testSubject.getOptionalComponent(Greeter.class, "shared"))
                    .isInstanceOf(AmbiguousComponentMatchException.class);
        }
    }

    @Nested
    class GetComponent {

        @Test
        void findsBeanByBeanName() {
            // when / then
            assertThat(testSubject.getComponent(Greeter.class, "plainGreeter"))
                    .isSameAs(context.getBean("plainGreeter"));
        }

        @Test
        void findsBeanByQualifier() {
            // when / then
            assertThat(testSubject.getComponent(Greeter.class, "special"))
                    .isSameAs(context.getBean("specialGreeter"));
        }

        @Test
        void findsBeanByQualifierAndGenericType() {
            // when / then
            assertThat(testSubject.getComponent(STRING_SUPPLIER, "generic"))
                    .isSameAs(context.getBean("stringSupplier"));
        }

        @Test
        void throwsComponentNotFoundExceptionWhenNoBeanMatchesNameOrQualifier() {
            // when / then
            assertThatThrownBy(() -> testSubject.getComponent(Greeter.class, "unknown"))
                    .isInstanceOf(ComponentNotFoundException.class)
                    .hasMessageContaining("unknown");
            assertThatThrownBy(() -> testSubject.getComponent(STRING_SUPPLIER, "unknown"))
                    .isInstanceOf(ComponentNotFoundException.class)
                    .hasMessageContaining("unknown");
        }

        @Test
        void throwsAmbiguousComponentMatchExceptionWhenSeveralBeansCarryTheQualifier() {
            // when / then
            assertThatThrownBy(() -> testSubject.getComponent(Greeter.class, "shared"))
                    .isInstanceOf(AmbiguousComponentMatchException.class);
        }
    }

    interface Greeter {

    }

    static class GreeterConfiguration {

        @Bean
        Greeter plainGreeter() {
            return new Greeter() {
            };
        }

        @Bean
        @Qualifier("special")
        Greeter specialGreeter() {
            return new Greeter() {
            };
        }

        @Bean
        @Qualifier("plainGreeter")
        Greeter impostorGreeter() {
            return new Greeter() {
            };
        }

        @Bean
        @Qualifier("shared")
        Greeter firstSharedGreeter() {
            return new Greeter() {
            };
        }

        @Bean
        @Qualifier("shared")
        Greeter secondSharedGreeter() {
            return new Greeter() {
            };
        }

        @Bean
        Greeter sharedGreeter() {
            return new Greeter() {
            };
        }

        @Bean
        @Qualifier("sharedGreeter")
        Greeter firstNamedSharedGreeter() {
            return new Greeter() {
            };
        }

        @Bean
        @Qualifier("sharedGreeter")
        Greeter secondNamedSharedGreeter() {
            return new Greeter() {
            };
        }

        @Lazy
        @Bean
        @Qualifier("lazy")
        Greeter lazyGreeter() {
            LAZY_INSTANTIATIONS.incrementAndGet();
            return new Greeter() {
            };
        }

        @Bean
        @Qualifier("greeterAlias")
        Greeter aliasQualifiedGreeter() {
            return new Greeter() {
            };
        }

        // The qualifier is on the class of the produced object, so finding it requires the type of that object.
        @Lazy
        @Bean
        FactoryBean<FactoryProducedGreeter> greeterFactoryBean() {
            LAZY_INSTANTIATIONS.incrementAndGet();
            return new FactoryBean<>() {
                @Override
                public FactoryProducedGreeter getObject() {
                    return new FactoryProducedGreeter();
                }

                @Override
                public Class<?> getObjectType() {
                    return FactoryProducedGreeter.class;
                }
            };
        }

        @Bean
        @Qualifier("generic")
        Supplier<String> stringSupplier() {
            return () -> "string";
        }

        @Bean
        @Qualifier("generic")
        Supplier<Integer> integerSupplier() {
            return () -> 42;
        }
    }

    @Qualifier("classLevel")
    static class QualifiedGreeter implements Greeter {

    }

    @Qualifier("fromFactory")
    static class FactoryProducedGreeter implements Greeter {

    }
}
