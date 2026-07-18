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

package org.axonframework.common.configuration;

import org.jspecify.annotations.Nullable;
import org.axonframework.common.infra.DescribableComponent;
import org.axonframework.common.configuration.Component.Identifier;

/**
 * The starting point when configuring any Axon Framework application.
 * <p>
 * Provides utilities to {@link #registerComponent(Class, ComponentBuilder) register components},
 * {@link #registerDecorator(Class, int, ComponentDecorator) decorators} of these components, check if a component
 * {@link #hasComponent(Class) exists}, register {@link #registerEnhancer(ConfigurationEnhancer) enhancers} for the
 * entire configurer, register {@link #registerModule(Module) modules}, and register
 * {@link #registerFactory(ComponentFactory) component factories}.
 *
 * @author Allard Buijze
 * @author Steven van Beelen
 * @since 5.0.0
 */
public interface ComponentRegistry extends DescribableComponent {

    /**
     * Registers a {@link Component} that should be made available to other {@link Component components} or
     * {@link Module modules} in the {@link Configuration} that this {@code Configurer} will result in.
     * <p>
     * The given {@code builder} function gets the {@link Configuration configuration} as input, and is expected to
     * provide the component as output. The component will be registered under an {@link Identifier} based on the given
     * {@code type}.
     *
     * @param type    The declared type of the component to build, typically an interface.
     * @param builder The builder building the component.
     * @param <C>     The type of component the {@code builder} builds.
     * @return The current instance of the {@code Configurer} for a fluent API.
     * @throws ComponentOverrideException If the override policy is set to
     *                                    {@link OverridePolicy#REJECT} and a component
     *                                    with the same type is already defined.
     */
    default <C> ComponentRegistry registerComponent(Class<C> type,
                                                    ComponentBuilder<? extends C> builder) {
        return registerComponent(ComponentDefinition.ofType(type)
                                                    .withBuilder(builder));
    }

    /**
     * Registers a {@link Component} that should be made available to other {@link Component components} or
     * {@link Module modules} in the {@link Configuration} that this {@code Configurer} will result in.
     * <p>
     * The given {@code builder} function gets the {@link Configuration configuration} as input, and is expected to
     * provide the component as output. The component will be registered under an {@link Identifier} based on the given
     * {@code type} and {@code name} combination.
     *
     * @param type    The declared type of the component to build, typically an interface.
     * @param name    The name of the component to build. Use {@code null} when there is no name or use
     *                {@link #registerComponent(Class, ComponentBuilder)} instead.
     * @param builder The builder building the component.
     * @param <C>     The type of component the {@code builder} builds.
     * @return The current instance of the {@code Configurer} for a fluent API.
     * @throws ComponentOverrideException If the override policy is set to
     *                                    {@link OverridePolicy#REJECT} and a component
     *                                    with the same type and name is already defined.
     */
    default <C> ComponentRegistry registerComponent(Class<C> type,
                                                    @Nullable String name,
                                                    ComponentBuilder<? extends C> builder) {
        return registerComponent(ComponentDefinition.ofTypeAndName(type, name)
                                                    .withBuilder(builder));
    }

    /**
     * Registers a {@link Component} based on the given {@code definition}.
     *
     * @param definition The definition of the component to register.
     * @param <C>        The declared type of the component.
     * @return The current instance of the {@code Configurer} for a fluent API.
     * @throws ComponentOverrideException If the override policy is set to
     *                                    {@link OverridePolicy#REJECT} and a component
     *                                    with the same type and name is already defined.
     */
    <C> ComponentRegistry registerComponent(ComponentDefinition<? extends C> definition);

    /**
     * Registers a {@link Component} {@link ComponentDecorator decorator} that will act on <b>all</b>
     * {@link #registerComponent(Class, ComponentBuilder) registered} components of the given {@code type}, regardless
     * of component name.
     * <p>
     * Decorators are invoked based on the given {@code order}. Decorators with a lower {@code order} will be executed
     * before those with a higher one. If decorators depend on the result of another decorator, their {@code order} must
     * be strictly higher than the one they depend on.
     * <p>
     * The order in which components are decorated by decorators with the same {@code order} is undefined.
     *
     * @param type      The declared type of the component to decorate, typically an interface.
     * @param order     The order of the given {@code decorator} among other decorators.
     * @param decorator The decoration function for a component of type {@code C}.
     * @param <C>       The type of component the {@code decorator} decorates.
     * @param <D>       The type of component the {@code decorator} returns.
     * @return The current instance of the {@code Configurer} for a fluent API.
     */
    default <C, D extends C> ComponentRegistry registerDecorator(Class<C> type,
                                                                 int order,
                                                                 ComponentDecorator<C, D> decorator) {
        return registerDecorator(DecoratorDefinition.forType(type)
                                                    .with(decorator)
                                                    .order(order));
    }

    /**
     * Registers a {@link ComponentDecorator decorator} that will act on
     * {@link #registerComponent(Class, String, ComponentBuilder) registered} components of the given {@code type}
     * <b>and</b> {@code name} combination.
     * <p>
     * Decorators are invoked based on the given {@code order}. Decorators with a lowe {@code order} will be executed
     * before those with a higher one. If decorators depend on the result of another decorator, their {@code order} must
     * be strictly higher than the one they depend on.
     * <p>
     * The order in which components are decorated by decorators with the same {@code order} is undefined.
     *
     * @param type      The declared type of the component to decorate, typically an interface.
     * @param name      The name of the component to decorate.
     * @param order     The order of the given {@code decorator} among other decorators.
     * @param decorator The decoration function for a component of type {@code C}.
     * @param <C>       The type of component the {@code decorator} decorates.
     * @param <D>       The type of component the {@code decorator} returns.
     * @return The current instance of the {@code Configurer} for a fluent API.
     */
    default <C, D extends C> ComponentRegistry registerDecorator(Class<C> type,
                                                                 String name,
                                                                 int order,
                                                                 ComponentDecorator<C, D> decorator) {
        return registerDecorator(DecoratorDefinition.forTypeAndName(type, name)
                                                    .with(decorator)
                                                    .order(order));
    }

    /**
     * Registers a decorator based on the given {@code definition}.
     *
     * @param definition The definition of the decorator to apply to components.
     * @param <C>        The declared type of the component(s) to decorate.
     * @return The current instance of the {@code Configurer} for a fluent API.
     * @see DecoratorDefinition
     */
    <C> ComponentRegistry registerDecorator(DecoratorDefinition<C, ? extends C> definition);

    /**
     * Check whether there is a {@link Component} registered with this {@code Configurer} for the given {@code type}.
     *
     * @param type The type of the {@link Component} to check if it exists, typically an interface.
     * @return {@code true} when there is a {@link Component} registered under the given {@code type}, {@code false}
     * otherwise.
     */
    default boolean hasComponent(Class<?> type) {
        return hasComponent(type, (String) null);
    }

    /**
     * Check whether there is a {@link Component} registered with this {@code Configurer} for the given {@code type}.
     * <p>
     * The given {@code searchScope} is used to define if the search only checks the {@link SearchScope#CURRENT current}
     * registry, only checks all {@link SearchScope#ANCESTORS ancestors}, or checks {@link SearchScope#ALL both} the
     * current registry and all ancestors.
     *
     * @param type        The type of the {@link Component} to check if it exists, typically an interface.
     * @param searchScope The enumeration defining the search scope used to check if this registry has a
     *                    {@link Component}.
     * @return {@code true} when there is a {@link Component} registered under the given {@code type}, {@code false}
     * otherwise.
     */
    default boolean hasComponent(Class<?> type, SearchScope searchScope) {
        return hasComponent(type, null, searchScope);
    }

    /**
     * Check whether there is a {@link Component} registered with this {@code Configurer} for the given {@code type} and
     * {@code name} combination.
     *
     * @param type The type of the {@link Component} to check if it exists, typically an interface.
     * @param name The name of the {@link Component} to check if it exists. Use {@code null} when there is no name or
     *             use {@link #hasComponent(Class)} instead.
     * @return {@code true} when there is a {@link Component} registered under the given {@code type} and
     * {@code name combination}, {@code false} otherwise.
     */
    default boolean hasComponent(Class<?> type,
                                 @Nullable String name) {
        return hasComponent(type, name, SearchScope.ALL);
    }

    /**
     * Check whether there is a {@link Component} registered with this {@code Configurer} for the given {@code type} and
     * {@code name} combination.
     * <p>
     * The given {@code searchScope} is used to define if the search only checks the {@link SearchScope#CURRENT current}
     * registry, only checks all {@link SearchScope#ANCESTORS ancestors}, or checks {@link SearchScope#ALL both} the
     * current registry and all ancestors.
     *
     * @param type        The type of the {@link Component} to check if it exists, typically an interface.
     * @param name        The name of the {@link Component} to check if it exists. Use {@code null} when there is no
     *                    name or use {@link #hasComponent(Class)} instead.
     * @param searchScope The enumeration defining the search scope used to check if this registry has a
     *                    {@link Component}.
     * @return {@code true} when there is a {@link Component} registered under the given {@code type} and
     * {@code name combination}, {@code false} otherwise.
     */
    boolean hasComponent(Class<?> type,
                         @Nullable String name,
                         SearchScope searchScope);

    /**
     * Registers a {@link Component} only <b>if</b> there is none yet for the given {@code type}.
     * <p>
     * The given {@code builder} function gets the {@link Configuration configuration} as input, and is expected to
     * provide the component as output. The component will be registered under an {@link Identifier} based on the given
     * {@code type}.
     *
     * @param type    The declared type of the component to build, typically an interface.
     * @param builder The builder building the component.
     * @param <C>     The type of component the {@code builder} builds.
     * @return The current instance of the {@code Configurer} for a fluent API.
     */
    default <C> ComponentRegistry registerIfNotPresent(Class<C> type,
                                                       ComponentBuilder<? extends C> builder) {
        return registerIfNotPresent(type, null, builder);
    }


    /**
     * Registers a {@link Component} only <b>if</b> there is none yet for the given {@code type}.
     * <p>
     * The given {@code builder} function gets the {@link Configuration configuration} as input, and is expected to
     * provide the component as output. The component will be registered under an {@link Identifier} based on the given
     * {@code type}.
     * <p>
     * The given {@code searchScope} is used to define if the search only checks the {@link SearchScope#CURRENT current}
     * registry, only checks all {@link SearchScope#ANCESTORS ancestors}, or checks {@link SearchScope#ALL both} the
     * current registry and all ancestors.
     *
     * @param type        The declared type of the component to build, typically an interface.
     * @param builder     The builder building the component.
     * @param searchScope The enumeration defining the search scope used to check if this registry has a
     *                    {@link Component}.
     * @param <C>         The type of component the {@code builder} builds.
     * @return The current instance of the {@code Configurer} for a fluent API.
     */
    default <C> ComponentRegistry registerIfNotPresent(Class<C> type,
                                                       ComponentBuilder<? extends C> builder,
                                                       SearchScope searchScope) {
        return registerIfNotPresent(type, null, builder, searchScope);
    }

    /**
     * Registers a {@link Component} only <b>if</b> there is none yet for the given {@code type} and {@code name}
     * combination.
     * <p>
     * The given {@code builder} function gets the {@link Configuration configuration} as input, and is expected to
     * provide the component as output. The component will be registered under an {@link Identifier} based on the given
     * {@code type}.
     *
     * @param type    The declared type of the component to build (typically an interface) <b>if</b> it has not been
     *                registered yet.
     * @param name    The name of the component to build <b>if</b> it has not been registered yet.
     * @param builder The builder building the component.
     * @param <C>     The type of component the {@code builder} builds.
     * @return The current instance of the {@code Configurer} for a fluent API.
     */
    default <C> ComponentRegistry registerIfNotPresent(Class<C> type,
                                                       @Nullable String name,
                                                       ComponentBuilder<? extends C> builder) {
        return registerIfNotPresent(ComponentDefinition.ofTypeAndName(type, name).withBuilder(builder));
    }


    /**
     * Registers a {@link Component} only <b>if</b> there is none yet for the given {@code type} and {@code name}
     * combination.
     * <p>
     * The given {@code builder} function gets the {@link Configuration configuration} as input, and is expected to
     * provide the component as output. The component will be registered under an {@link Identifier} based on the given
     * {@code type}.
     * <p>
     * The given {@code searchScope} is used to define if the search only checks the {@link SearchScope#CURRENT current}
     * registry, only checks all {@link SearchScope#ANCESTORS ancestors}, or checks {@link SearchScope#ALL both} the
     * current registry and all ancestors.
     *
     * @param type        The declared type of the component to build (typically an interface) <b>if</b> it has not been
     *                    registered yet.
     * @param name        The name of the component to build <b>if</b> it has not been registered yet.
     * @param builder     The builder building the component.
     * @param searchScope The enumeration defining the search scope used to check if this registry has a
     *                    {@link Component}.
     * @param <C>         The type of component the {@code builder} builds.
     * @return The current instance of the {@code Configurer} for a fluent API.
     */
    default <C> ComponentRegistry registerIfNotPresent(Class<C> type,
                                                       @Nullable String name,
                                                       ComponentBuilder<? extends C> builder,
                                                       SearchScope searchScope) {
        return registerIfNotPresent(ComponentDefinition.ofTypeAndName(type, name).withBuilder(builder), searchScope);
    }

    /**
     * Registers a {@link Component} based on the given {@code definition} only <b>if</b> there is none yet for the
     * definition's {@link ComponentDefinition#rawType() raw type} and {@link ComponentDefinition#name() name}
     * combination.
     *
     * @param definition The definition of the component to register.
     * @param <C>        The declared type of the component.
     * @return The current instance of the {@code Configurer} for a fluent API.
     */
    default <C> ComponentRegistry registerIfNotPresent(ComponentDefinition<C> definition) {
        return definition.name() == null
                ? hasComponent(definition.rawType()) ? this : registerComponent(definition)
                : hasComponent(definition.rawType(), definition.name()) ? this : registerComponent(definition);
    }


    /**
     * Registers a {@link Component} based on the given {@code definition} only <b>if</b> there is none yet for the
     * definition's {@link ComponentDefinition#rawType() raw type} and {@link ComponentDefinition#name() name}
     * combination.
     * <p>
     * The given {@code searchScope} is used to define if the search only checks the {@link SearchScope#CURRENT current}
     * registry, only checks all {@link SearchScope#ANCESTORS ancestors}, or checks {@link SearchScope#ALL both} the
     * current registry and all ancestors.
     *
     * @param definition  The definition of the component to register.
     * @param searchScope The enumeration defining the search scope used to check if this registry has a
     *                    {@link Component}.
     * @param <C>         The declared type of the component.
     * @return The current instance of the {@code Configurer} for a fluent API.
     */
    default <C> ComponentRegistry registerIfNotPresent(ComponentDefinition<C> definition,
                                                       SearchScope searchScope) {
        return definition.name() == null
                ? hasComponent(definition.rawType(), searchScope) ? this : registerComponent(definition)
                : hasComponent(definition.rawType(), definition.name(), searchScope) ? this : registerComponent(
                definition);
    }

    /**
     * Registers an {@link ConfigurationEnhancer} with this {@code ComponentRegistry}.
     * <p>
     * An {@code enhancer} is able to invoke <em>any</em> of the methods on this {@code ComponentRegistry}, allowing it
     * to add (sensible) defaults, decorate {@link Component components}, or replace components entirely.
     * <p>
     * An enhancer's {@link ConfigurationEnhancer#enhance(ComponentRegistry)} method is invoked during the
     * initialization phase when all components have been defined. This is right before the {@code ComponentRegistry}
     * creates its {@link Configuration}.
     * <p>
     * When multiple enhancers have been provided, their {@link ConfigurationEnhancer#order()} dictates the enhancement
     * order. For enhancer with the same order, the order of execution is undefined.
     *
     * @param enhancer The configuration enhancer to enhance ComponentRegistry during
     *                 {@link ApplicationConfigurer#build()}.
     * @return The current instance of the {@code Configurer} for a fluent API.
     */
    ComponentRegistry registerEnhancer(ConfigurationEnhancer enhancer);

    /**
     * Registers a {@link Module} with this registry.
     * <p>
     * Note that a {@code Module} is able to access the components defined in this {@code ComponentRegistry} upon
     * construction, but not vice versa. As such, the {@code Module} maintains encapsulation.
     *
     * @param module The module builder function to register.
     * @return The current instance of the {@code ComponentRegistry} for a fluent API.
     * @throws ComponentOverrideException If a module with the same name already exists.
     */
    ComponentRegistry registerModule(Module module);

    /**
     * Registers a {@link ComponentFactory} with this registry.
     * <p>
     * If the {@link Configuration} that will contain this registry <b>does not</b> have a component for a given
     * {@code Class} and name combination, it will consult all registered component factories. Only if a given
     * {@code factory} can produce the {@link ComponentFactory#forType() requested type} will
     * {@link ComponentFactory#construct(String, Configuration)} be invoked. When the {@code factory} decides to
     * construct a new component, it will be stored in the {@code Configuration} for future reference to ensure it's not
     * constructed again.
     *
     * @param factory The component factory to register.
     * @param <C>     The component type constructed by the given {@code factory}.
     * @return The current instance of the {@code ComponentRegistry} for a fluent API.
     */
    <C> ComponentRegistry registerFactory(ComponentFactory<C> factory);

    /**
     * Sets the {@link OverridePolicy} for this {@code ComponentRegistry}.
     * <p>
     * This policy dictates what should happen when components are registered with an identifier for which another
     * component is already present.
     *
     * @param overridePolicy The override policy for components defined in this registry.
     * @return The current instance of the {@code Configurer} for a fluent API.
     */
    ComponentRegistry setOverridePolicy(OverridePolicy overridePolicy);

    /**
     * Completely disables scanning for enhancers on the classpath through the {@link java.util.ServiceLoader}
     * mechanism. Note that this may lead to missing framework functionality. It is recommended to disable specific
     * enhancers through {@link #disableEnhancer(Class)} instead. Does not affect enhancers that are registered through
     * the {@link #registerEnhancer(ConfigurationEnhancer)} method.
     *
     * @return The current instance of the {@code Configurer} for a fluent API.
     */
    ComponentRegistry disableEnhancerScanning();

    /**
     * Disables the given {@link ConfigurationEnhancer} class from executing during the configuration initialization
     * phase. This affects both enhancers registered through the {@link java.util.ServiceLoader} mechanism and those
     * registered programmatically via {@link #registerEnhancer(ConfigurationEnhancer)}.
     * <p>
     * Only specific classes can be disabled, and class hierarchies are not taken into account. If the enhancer has
     * already been invoked when this method is called, disabling will have no effect and a warning will be logged.
     * <p>
     * This method is typically called from within another enhancer's
     * {@link ConfigurationEnhancer#enhance(ComponentRegistry)} method to prevent subsequent enhancers from executing.
     *
     * @param enhancerClass The class of the enhancer to disable.
     * @return The current instance of the {@code Configurer} for a fluent API.
     */
    ComponentRegistry disableEnhancer(Class<? extends ConfigurationEnhancer> enhancerClass);

    /**
     * Disables the given {@link ConfigurationEnhancer} class from executing during the configuration initialization
     * phase. This affects both enhancers registered through the {@link java.util.ServiceLoader} mechanism and those
     * registered programmatically via {@link #registerEnhancer(ConfigurationEnhancer)}.
     * <p>
     * Only specific classes can be disabled, and class hierarchies are not taken into account. If the enhancer has
     * already been invoked when this method is called, disabling will have no effect and a warning will be logged.
     * <p>
     * This method is typically called from within another enhancer's
     * {@link ConfigurationEnhancer#enhance(ComponentRegistry)} method to prevent subsequent enhancers from executing.
     * <p>
     * If the class cannot be found on the classpath, a warning will be logged and the call will have no effect.
     *
     * @param fullyQualifiedClassName The fully qualified class name of the enhancer to disable.
     * @return The current instance of the {@code Configurer} for a fluent API.
     */
    ComponentRegistry disableEnhancer(String fullyQualifiedClassName);
}
