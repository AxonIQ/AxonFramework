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

package org.axonframework.extension.micronaut.config;

import io.micronaut.context.BeanContext;
import io.micronaut.context.Qualifier;
import io.micronaut.context.RuntimeBeanDefinition;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.exceptions.NonUniqueBeanException;
import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.io.service.SoftServiceLoader;
import io.micronaut.core.reflect.ReflectionUtils;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import io.micronaut.inject.qualifiers.Qualifiers;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import org.axonframework.common.TypeReference;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.configuration.AmbiguousComponentMatchException;
import org.axonframework.common.configuration.Component;
import org.axonframework.common.configuration.ComponentDefinition;
import org.axonframework.common.configuration.ComponentFactory;
import org.axonframework.common.configuration.ComponentOverrideException;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.Components;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.common.configuration.DecoratorDefinition;
import org.axonframework.common.configuration.DefaultComponentRegistry;
import org.axonframework.common.configuration.DuplicateModuleRegistrationException;
import org.axonframework.common.configuration.HierarchicalLifecycleRegistry;
import org.axonframework.common.configuration.LifecycleRegistry;
import org.axonframework.common.configuration.Module;
import org.axonframework.common.configuration.OverridePolicy;
import org.axonframework.common.configuration.SearchScope;
import org.axonframework.common.infra.ComponentDescriptor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * A {@link ComponentRegistry} implementation that connects into Micronaut's ecosystem
 * By using the {@link BeanContext}, this {@link ComponentRegistry} can return any component, regardless of whether it
 * was registered with this {@link ComponentRegistry}, through a {@link ConfigurationEnhancer}, or coming from
 * Micronaut's Application Context directly. The latter integration ensures that <b>any</b> Axon Framework component
 * using the {@link Configuration} resulting from this {@link ComponentRegistry} can retrieve <b>any</b> bean that's
 * available.
 * <p>
 * Currently missing features: Decoration of Micronaut Beans outside manually registered components.
 *
 * @author Daniel Karapishchenko
 * @since 5.1.0
 */
@Singleton
@Requires(missingBeans = ComponentRegistry.class)
public class MicronautComponentRegistry implements ComponentRegistry {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final LifecycleRegistry lifecycleRegistry;

    private final Components localComponents = new Components();

    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final MicronautConfiguration configuration;
    private final BeanContext beanContext;
    private final AxonConfigurationProperties axonConfigurationProperties;

    private final List<Class<? extends ConfigurationEnhancer>> disabledEnhancers = new CopyOnWriteArrayList<>();
    private final List<Class<? extends ConfigurationEnhancer>> invokedEnhancers = new CopyOnWriteArrayList<>();


    /**
     * @param beanContext                 The {@link BeanContext} for registering and retrieving components.
     * @param lifecycleRegistry           The {@link LifecycleRegistry} used to initializes.
     *                                    {@link #registerModule(Module) registered modules} and
     *                                    {@link #registerFactory(ComponentFactory) registered factories}.
     * @param axonConfigurationProperties The {@link AxonConfigurationProperties} used for configuring axon's behavior.
     */
    @Internal
    public MicronautComponentRegistry(BeanContext beanContext,
                                      LifecycleRegistry lifecycleRegistry,
                                      AxonConfigurationProperties axonConfigurationProperties
    ) {
        this.beanContext = beanContext;
        this.lifecycleRegistry = lifecycleRegistry;
        this.axonConfigurationProperties = axonConfigurationProperties;
        this.configuration = new MicronautConfiguration(beanContext);
    }

    /**
     * Scans ConfigurationEnhancers if applicable, can happen immediately as it is not configurable after bean
     * initializationpringsprin
     */
    @PostConstruct
    void postConstruct() {
        if (axonConfigurationProperties.enhancerScanning()) {
            scanForConfigurationEnhancers();
        }
    }

    /**
     * This method builds the final {@link MicronautConfiguration} It does these things in order:
     * <ol>
     *     <li>Invoke {@link ConfigurationEnhancer#enhance(ComponentRegistry)} on all registered enhancers.</li>
     *     <li>Decorate all registered {@code Components} by invoking all {@link #registerDecorator(DecoratorDefinition) registered decorators}.</li>
     *     <li>Registers <b>all</b> {@link Component Components} with the Application Context.</li>
     *     <li>Looks for any {@link Module Modules} and {@link #registerModule(Module) registers} them.</li>
     *     <li>Builds all registered {@code Modules} so that they become available to {@link Configuration#getModuleConfigurations()}.</li>
     *     <li>{@link ComponentFactory#registerShutdownHandlers(LifecycleRegistry) Registers} all {@code ComponentFactory} shutdown handlers with in the {@link LifecycleRegistry}.</li>
     * </ol>
     *
     * @return Built {@link MicronautConfiguration}
     */
    public MicronautConfiguration build() {
        if (initialized.getAndSet(true)) {
            return configuration;
        }
        invokeEnhancers();
        decorateComponents();
        registerLocalComponentsWithApplicationContext();
        buildModuleConfigurations();
        registerFactoryLifecycleHooks();
        return configuration;
    }

    @Override
    public <C> ComponentRegistry registerComponent(ComponentDefinition<? extends C> definition) {
        if (!(definition instanceof ComponentDefinition.ComponentCreator<? extends C> creator)) {
            // The compiler should avoid this from happening.
            throw new IllegalArgumentException("Unsupported component definition type: " + definition);
        }

        // We need to buffer these components, because they may depend on components that aren't
        // registered yet. We will register these in the application context just-in-time.
        Component<? extends C> component = creator.createComponent();
        if (localComponents.contains(component.identifier())) {
            throw new ComponentOverrideException(creator.rawType(), creator.name());
        }

        localComponents.put(component);
        return this;
    }

    @Override
    public <C> ComponentRegistry registerDecorator(DecoratorDefinition<C, ? extends C> definition) {
        if (!(definition instanceof DecoratorDefinition.CompletedDecoratorDefinition<C, ? extends C> decoratorRegistration)) {
            // The compiler should avoid this from happening.
            throw new IllegalArgumentException("Unsupported decorator definition type: " + definition);
        }
        logger.debug("Registering decorator definition: [{}]", definition);

        var annotationMetadata = new MutableAnnotationMetadata();
        annotationMetadata.addDeclaredAnnotation(Order.class.getName(),
                                                 Collections.singletonMap(AnnotationMetadata.VALUE_MEMBER,
                                                                          decoratorRegistration.order()));
        beanContext.registerBeanDefinition(
                RuntimeBeanDefinition
                        .builder(definition)
                        .exposedTypes(ReflectionUtils.getAllClassesInHierarchy(
                                decoratorRegistration.getClass()).toArray(Class[]::new))
                        .annotationMetadata(annotationMetadata)
                        .singleton(true)
                        // Because every DecoratorDefinition can be the same type (for example: DefaultDecoratorDefinition)
                        // We need every Singleton BeanDefinition to be uniquely qualified, as without it decorators will override one another.
                        // Therefore, we use the Objects toString method to Name this BeanDefinition uniqely.
                        .named(definition.toString())
                        .build());
        return this;
    }

    @Override
    public boolean hasComponent(Class<?> type, @Nullable String name, SearchScope searchScope) {
        var identifier = new Component.Identifier<>(type, name);
        // Checks both the local Components as the BeanFactory,
        //  since the ConfigurationEnhancers act before component registration with the Application Context.
        return switch (searchScope) {
            case ALL -> localComponents.contains(identifier) || contextHasComponent(identifier);
            case CURRENT -> localComponents.contains(identifier);
            case ANCESTORS -> contextHasComponent(identifier);
        };
    }


    @Override
    public ComponentRegistry registerEnhancer(ConfigurationEnhancer enhancer) {
        logger.debug("Registering enhancer [{}].", enhancer.getClass().getSimpleName());
        var annotationMetadata = new MutableAnnotationMetadata();
        annotationMetadata.addDeclaredAnnotation(Order.class.getName(),
                                                 Collections.singletonMap(AnnotationMetadata.VALUE_MEMBER,
                                                                          enhancer.order()));
        var runtimeBeanDefinitionBuilder = RuntimeBeanDefinition.builder(enhancer)
                                                                .named(String.valueOf(enhancer.hashCode()))
                                                                .exposedTypes(ReflectionUtils.getAllClassesInHierarchy(
                                                                        enhancer.getClass()).toArray(Class[]::new))
                                                                .singleton(true);
        var beanDefinition = beanContext.findBeanDefinition(enhancer.getClass());
        if (beanDefinition.isPresent()) {
            logger.warn("Duplicate Configuration Enhancer registration detected. Replaced enhancer of type [{}].",
                        enhancer.getClass().getSimpleName());
            annotationMetadata.addDeclaredAnnotation(
                    Replaces.class.getName(),
                    Map.of(AnnotationMetadata.VALUE_MEMBER,
                           new AnnotationClassValue<>(beanDefinition.get().getBeanType()),
                           "named",
                           Objects.requireNonNull(Qualifiers.findName(beanDefinition.get().getDeclaredQualifier()))));
        }
        runtimeBeanDefinitionBuilder.annotationMetadata(annotationMetadata);

        beanContext.registerBeanDefinition(runtimeBeanDefinitionBuilder.build());
        return this;
    }

    @Override
    public ComponentRegistry registerModule(Module module) {
        if (logger.isDebugEnabled()) {
            logger.debug("Registering module [{}].", module.name());
        }
        Qualifier<Module> moduleQualifier = Qualifiers.byName(module.name());
        if (beanContext.findBeanDefinition(Module.class, moduleQualifier).isPresent()) {
            throw new DuplicateModuleRegistrationException(module);
        }
        beanContext.registerBeanDefinition(
                RuntimeBeanDefinition.
                        builder(module)
                        .exposedTypes(ReflectionUtils.getAllClassesInHierarchy(module.getClass()).toArray(Class[]::new))
                        .qualifier(moduleQualifier)
                        .singleton(true)
                        .build());
        return this;
    }

    @Override
    public <C> ComponentRegistry registerFactory(ComponentFactory<C> factory) {
        if (logger.isDebugEnabled()) {
            logger.debug("Registering component factory [{}].", factory.getClass().getSimpleName());
        }
        this.beanContext.registerSingleton(factory);
        return this;
    }

    @Override
    public ComponentRegistry setOverridePolicy(OverridePolicy overridePolicy) {
        if (overridePolicy != OverridePolicy.REJECT) {
            logger.warn("Enabling Component overriding on a Micronaut-based ComponentRegistry is not supported. "
                                + "Please use Micronaut \"Bean Replacement\" instead.");
        }
        return this;
    }

    @Override
    public ComponentRegistry disableEnhancerScanning() {
        logger.warn("Disabling enhancer scanning on a Micronaut-based Component-Registry is not supported"
                            + "Please set the micronaut property \"" + AxonConfigurationProperties.PREFIX
                            + ".enhancer-scanning" + "\" to false instead.");
        return this;
    }

    @Override
    public ComponentRegistry disableEnhancer(String fullyQualifiedClassName) {
        try {
            var enhancerClass = Class.forName(fullyQualifiedClassName);
            if (!ConfigurationEnhancer.class.isAssignableFrom(enhancerClass)) {
                throw new IllegalArgumentException(String.format("Class %s is not a ConfigurationEnhancer",
                                                                 fullyQualifiedClassName));
            }
            //noinspection unchecked
            return disableEnhancer((Class<? extends ConfigurationEnhancer>) enhancerClass);
        } catch (ClassNotFoundException e) {
            logger.warn(
                    "Disabling Configuration Enhancer [{}] won't take effect as the enhancer class could not be found.",
                    fullyQualifiedClassName);
        }
        return this;
    }

    @Override
    public ComponentRegistry disableEnhancer(Class<? extends ConfigurationEnhancer> enhancerClass) {
        if (invokedEnhancers.contains(enhancerClass)) {
            logger.warn("Disabling Configuration Enhancer [{}] won't take effect as it has already been invoked. "
                                + "We recommend to invoke disabling of this enhancer before it takes effect.",
                        enhancerClass.getSimpleName());
            return this;
        }
//        beanContext.registerBeanConfiguration(ConditionalBean);
        disabledEnhancers.add(enhancerClass);
        return this;
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeProperty("initialized", initialized.get());
        descriptor.describeProperty("components", localComponents);
        descriptor.describeProperty("decorators", getDecorators());
        descriptor.describeProperty("configurerEnhancers", getConfigurationEnhancerMap());
        descriptor.describeProperty("modules", getModules());
        descriptor.describeProperty("factories", getFactories());
    }


    public <T> boolean isLocalComponent(Component.Identifier<T> beanComponentId) {
        if (localComponents.contains(beanComponentId)) {
            return true;
        }
        if (beanComponentId.areTypeAndNameEqual()) {
            return localComponents.contains(new Component.Identifier<>(beanComponentId.type(), null));
        }
        return false;
    }

    /**
     * Checks if the {@link BeanContext} contains a {@link io.micronaut.inject.BeanDefinition} for this
     * {@link org.axonframework.common.configuration.Component.Identifier}
     */
    @SuppressWarnings("unchecked")
    private <C> boolean contextHasComponent(Component.Identifier<C> identifier) {
        return beanContext
                .findBeanDefinition(
                        (Argument<C>) Argument.of(identifier.type().getType()),
                        identifier.name() == null ? Qualifiers.none() : Qualifiers.byName(identifier.name())
                )
                .isPresent();
    }

    /**
     * Registers all {@link ComponentFactory} ShutdownHandlers with in the {@link LifecycleRegistry}
     */
    private void registerFactoryLifecycleHooks() {
        getFactories().forEach(factory -> factory.registerShutdownHandlers(lifecycleRegistry));
    }

    /**
     * Scans for additional {@link ConfigurationEnhancer ConfigurationEnhancers} through means of a
     * {@link SoftServiceLoader}.
     * <p>
     */
    private void scanForConfigurationEnhancers() {
        SoftServiceLoader<ConfigurationEnhancer> enhancerLoader = SoftServiceLoader.load(ConfigurationEnhancer.class,
                                                                                         getClass().getClassLoader());
        var collectedEnhancers = new ArrayList<ConfigurationEnhancer>();
        var beanEnhancers = new HashSet<>(getConfigurationEnhancers());
        enhancerLoader.collectAll(collectedEnhancers,
                                  (enhancer) -> !beanEnhancers.contains(enhancer) && !disabledEnhancers.contains(
                                          enhancer.getClass()));
        collectedEnhancers.forEach(this::registerEnhancer);
    }


    /**
     * Invoke all the {@link #registerEnhancer(ConfigurationEnhancer) registered}
     * {@link ConfigurationEnhancer enhancers} on this {@link ComponentRegistry} implementation in their
     * {@link ConfigurationEnhancer#order()}.
     * <p>
     * This will ensure all sensible default components and decorators are in place from these enhancers.
     * <p>
     * This method supports dynamic enhancer registration - if an enhancer registers another enhancer during its
     * {@link ConfigurationEnhancer#enhance(ComponentRegistry)} call, the newly registered enhancer will be processed in
     * the correct order based on its {@link ConfigurationEnhancer#order()} value relative to all unprocessed enhancers.
     * Each enhancer is processed one at a time to ensure proper ordering when new enhancers are registered
     * dynamically.
     */
    private void invokeEnhancers() {

        Set<ConfigurationEnhancer> processedEnhancerKeys = new HashSet<>();

        Collection<ConfigurationEnhancer> configurationEnhancers = getConfigurationEnhancers();
        do {
            // Find the next unprocessed enhancer with the lowest order value
            Optional<ConfigurationEnhancer> nextEnhancer = configurationEnhancers
                    .stream()
                    .filter(entry ->
                                    !processedEnhancerKeys.contains(entry))
                    .findFirst();
            if (nextEnhancer.isEmpty()) {
                break; // No more enhancers to process
            }
            ConfigurationEnhancer enhancer = nextEnhancer.get();
            if (!disabledEnhancers.contains(enhancer.getClass())) {
                enhancer.enhance(this);
                invokedEnhancers.add(enhancer.getClass());
            }
            processedEnhancerKeys.add(enhancer);

            // Refresh ConfigurationEnhancers in case new ones were added
            configurationEnhancers = getConfigurationEnhancers();
        } while (processedEnhancerKeys.size() < getConfigurationEnhancers().size());
    }

    /**
     * Decorate all components that have been {@link #registerComponent(ComponentDefinition) registered directly} or
     * registered through a {@link ConfigurationEnhancer}.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void decorateComponents() {
        for (DecoratorDefinition.CompletedDecoratorDefinition decorator : getDecorators()) {
            for (Component.Identifier id : localComponents.identifiers()) {
                if (decorator.matches(id)) {
                    localComponents.replace(id, decorator::decorate);
                }
            }
        }
    }

    /**
     * Registers all {@link Component Components} that are present in the {@link Components} collection with the
     * Micronaut's Application Context. The registration of {@code Components} should occur
     * <b>after</b> all {@link ConfigurationEnhancer ConfigurationEnhancers} have enhanced the configuration. By doing
     * so, we ensure that any defaults or overrides are present in the Application Context too.
     **/
    private void registerLocalComponentsWithApplicationContext() {
        localComponents.postProcessComponents(this::registerLocalComponent);
    }

    /**
     * Register a component in the {@link BeanContext} This creates a {@link RuntimeBeanDefinition} with the component's
     * name as it {@link Qualifiers} if it exists.
     * <p>
     * TODO: Fix limitation where registering DecoratedComponents doesn't register the newly decorated type, only the
     * original components type. See TODO in MicronautComponentRegistryTest.validateDecoratorDefinitionsAreInvoked
     */
    private <T> void registerLocalComponent(Component<T> component) {
        String name = component.identifier().name();
        Class<T> componentClass = component.identifier().type().getTypeAsClass();
        if (beanContext.findBeanDefinition(componentClass, name != null ? Qualifiers.byName(name) : Qualifiers.none())
                       .isPresent()) {
            logger.info("Component with type [{}], name [{}] is already available. Skipping registration.",
                        component.getClass().getName(),
                        name);
            return;
        }
        //noinspection unchecked
        beanContext.registerBeanDefinition(
                RuntimeBeanDefinition
                        .builder((Argument<? super T>) Argument.of(component.identifier().type().getType()),
                                 () -> component.resolve(configuration))
                        .named(name)
                        .exposedTypes(ReflectionUtils.getAllClassesInHierarchy(componentClass).toArray(Class[]::new))
                        .singleton(true)
                        .build());
        component.initLifecycle(configuration, lifecycleRegistry);
    }

    private DefaultComponentRegistry copyWithDecoratorsAndEnhancers() {
        return DefaultComponentRegistry.create(getDecorators(), getConfigurationEnhancers(), disabledEnhancers);
    }

    /**
     * Retrieve and thus Eagerly Init all {@link Module modules} and build their {@link Configuration configurations}
     */
    private void buildModuleConfigurations() {
        getModules().forEach(this::buildModuleConfig);
    }

    /**
     * Build a modules {@link Configuration} and registers it in the {@link BeanContext} with a name {@link Qualifiers}
     * where the name is the {@link Module modules} name
     */
    private void buildModuleConfig(Module module) {
        var moduleRegistry = copyWithDecoratorsAndEnhancers();
        var moduleConfig = HierarchicalLifecycleRegistry.build(
                lifecycleRegistry,
                childLifecycleRegistry -> {
                    var local = moduleRegistry.createLocalConfiguration(configuration);
                    var moduleConfiguration = module.build(local, childLifecycleRegistry);
                    return moduleRegistry.buildNested(moduleConfiguration, childLifecycleRegistry);
                });
        beanContext.registerBeanDefinition(
                RuntimeBeanDefinition
                        .builder(moduleConfig)
                        .exposedTypes(ReflectionUtils.getAllClassesInHierarchy(moduleConfig.getClass())
                                                     .toArray(Class[]::new))
                        .named(module.name())
                        .singleton(true)
                        .build()
        );
    }

    @SuppressWarnings("unchecked")
    public Collection<DecoratorDefinition.CompletedDecoratorDefinition<?, ?>> getDecorators() {
        return (Collection<DecoratorDefinition.CompletedDecoratorDefinition<?, ?>>) (Collection<?>) beanContext.getBeansOfType(
                DecoratorDefinition.class);
    }

    private Collection<Module> getModules() {
        return beanContext.getBeansOfType(Module.class);
    }

    @SuppressWarnings("rawtypes")
    private Collection<ComponentFactory> getFactories() {
        return beanContext.getBeansOfType(ComponentFactory.class);
    }

    private Collection<ConfigurationEnhancer> getConfigurationEnhancers() {
        return beanContext.getBeansOfType(ConfigurationEnhancer.class);
    }

    private Map<String, ConfigurationEnhancer> getConfigurationEnhancerMap() {
        return beanContext.mapOfType(ConfigurationEnhancer.class);
    }

    /**
     * A Micronaut integrated {@link Configuration} implementation. Resolves {@link Component}s and Module
     * {@link Configuration}s using the {@link BeanContext}
     */
    public static class MicronautConfiguration implements Configuration {

        private final BeanContext beanContext;

        /**
         * @param beanContext The {@link BeanContext} used for retrieving the {@link Component components} and
         *                    {@link Module module} {@link Configuration configurations}.
         */
        @Internal
        public MicronautConfiguration(BeanContext beanContext) {
            this.beanContext = beanContext;
        }

        @Override
        public <C> Optional<C> getOptionalComponent(Class<C> type, @Nullable String name) {
            Qualifier<C> qualifier = name == null ? Qualifiers.none() : Qualifiers.byName(name);
            try {
                return beanContext.findBean(type, qualifier);
            } catch (NonUniqueBeanException e) {
                throw new AmbiguousComponentMatchException(new Component.Identifier<>(type, name));
            }
        }

        @Override
        public <C> Optional<C> getOptionalComponent(TypeReference<C> typeReference, @Nullable String name) {
            Qualifier<C> qualifier = name == null ? Qualifiers.none() : Qualifiers.byName(name);
            try {
                //noinspection unchecked
                return beanContext.findBean((Argument<C>) Argument.of(typeReference.getType()), qualifier);
            } catch (NonUniqueBeanException e) {
                throw new AmbiguousComponentMatchException(new Component.Identifier<>(typeReference, name));
            }
        }

        @Override
        public <C> C getComponent(Class<C> type, @Nullable String name, Supplier<C> defaultImpl) {
            return getOptionalComponent(type, name).orElseGet(defaultImpl);
        }

        @Override
        public List<Configuration> getModuleConfigurations() {
            return beanContext.getBeanDefinitions(Configuration.class).stream()
                              .filter(a -> Qualifiers.findName(a.getDeclaredQualifier()) != null).map(
                            beanContext::getBean).toList();
        }

        @Override
        public Optional<Configuration> getModuleConfiguration(String name) {
            return beanContext.findBean(Configuration.class, Qualifiers.byName(name));
        }

        @Nullable
        @Override
        public Configuration getParent() {
            return null;
        }

        @Override
        public void describeTo(ComponentDescriptor descriptor) {
            descriptor.describeProperty("modules", getModuleConfigurations());
        }

        @Override
        public <C> Map<String, C> getComponents(Class<C> type) {
            var beanDefinitions = beanContext.getBeanDefinitions(type);
            Map<String, C> result = new LinkedHashMap<>(beanDefinitions.size());
            //noinspection NullableProblems
            result.putAll(beanDefinitions
                                  .stream()
                                  .collect(
                                          Collectors.toMap(
                                                  bd -> Qualifiers.findName(bd.getDeclaredQualifier()),
                                                  beanContext::getBean
                                          )
                                  ));
            for (Configuration moduleConfig : getModuleConfigurations()) {
                Map<String, C> moduleComponents = moduleConfig.getComponents(type);
                result.putAll(moduleComponents);
            }
            return Collections.unmodifiableMap(result);
        }
    }

    public record MicronautComponent<T>(Identifier<T> identifier, T bean) implements Component<T> {

        @Override
        public T resolve(Configuration configuration) {
            return bean;
        }

        @Override
        public boolean isInstantiated() {
            return true;
        }

        @Override
        public void initLifecycle(Configuration configuration, LifecycleRegistry lifecycleRegistry) {
            // Unimplemented since Micronaut manages the lifecycle of all beans.
        }

        @Override
        public boolean isInitialized() {
            return true;
        }

        @Override
        public void describeTo(ComponentDescriptor descriptor) {
            descriptor.describeProperty("identifier", identifier);
            descriptor.describeProperty("bean", bean);
        }
    }
}
