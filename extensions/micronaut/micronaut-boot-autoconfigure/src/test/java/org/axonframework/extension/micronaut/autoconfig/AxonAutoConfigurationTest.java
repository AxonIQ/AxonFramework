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

package org.axonframework.extension.micronaut.autoconfig;

import org.axonframework.axonserver.connector.AxonServerConfigurationEnhancer;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.commandhandling.interception.InterceptingCommandBus;
import org.axonframework.messaging.commandhandling.SimpleCommandBus;
import org.axonframework.common.TypeReference;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.common.configuration.BaseModule;
import org.axonframework.common.configuration.Component;
import org.axonframework.common.configuration.ComponentDecorator;
import org.axonframework.common.configuration.ComponentDefinition;
import org.axonframework.common.configuration.ComponentFactory;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.common.configuration.DecoratorDefinition;
import org.axonframework.common.configuration.InstantiatedComponentDefinition;
import org.axonframework.common.configuration.LifecycleRegistry;
import org.axonframework.common.configuration.Module;
import org.axonframework.messaging.core.EmptyApplicationContext;
import org.axonframework.messaging.core.unitofwork.SimpleUnitOfWorkFactory;
import org.axonframework.extension.micronaut.config.MicronautAxonApplication;
import org.axonframework.extension.micronaut.config.MicronautComponentRegistry;
import org.axonframework.extension.micronaut.config.MicronautLifecycleRegistry;
import org.axonframework.extension.micronaut.config.MicronautLifecycleShutdownHandler;
import org.axonframework.extension.micronaut.config.MicronautLifecycleStartHandler;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class validating the {@link AxonAutoConfiguration}.
 *
 * @author Allard Buijze
 * @author Steven van Beelen
 */
public class AxonAutoConfigurationTest {

    private static final String MODULE_SPECIFIC_STRING = "Some String That Is Only Present Here!";
    private static final String FACTORY_SPECIFIC_STRING = "Some Factory Made String.";

    private ApplicationContextRunner testContext;

    @BeforeEach
    void setUp() {
        testContext = new ApplicationContextRunner()
                .withUserConfiguration(TestContext.class)
                .withPropertyValues("axon.axonserver.enabled=false", "axon.eventstorage.jpa.polling-interval=0");
    }

    @Test
    void expectedAxonConfigurationBeansAreAutomaticallyConfigured() {
        testContext.run(context -> {
            assertThat(context).hasBean("springComponentRegistry");
            assertThat(context).hasBean("springLifecycleRegistry");
            assertThat(context).hasBean("axonApplication");
            assertThat(context).hasBean("axonApplicationConfiguration");
        });
    }

    @Test
    void expectedAxonConfigurationBeansCanAllBeCustomized() {
        testContext.withUserConfiguration(CustomContext.class).run(context -> {
            assertThat(context).hasBean("customComponentRegistry");
            assertThat(context).hasBean("customLifecycleRegistry");
            assertThat(context).hasBean("customAxonApplication");
            assertThat(context).hasBean("customAxonApplicationConfiguration");

            assertThat(context).doesNotHaveBean("springComponentRegistry");
            assertThat(context).doesNotHaveBean("springLifecycleRegistry");
            assertThat(context).doesNotHaveBean("axonApplication");
            assertThat(context).doesNotHaveBean("axonApplicationConfiguration");
            assertThat(context).doesNotHaveBean("axonConfiguration");
        });
    }

    @Test
    void lifecycleRegistryIntegratesWithDedicatedSpringLifecycleBeans() {
        testContext.run(context -> {
            // We expect beans to be registered for lifecycle handlers.
            Map<String, MicronautLifecycleStartHandler> startHandlers = BeanFactoryUtils.beansOfTypeIncludingAncestors(
                    context, MicronautLifecycleStartHandler.class
            );
            Map<String, MicronautLifecycleShutdownHandler> shutdownHandlers = BeanFactoryUtils.beansOfTypeIncludingAncestors(
                    context, MicronautLifecycleShutdownHandler.class
            );

            // The TestContext registers a start handler on phase 10 for the SimpleCommandBus
            assertTrue(startHandlers.values().stream().anyMatch(h -> h.getPhase() == 10));
            // The TestContext registers a shutdown handler on phase 12 for the SimpleCommandBus
            assertTrue(shutdownHandlers.values().stream().anyMatch(h -> h.getPhase() == 12));

            for (MicronautLifecycleStartHandler startHandler : startHandlers.values()) {
                assertTrue(startHandler.isRunning());
            }
            for (MicronautLifecycleShutdownHandler shutdownHandler : shutdownHandlers.values()) {
                assertTrue(shutdownHandler.isRunning());
            }

            assertThat(context.getBean("startHandlerInvoked", AtomicBoolean.class)).isTrue();
            AtomicBoolean shutdownHandlerInvoked = context.getBean("shutdownHandlerInvoked", AtomicBoolean.class);
            assertThat(shutdownHandlerInvoked).isFalse();

            // Shutdown the Application Context to validate the shutdown handler is invoked.
            context.stop();
            assertThat(shutdownHandlerInvoked).isTrue();
        });
    }

    @Test
    void componentDefinitionsIntegrateWithSpringLifecycleRegistryThroughDedicatedSpringLifecycleBeans() {
        testContext.withUserConfiguration(LifecycleContext.class).run(context -> {
            // We expect beans to be registered for lifecycle handlers.
            Map<String, MicronautLifecycleStartHandler> startHandlers = BeanFactoryUtils.beansOfTypeIncludingAncestors(
                    context, MicronautLifecycleStartHandler.class
            );
            Map<String, MicronautLifecycleShutdownHandler> shutdownHandlers = BeanFactoryUtils.beansOfTypeIncludingAncestors(
                    context, MicronautLifecycleShutdownHandler.class
            );

            // The LifecycleContext registers a start handler on phase 7 for the TestComponent
            assertTrue(startHandlers.values().stream().anyMatch(h -> h.getPhase() == 7));
            // The LifecycleContext registers a shutdown handler on phase 14 for the TestComponent
            assertTrue(shutdownHandlers.values().stream().anyMatch(h -> h.getPhase() == 14));

            for (MicronautLifecycleStartHandler startHandler : startHandlers.values()) {
                assertTrue(startHandler.isRunning());
            }
            for (MicronautLifecycleShutdownHandler shutdownHandler : shutdownHandlers.values()) {
                assertTrue(shutdownHandler.isRunning());
            }

            assertThat(context.getBean("componentSpecificStartHandlerInvoked", AtomicBoolean.class)).isTrue();
            AtomicBoolean shutdownHandlerInvoked =
                    context.getBean("componentSpecificShutdownHandlerInvoked", AtomicBoolean.class);
            assertThat(shutdownHandlerInvoked).isFalse();

            // Shutdown the Application Context to validate the shutdown handler is invoked.
            context.stop();
            assertThat(shutdownHandlerInvoked).isTrue();
        });
    }

    @Test
    void validateDecoratorDefinitionsAreInvoked() {
        testContext.withUserConfiguration(DecoratorDefinitionContext.class).run(context -> {
            assertThat(context).hasBean("commandBus");
            assertThat(context).hasSingleBean(InterceptingCommandBus.class);
        });
    }

    @Test
    void validateConfigurationEnhancersAreInvokedAndCanDecorateComponents() {
        testContext.withUserConfiguration(ConfigurationEnhancerContext.class).run(context -> {
            assertThat(context).hasBean("commandBus");
            assertThat(context).hasSingleBean(InterceptingCommandBus.class);
        });
    }

    @Test
    void validateModuleBeanCreationMethodAddsModuleToAxonConfiguration() {
        testContext.withUserConfiguration(ModuleContext.class).run(context -> {
            assertThat(context).hasBean("testModule");

            AxonConfiguration axonConfiguration = context.getBean(AxonConfiguration.class);
            assertThat(axonConfiguration.getModuleConfigurations()).hasSize(1);
            Optional<org.axonframework.common.configuration.Configuration> testModuleConfig =
                    axonConfiguration.getModuleConfiguration("testModule");
            assertThat(testModuleConfig).isNotEmpty();
            // Validate if the MODULE_SPECIFIC_STRING, that is only registered by the TestModule internally,
            //  is not in the Application Context.
            assertThat(testModuleConfig.get().getComponent(Object.class, MODULE_SPECIFIC_STRING))
                    .isEqualTo(MODULE_SPECIFIC_STRING);
            assertThat(context).doesNotHaveBean(MODULE_SPECIFIC_STRING);

            // We expect a Module-specific bean to be registered for both a start and shutdown handler.
            Map<String, MicronautLifecycleStartHandler> startHandlers = BeanFactoryUtils.beansOfTypeIncludingAncestors(
                    context, MicronautLifecycleStartHandler.class
            );
            Map<String, MicronautLifecycleShutdownHandler> shutdownHandlers = BeanFactoryUtils.beansOfTypeIncludingAncestors(
                    context, MicronautLifecycleShutdownHandler.class
            );

            // The testModule in the TestContext registers a start handler on phase 1337.
            assertTrue(startHandlers.values().stream().anyMatch(h -> h.getPhase() == 1337));
            // The testModule in the TestContext registers a shutdown handler on phase 7331.
            assertTrue(shutdownHandlers.values().stream().anyMatch(h -> h.getPhase() == 7331));

            for (MicronautLifecycleStartHandler startHandler : startHandlers.values()) {
                assertTrue(startHandler.isRunning());
            }
            for (MicronautLifecycleShutdownHandler shutdownHandler : shutdownHandlers.values()) {
                assertTrue(shutdownHandler.isRunning());
            }

            assertThat(context.getBean("moduleSpecificStartHandlerInvoked", AtomicBoolean.class)).isTrue();
            AtomicBoolean moduleSpecificShutdownHandlerInvoked =
                    context.getBean("moduleSpecificShutdownHandlerInvoked", AtomicBoolean.class);
            assertThat(moduleSpecificShutdownHandlerInvoked).isFalse();

            // Shutdown the Application Context to validate the shutdown handler is invoked.
            context.stop();
            assertThat(moduleSpecificShutdownHandlerInvoked).isTrue();
        });
    }

    @Test
    void validateComponentFactoryBeanUsage() {
        testContext.withUserConfiguration(ComponentFactoryContext.class).run(context -> {
            assertThat(context).hasBean("testComponentFactory");

            Map<String, MicronautLifecycleShutdownHandler> shutdownHandlers = BeanFactoryUtils.beansOfTypeIncludingAncestors(
                    context, MicronautLifecycleShutdownHandler.class
            );

            // The testComponentFactory in the TestContext registers a shutdown handler on phase 9001.
            assertTrue(shutdownHandlers.values().stream().anyMatch(h -> h.getPhase() == 9001));
            for (MicronautLifecycleShutdownHandler shutdownHandler : shutdownHandlers.values()) {
                assertTrue(shutdownHandler.isRunning());
            }

            AtomicBoolean factoryShutdownHandlerInvoked =
                    context.getBean("factoryShutdownHandlerInvoked", AtomicBoolean.class);
            assertThat(factoryShutdownHandlerInvoked).isFalse();

            // Shutdown the Application Context to validate the shutdown handler is invoked.
            context.stop();
            assertThat(factoryShutdownHandlerInvoked).isTrue();
        });
    }

    @Test
    void configurationEnhancerRegisteredGenericComponentsAreCorrectlyRegisteredInApplicationContext() {
        testContext.withUserConfiguration(GenericComponentRegistrationContext.class).run(context -> {
            assertThat(context).hasBean("myDependentBean");

            @SuppressWarnings("unchecked")
            MyDependentBean<String, Long> myDependentBean = context.getBean("myDependentBean", MyDependentBean.class);

            assertThat(myDependentBean.beanOne().field()).isEqualTo("helloWorld");
            assertThat(myDependentBean.beanTwo().field()).isEqualTo(42L);
        });
    }

    @Configuration
    @EnableAutoConfiguration
    public static class TestContext {

        @Bean
        ConfigurationEnhancer disableServerEnhancer() {
            return new ConfigurationEnhancer() {

                @Override
                public void enhance(@NotNull ComponentRegistry registry) {
                    registry.disableEnhancer(AxonServerConfigurationEnhancer.class);
                }

                @Override
                public int order() {
                    return Integer.MIN_VALUE;
                }
            };
        }

        @Bean
        AtomicBoolean startHandlerInvoked() {
            return new AtomicBoolean(false);
        }

        @Bean
        AtomicBoolean shutdownHandlerInvoked() {
            return new AtomicBoolean(false);
        }

        @Bean
        CommandBus commandBus(LifecycleRegistry lifecycleRegistry,
                              AtomicBoolean startHandlerInvoked,
                              AtomicBoolean shutdownHandlerInvoked) {
            CommandBus simpleCommandBus = new SimpleCommandBus(new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE), Collections.emptyList());
            lifecycleRegistry.onStart(10, () -> startHandlerInvoked.set(true));
            lifecycleRegistry.onShutdown(12, () -> shutdownHandlerInvoked.set(true));
            return simpleCommandBus;
        }
    }

    @Configuration
    @EnableAutoConfiguration
    public static class CustomContext {

        @Bean
        MicronautComponentRegistry customComponentRegistry(ApplicationContext applicationContext,
                                                           MicronautLifecycleRegistry lifecycleRegistry) {
            return new MicronautComponentRegistry(applicationContext, lifecycleRegistry);
        }

        @Bean
        MicronautLifecycleRegistry customLifecycleRegistry() {
            return new MicronautLifecycleRegistry();
        }

        @Bean
        MicronautAxonApplication customAxonApplication(MicronautComponentRegistry customComponentRegistry,
                                                       MicronautLifecycleRegistry customLifecycleRegistry) {
            return new MicronautAxonApplication(customComponentRegistry, customLifecycleRegistry);
        }

        @Bean
        AxonConfiguration customAxonApplicationConfiguration(MicronautAxonApplication customAxonApplication) {
            return customAxonApplication.build();
        }
    }

    @Configuration
    @EnableAutoConfiguration
    public static class LifecycleContext {

        @Bean
        AtomicBoolean componentSpecificStartHandlerInvoked() {
            return new AtomicBoolean(false);
        }

        @Bean
        AtomicBoolean componentSpecificShutdownHandlerInvoked() {
            return new AtomicBoolean(false);
        }

        @Bean
        ConfigurationEnhancer lifecycleBeanAdder(AtomicBoolean componentSpecificStartHandlerInvoked,
                                                 AtomicBoolean componentSpecificShutdownHandlerInvoked) {
            return registry -> registry.registerComponent(
                    ComponentDefinition.ofType(TestComponent.class)
                                       .withBuilder(c -> new TestComponent(componentSpecificStartHandlerInvoked,
                                                                           componentSpecificShutdownHandlerInvoked))
                                       .onStart(7, TestComponent::start)
                                       .onShutdown(14, TestComponent::shutdown)
            );
        }

        private record TestComponent(AtomicBoolean started,
                                     AtomicBoolean stopped) {

            public void start() {
                started.set(true);
            }

            public void shutdown() {
                stopped.set(true);
            }
        }
    }

    @Configuration
    @EnableAutoConfiguration
    public static class DecoratorDefinitionContext {

        @Bean
        DecoratorDefinition<CommandBus, InterceptingCommandBus> interceptingDecorator() {
            return DecoratorDefinition.forType(CommandBus.class)
                                      .with((config, name, delegate) ->
                                                    new InterceptingCommandBus(delegate, List.of(), List.of()))
                                      .order(0);
        }
    }

    @Configuration
    @EnableAutoConfiguration
    public static class ConfigurationEnhancerContext {

        @Bean
        ConfigurationEnhancer configurationEnhancer() {
            return registry -> registry.registerDecorator(
                    CommandBus.class, 0,
                    (ComponentDecorator<CommandBus, CommandBus>) (config, name, delegate) ->
                            new InterceptingCommandBus(delegate, List.of(), List.of())
            );
        }
    }

    @Configuration
    @EnableAutoConfiguration
    public static class ModuleContext {

        @Bean
        AtomicBoolean moduleSpecificStartHandlerInvoked() {
            return new AtomicBoolean(false);
        }

        @Bean
        AtomicBoolean moduleSpecificShutdownHandlerInvoked() {
            return new AtomicBoolean(false);
        }

        @Bean
        Module testModule(AtomicBoolean moduleSpecificStartHandlerInvoked,
                          AtomicBoolean moduleSpecificShutdownHandlerInvoked) {
            //noinspection rawtypes
            return new BaseModule("testModule") {
                @Override
                public org.axonframework.common.configuration.Configuration build(
                        @NotNull org.axonframework.common.configuration.Configuration parent,
                        @NotNull LifecycleRegistry lifecycleRegistry
                ) {
                    lifecycleRegistry.onStart(1337, () -> moduleSpecificStartHandlerInvoked.set(true));
                    lifecycleRegistry.onShutdown(7331, () -> moduleSpecificShutdownHandlerInvoked.set(true));
                    //noinspection unchecked
                    componentRegistry((Consumer<ComponentRegistry>) registry -> registry.registerComponent(
                            Object.class, MODULE_SPECIFIC_STRING, c -> MODULE_SPECIFIC_STRING
                    ));
                    return super.build(parent, lifecycleRegistry);
                }
            };
        }
    }

    @Configuration
    @EnableAutoConfiguration
    public static class ComponentFactoryContext {

        @Bean
        AtomicBoolean factoryShutdownHandlerInvoked() {
            return new AtomicBoolean(false);
        }

        @Bean
        ComponentFactory<String> testComponentFactory(AtomicBoolean factoryShutdownHandlerInvoked) {
            return new ComponentFactory<>() {
                @Override
                public void describeTo(@NotNull ComponentDescriptor descriptor) {
                    // Not implemented as not important.
                }

                @NotNull
                @Override
                public Class<String> forType() {
                    return String.class;
                }

                @NotNull
                @Override
                public Optional<Component<String>> construct(
                        @NotNull String name,
                        @NotNull org.axonframework.common.configuration.Configuration config
                ) {
                    return Optional.of(new InstantiatedComponentDefinition<>(
                            new Component.Identifier<>(forType(), name),
                            name + FACTORY_SPECIFIC_STRING
                    ));
                }

                @Override
                public void registerShutdownHandlers(@NotNull LifecycleRegistry registry) {
                    registry.onShutdown(9001, () -> factoryShutdownHandlerInvoked.set(true));
                }
            };
        }
    }

    @Configuration
    @EnableAutoConfiguration
    public static class GenericComponentRegistrationContext {

        @Bean
        ConfigurationEnhancer genericComponentRegistrationEnhancer() {
            return registry -> {
                ComponentDefinition<MyGenericBean<String>> genericStringBeanDefinition =
                        ComponentDefinition.ofTypeAndName(new TypeReference<MyGenericBean<String>>() {
                                           }, "stringBean")
                                           .withInstance(new MyGenericBean<>("helloWorld"));
                registry.registerComponent(genericStringBeanDefinition);

                ComponentDefinition<MyGenericBean<Integer>> genericIntegerBeanDefinition =
                        ComponentDefinition.ofTypeAndName(new TypeReference<MyGenericBean<Integer>>() {
                                           }, "intBean")
                                           .withInstance(new MyGenericBean<>(1337));
                registry.registerComponent(genericIntegerBeanDefinition);

                ComponentDefinition<MyGenericBean<Long>> genericLongBeanDefinition =
                        ComponentDefinition.ofTypeAndName(new TypeReference<MyGenericBean<Long>>() {
                                           }, "longBean")
                                           .withInstance(new MyGenericBean<>(42L));
                registry.registerComponent(genericLongBeanDefinition);
            };
        }

        @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
        @Bean
        MyDependentBean<String, Long> myDependentBean(MyGenericBean<String> stringBean,
                                                      MyGenericBean<Long> longBean) {
            return new MyDependentBean<>(stringBean, longBean);
        }
    }

    private record MyGenericBean<T>(T field) {

    }

    private record MyDependentBean<T, S>(MyGenericBean<T> beanOne, MyGenericBean<S> beanTwo) {

    }
}
