package org.axonframework.extension.micronaut.config;

import io.micronaut.context.BeanContext;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.axonframework.common.TypeReference;
import org.axonframework.common.configuration.BaseModule;
import org.axonframework.common.configuration.Component;
import org.axonframework.common.configuration.ComponentDecorator;
import org.axonframework.common.configuration.ComponentDefinition;
import org.axonframework.common.configuration.ComponentFactory;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.common.configuration.DecoratorDefinition;
import org.axonframework.common.configuration.DefaultComponentRegistry;
import org.axonframework.common.configuration.InstantiatedComponentDefinition;
import org.axonframework.common.configuration.LifecycleRegistry;
import org.axonframework.common.configuration.Module;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.commandhandling.interception.InterceptingCommandBus;
import org.junit.jupiter.api.*;
import org.mockito.internal.util.*;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.micronaut.core.util.StringUtils.TRUE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(rebuildContext = true)
class MicronautComponentRegistryTest {

    private static final String CUSTOM_COMPONENT_REGISTRY_ENABLED_PROPERTY = "spec.MicronautComponentRegistryTest.customComponentRegistry";

    @Inject
    private BeanContext beanContext;

    @Test
    void expectToBeAvailableInContext() {
        assertThat(beanContext.getBean(ComponentRegistry.class) instanceof MicronautComponentRegistry).isTrue();
    }

    @Property(name = CUSTOM_COMPONENT_REGISTRY_ENABLED_PROPERTY, value = TRUE)
    @Test
    void expectToBeOverridable() {
        assertTrue(beanContext.getBean(ComponentRegistry.class) instanceof DefaultComponentRegistry);
    }

    @Property(name = DecoratorDefinitionContext.ENABLED_PROPERTY, value = TRUE)
    @Test
    void validateDecoratorDefinitionsAreInvoked() {
        assertTrue(beanContext.containsBean(CommandBus.class));
        // TODO: can't be implemented until DecoratedComponent type is exposed before `component.resolve` is ran
        // assertTrue(beanContext.containsBean(InterceptingCommandBus.class));
        var decoratorStarted = beanContext.findBean(AtomicBoolean.class,
                                                    Qualifiers.byName(DecoratorDefinitionContext.DECORATOR_STARTED_NAME));
        assertTrue(decoratorStarted.isPresent());
        await().pollDelay(Duration.ofMillis(50))
               .atMost(Duration.ofSeconds(1))
               .untilTrue(decoratorStarted.get());
    }

    @Property(name = ModuleContext.ENABLED_PROPERTY, value = TRUE)
    @Test
    void validateModuleBeanCreationMethodAddsModuleToAxonConfiguration() {
        var module = beanContext.findBean(Module.class);
        assertThat(module.isPresent()).isTrue();
        assertThat(module.get().name()).isEqualTo(ModuleContext.MODULE_NAME);

        Configuration axonConfiguration = beanContext.getBean(Configuration.class);
        assertThat(axonConfiguration.getModuleConfigurations()).hasSize(1);
        Optional<Configuration> testModuleConfig =
                axonConfiguration.getModuleConfiguration(ModuleContext.MODULE_NAME);
        assertThat(testModuleConfig).isNotEmpty();
        // Validate if the MODULE_SPECIFIC_STRING, that is only registered by the TestModule internally,
        //  is not in the Application Context.
        assertThat(testModuleConfig.get().getComponent(Object.class, ModuleContext.MODULE_SPECIFIC_STRING))
                .isEqualTo(ModuleContext.MODULE_SPECIFIC_STRING);
        assertThat(beanContext.containsBean(Object.class,
                                            Qualifiers.byName(ModuleContext.MODULE_SPECIFIC_STRING))).isFalse();

        // We expect a Module-specific bean to be registered for both a start and shutdown handler.
        Collection<MicronautLifecycleStartHandler> startHandlers = beanContext.getBeansOfType(
                MicronautLifecycleStartHandler.class);
        Collection<MicronautLifecycleShutdownHandler> shutdownHandlers = beanContext.getBeansOfType(
                MicronautLifecycleShutdownHandler.class);


        assertTrue(startHandlers.stream().anyMatch(h -> h.getOrder() == ModuleContext.START_PHASE));
        assertTrue(shutdownHandlers.stream().anyMatch(h -> h.getOrder() == ModuleContext.SHUTDOWN_PHASE));


        assertThat(beanContext.getBean(AtomicBoolean.class,
                                       Qualifiers.byName(ModuleContext.LIFECYCLE_START_NAME))).isTrue();
        AtomicBoolean moduleSpecificShutdownHandlerInvoked = beanContext.getBean(AtomicBoolean.class,
                                                                                 Qualifiers.byName(ModuleContext.LIFECYCLE_SHUTDOWN_NAME));
        assertThat(moduleSpecificShutdownHandlerInvoked).isFalse();

        beanContext.stop();
        assertThat(moduleSpecificShutdownHandlerInvoked).isTrue();
        // don't break @MicronautTest
        beanContext.start();
    }

    @Property(name = ComponentFactoryContext.ENABLED_PROPERTY, value = TRUE)
    @Test
    void validateComponentFactoryBeanUsage() {
        var shutdownHandlers = beanContext.getBeansOfType(MicronautLifecycleShutdownHandler.class);
        assertTrue(shutdownHandlers.stream().anyMatch(h -> h.getOrder() == ComponentFactoryContext.SHUTDOWN_PHASE));
        AtomicBoolean factoryShutdownHandlerInvoked = beanContext.getBean(AtomicBoolean.class,
                                                                          Qualifiers.byName(ComponentFactoryContext.FACTORY_SHUTDOWN_HANDLER_INVOKED_NAME));
        assertThat(factoryShutdownHandlerInvoked).isFalse();
        beanContext.stop();
        assertThat(factoryShutdownHandlerInvoked).isTrue();
        // don't break @MicronautTest
        beanContext.start();
    }

    @Property(name = GenericComponentRegistrationContext.ENABLED_PROPERTY, value = TRUE)
    @Test
    void configurationEnhancerRegisteredGenericComponentsAreCorrectlyRegisteredInApplicationContext() {
        var myDependentBean = beanContext.getBean(GenericComponentRegistrationContext.MyDependentBean.class);

        assertThat(myDependentBean.beanOne().field()).isEqualTo("helloWorld");
        assertThat(myDependentBean.beanTwo().field()).isEqualTo(42L);
    }


    @Property(name = LifecycleContext.ENABLED_PROPERTY, value = TRUE)
    @Test
    void componentDefinitionsIntegrateWithLifecycleRegistryThroughDedicatedLifecycleBeans() {
        // We expect beans to be registered for lifecycle handlers.
        Collection<MicronautLifecycleStartHandler> startHandlers = beanContext.getBeansOfType(
                MicronautLifecycleStartHandler.class);
        Collection<MicronautLifecycleShutdownHandler> shutdownHandlers = beanContext.getBeansOfType(
                MicronautLifecycleShutdownHandler.class);

        assertTrue(startHandlers.stream().anyMatch(h -> h.getOrder() == LifecycleContext.START_PHASE));
        assertTrue(shutdownHandlers.stream().anyMatch(h -> h.getOrder() == LifecycleContext.SHUTDOWN_PHASE));

        assertThat(beanContext.getBean(AtomicBoolean.class,
                                       Qualifiers.byName(LifecycleContext.LIFECYCLE_START_NAME))).isTrue();
        AtomicBoolean shutdownHandlerInvoked = beanContext.getBean(AtomicBoolean.class,
                                                                   Qualifiers.byName(LifecycleContext.LIFECYCLE_SHUTDOWN_NAME));
        assertThat(shutdownHandlerInvoked).isFalse();

        beanContext.stop();
        assertThat(shutdownHandlerInvoked).isTrue();
        // don't break @MicronautTest
        beanContext.start();
    }


    @Property(name = BeanDecoratorDefinitionContext.ENABLED_PROPERTY, value = TRUE)
    @Disabled("Unsupported yet")
    @Test
    void validateDecoratorDefinitionsAreInvokedOnMicronautBeans() {
        assertThat(beanContext.getBean(String.class)).isEqualTo(BeanDecoratorDefinitionContext.CUSTOM_BEAN_OVERRIDE);
        var decoratorStarted = beanContext.findBean(AtomicBoolean.class,
                                                    Qualifiers.byName(BeanDecoratorDefinitionContext.DECORATOR_STARTED_NAME));
        assertThat(decoratorStarted.isPresent()).isTrue();
        await().pollDelay(Duration.ofMillis(50))
               .atMost(Duration.ofSeconds(1))
               .untilTrue(decoratorStarted.get());
    }

    @Factory
    @Requires(property = BeanDecoratorDefinitionContext.ENABLED_PROPERTY, value = TRUE)
    public static class BeanDecoratorDefinitionContext {

        private static final String ENABLED_PROPERTY = "spec.MicronautComponentRegistry.BeanDecoratorDefinitionContext";
        private static final String DECORATOR_STARTED_NAME = "spec.MicronautComponentRegistry.BeanDecoratorDefinitionContext.decoratorStarted";
        private static final String CUSTOM_BEAN_OVERRIDE = "override";

        @Singleton
        @Named(DECORATOR_STARTED_NAME)
        AtomicBoolean decoratorStarted() {
            return new AtomicBoolean(false);
        }

        @Singleton
        String customBeam() {
            return "custom";
        }

        @Singleton
        DecoratorDefinition<String, String> interceptingDecorator(
                @Named(DECORATOR_STARTED_NAME) AtomicBoolean decoratorStarted) {
            return DecoratorDefinition.forType(String.class)
                                      .with((config, name, delegate) ->
                                                    CUSTOM_BEAN_OVERRIDE
                                      )
                                      .order(0)
                                      .onStart(0, component -> decoratorStarted.set(true));
        }
    }

    @Factory
    @Requires(property = DecoratorDefinitionContext.ENABLED_PROPERTY, value = TRUE)
    public static class DecoratorDefinitionContext {

        private static final String ENABLED_PROPERTY = "spec.MicronautComponentRegistry.DecoratorDefinitionContext";
        private static final String DECORATOR_STARTED_NAME = "spec.MicronautComponentRegistry.DecoratorDefinitionContext.decoratorStarted";

        @Singleton
        @Named(DECORATOR_STARTED_NAME)
        AtomicBoolean decoratorStarted() {
            return new AtomicBoolean(false);
        }

        @Singleton
        DecoratorDefinition<CommandBus, InterceptingCommandBus> interceptingDecorator(
                @Named(DECORATOR_STARTED_NAME) AtomicBoolean decoratorStarted) {
            return DecoratorDefinition.forType(CommandBus.class)
                                      .with((config, name, delegate) ->
                                                    new InterceptingCommandBus(delegate, List.of(), List.of()))
                                      .order(0)
                                      .onStart(0, component -> decoratorStarted.set(true));
        }
    }

    @Factory
    @Requires(property = ConfigurationEnhancerContext.ENABLED_PROPERTY, value = TRUE)
    public static class ConfigurationEnhancerContext {

        private static final String ENABLED_PROPERTY = "spec.MicronautComponentRegistry.ConfigurationEnhancerContext";

        @Singleton
        ConfigurationEnhancer configurationEnhancer() {
            return registry -> registry.registerDecorator(
                    CommandBus.class, 0,
                    (ComponentDecorator<CommandBus, CommandBus>) (config, name, delegate) ->
                            new InterceptingCommandBus(delegate, List.of(), List.of())
            );
        }
    }

    @Factory
    @Requires(property = ModuleContext.ENABLED_PROPERTY, value = TRUE)
    public static class ModuleContext {

        private static final String ENABLED_PROPERTY = "spec.MicronautComponentRegistry.ModuleContext";
        private static final String LIFECYCLE_START_NAME = "spec.MicronautComponentRegistry.ModuleContext.startHandlerInvoked";
        private static final String MODULE_NAME = "testModule";
        private static final String LIFECYCLE_SHUTDOWN_NAME = "spec.MicronautComponentRegistry.ModuleContext.shutdownHandlerInvoked";
        private static final String MODULE_SPECIFIC_STRING = "Some String That Is Only Present Here!";
        private static final int START_PHASE = 1337;
        private static final int SHUTDOWN_PHASE = 7331;

        @Singleton
        @Named(LIFECYCLE_START_NAME)
        AtomicBoolean startHandlerInvoked() {
            return new AtomicBoolean(false);
        }

        @Singleton
        @Named(LIFECYCLE_SHUTDOWN_NAME)
        AtomicBoolean shutdownHandlerInvoked() {
            return new AtomicBoolean(false);
        }

        @Singleton
        <S extends BaseModule<S>> BaseModule<S> testModule(
                @Named(LIFECYCLE_START_NAME) AtomicBoolean moduleSpecificStartHandlerInvoked,
                @Named(LIFECYCLE_SHUTDOWN_NAME) AtomicBoolean moduleSpecificShutdownHandlerInvoked) {
            return new BaseModule<>(MODULE_NAME) {
                @Override
                public Configuration build(
                        Configuration parent,
                        LifecycleRegistry lifecycleRegistry
                ) {
                    lifecycleRegistry.onStart(START_PHASE, () -> moduleSpecificStartHandlerInvoked.set(true));
                    lifecycleRegistry.onShutdown(SHUTDOWN_PHASE, () -> moduleSpecificShutdownHandlerInvoked.set(true));
                    componentRegistry(registry -> registry.registerComponent(
                            Object.class, MODULE_SPECIFIC_STRING, c -> MODULE_SPECIFIC_STRING
                    ));
                    return super.build(parent, lifecycleRegistry);
                }
            };
        }
    }

    @Factory
    @Requires(property = ComponentFactoryContext.ENABLED_PROPERTY, value = TRUE)
    public static class ComponentFactoryContext {

        private static final String ENABLED_PROPERTY = "spec.MicronautComponentRegistry.ComponentFactoryContext";
        private static final String FACTORY_SHUTDOWN_HANDLER_INVOKED_NAME = "spec.MicronautComponentRegistry.ComponentFactoryContext.factoryShutdownHandlerInvoked";
        private static final String FACTORY_SPECIFIC_STRING = "Some Factory Made String.";
        private static final int SHUTDOWN_PHASE = 9001;


        @Singleton
        @Named(FACTORY_SHUTDOWN_HANDLER_INVOKED_NAME)
        AtomicBoolean factoryShutdownHandlerInvoked() {
            return new AtomicBoolean(false);
        }

        @Singleton
        ComponentFactory<String> testComponentFactory(@Named(FACTORY_SHUTDOWN_HANDLER_INVOKED_NAME)
                                                      AtomicBoolean factoryShutdownHandlerInvoked) {
            return new ComponentFactory<>() {
                @Override
                public void describeTo(ComponentDescriptor descriptor) {
                    // Not implemented as not important.
                }

                @Override
                public Class<String> forType() {
                    return String.class;
                }

                @Override
                public Optional<Component<String>> construct(
                        String name,
                        Configuration config
                ) {
                    return Optional.of(new InstantiatedComponentDefinition<>(
                            new Component.Identifier<>(forType(), name),
                            name + FACTORY_SPECIFIC_STRING
                    ));
                }

                @Override
                public void registerShutdownHandlers(LifecycleRegistry registry) {
                    registry.onShutdown(9001, () -> factoryShutdownHandlerInvoked.set(true));
                }
            };
        }
    }

    @Factory
    @Requires(property = GenericComponentRegistrationContext.ENABLED_PROPERTY, value = TRUE)
    public static class GenericComponentRegistrationContext {

        private static final String ENABLED_PROPERTY = "spec.MicronautComponentRegistry.GenericComponentRegistrationContext";

        @Singleton
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

        @Singleton
        MyDependentBean<String, Long> myDependentBean(MyGenericBean<String> stringBean,
                                                      MyGenericBean<Long> longBean) {
            return new MyDependentBean<>(stringBean, longBean);
        }


        private record MyGenericBean<T>(T field) {

        }

        private record MyDependentBean<T, S>(MyGenericBean<T> beanOne, MyGenericBean<S> beanTwo) {

        }
    }


    @Factory
    @Requires(property = LifecycleContext.ENABLED_PROPERTY, value = TRUE)
    public static class LifecycleContext {

        private static final String ENABLED_PROPERTY = "spec.MicronautComponentRegistry.LifecycleContext";

        private static final String LIFECYCLE_START_NAME = "spec.MicronautComponentRegistry.LifecycleContext.startHandlerInvoked";
        private static final String LIFECYCLE_SHUTDOWN_NAME = "spec.MicronautComponentRegistry.LifecycleContext.shutdownHandlerInvoked";
        private static final int START_PHASE = 1337;
        private static final int SHUTDOWN_PHASE = 7331;


        @Singleton
        @Named(LIFECYCLE_START_NAME)
        AtomicBoolean startHandlerInvoked() {
            return new AtomicBoolean(false);
        }

        @Singleton
        @Named(LIFECYCLE_SHUTDOWN_NAME)
        AtomicBoolean shutdownHandlerInvoked() {
            return new AtomicBoolean(false);
        }

        @Singleton
        ConfigurationEnhancer lifecycleBeanAdder(
                @Named(LIFECYCLE_START_NAME) AtomicBoolean componentSpecificStartHandlerInvoked,
                @Named(LIFECYCLE_SHUTDOWN_NAME) AtomicBoolean componentSpecificShutdownHandlerInvoked) {
            return registry -> registry.registerComponent(
                    ComponentDefinition.ofType(TestComponent.class)
                                       .withBuilder(c -> new TestComponent(componentSpecificStartHandlerInvoked,
                                                                           componentSpecificShutdownHandlerInvoked))
                                       .onStart(START_PHASE, TestComponent::start)
                                       .onShutdown(SHUTDOWN_PHASE, TestComponent::shutdown)
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

    @Requires(property = CUSTOM_COMPONENT_REGISTRY_ENABLED_PROPERTY, value = TRUE)
    @Singleton
    ComponentRegistry customComponentRegistry() {
        return new DefaultComponentRegistry();
    }
}
