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

import org.axonframework.common.Assert;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

/**
 * Defines the structure of a decorator for {@link Component components} in the {@link Configuration} of the
 * application or one of its {@link Module Modules}.
 * <p>
 * Decorators can wrap or replace the implementation of defined components based on their type and optionally their
 * name. Decorators must return an instance that is an implementation of the declared type. Typically, they wrap another
 * component to provide additional behavior.
 * <p>
 * To create a decorator for a component of type {@code MyComponentInterface} with an implementation that has a
 * dependency, it would look as follows:
 * <pre><code>
 * DecoratorDefinition.forType(MyComponentInterface.class)
 *                    .with((config, name, delegate) -> new MyComponentWrapper(delegate, config.getComponent(MyDependency.class)))
 *                    .onStart(0, MyComponentWrapper::start)
 *                    .onShutdown(0, MyComponentWrapper::shutdown)
 * </code></pre>
 * <p>
 * Alternatively, you can use:
 * <pre><code>
 *     DecoratorDefinition.forTypeAndName(MyComponentInterface.class, "MyName")
 *                        .with(config -> ...)
 * </code></pre>
 * In this case, you need to only wrap a component with a given name.
 *
 * @param <C> The declared type of the component.
 * @param <D> The instance type of the decorated component.
 * @author Allard Buijze
 * @since 5.0.0
 */
public sealed interface DecoratorDefinition<C, D extends C> permits DecoratorDefinition.CompletedDecoratorDefinition {

    /**
     * Initiates the configuration of a decorator for {@link Component components} with the given {@code type}, which
     * must correspond with the declared type of these components.
     * <p>
     * If multiple components have been defined for this type, they all are subject to decoration under this definition.
     * To decorate only a specific component, consider using {@link #forTypeAndName(Class, String)}.
     *
     * @param type The class of the declared type of the components to decorate.
     * @param <C>  The declared type of the components to decorate.
     * @return A builder for further configuration of this decorator definition.
     */
    static <C> PartialDecoratorDefinition<C> forType(Class<C> type) {
        requireNonNull(type, "The type must not be null.");
        return new PartialDecoratorDefinition<>() {
            @Override
            public <D extends C> DecoratorDefinition<C, D> with(ComponentDecorator<C, D> decorator) {
                return new DefaultDecoratorDefinition<>(id -> type.isAssignableFrom(id.typeAsClass()), decorator);
            }
        };
    }

    /**
     * Initiates the configuration of a decorator for a {@link Component component} with the given {@code type} and
     * {@code name}, which must correspond with the declared type of these components.
     * <p>
     * The decorator is only invoked if such component with such name is defined in the component registry where this
     * decorator is registered. If multiple components for this type have been defined, and all are subject to
     * decoration, consider using {@link #forType(Class)}.
     *
     * @param type The class of the declared type of the components to decorate.
     * @param name The name of the component to decorate.
     * @param <C>  The declared type of the components to decorate.
     * @return A builder for further configuration of this decorator definition.
     */
    static <C> PartialDecoratorDefinition<C> forTypeAndName(Class<C> type, String name) {
        requireNonNull(type, "The type must not be null.");
        Assert.nonEmpty(name, "The name must not be empty or null.");
        return new PartialDecoratorDefinition<>() {
            @Override
            public <D extends C> DecoratorDefinition<C, D> with(ComponentDecorator<C, D> decorator) {
                return new DefaultDecoratorDefinition<>(
                        id -> type.isAssignableFrom(id.typeAsClass()) && Objects.equals(name, id.name()),
                        decorator
                );
            }
        };
    }

    /**
     * Sets the {@code order} in which this decorator will be invoked on a component relative to other decorators.
     * <p>
     * We refer to the {@link DecoratorOrder} for commonly used order values, providing information to decide the
     * {@code order} for the given {@code decorator} on. The relative order of decorators with the same {@code order} is
     * undefined.
     * <p>
     * In absence of configuration, the order is {@code 0}.
     *
     * @param order The relative order in which to invoke this decorator.
     * @return This {@code DecoratorDefinition} fur fluent API.
     */
    DecoratorDefinition<C, D> order(int order);

    /**
     * Registers the given {@code handler} to be registered with the application's lifecycle during startup for this
     * decorator.
     * <p>
     * The handler will be invoked in the given startup {@code phase} for each component that has been decorated.
     *
     * @param phase   The phase in which the handler must be invoked.
     * @param handler The handler to invoke.
     * @return This {@code DecoratorDefinition} fur fluent API.
     */
    DecoratorDefinition<C, D> onStart(int phase, ComponentLifecycleHandler<D> handler);

    /**
     * Registers the given {@code handler} to be registered with the application's lifecycle during startup for this
     * decorator.
     * <p>
     * The handler will be invoked in the given startup {@code phase} for each component that has been decorated.
     *
     * @param phase   The phase in which the start handler must be invoked.
     * @param handler The handler to invoke.
     * @return This {@code DecoratorDefinition} fur fluent API.
     */
    default DecoratorDefinition<C, D> onStart(int phase, Consumer<D> handler) {
        return onStart(phase, (config, component) -> {
            Objects.requireNonNull(handler, "The start handler cannot be null.")
                   .accept(component);
            return CompletableFuture.completedFuture(null);
        });
    }

    /**
     * Registers the given {@code handler} to be registered with the application's lifecycle during shutdown for this
     * decorator.
     * <p>
     * The handler will be invoked in the given shutdown {@code phase} for each component that has been decorated.
     *
     * @param phase   The phase in which the shutdown handler must be invoked.
     * @param handler The handler to invoke.
     * @return This {@code DecoratorDefinition} fur fluent API.
     */
    DecoratorDefinition<C, D> onShutdown(int phase, ComponentLifecycleHandler<D> handler);

    /**
     * Registers the given {@code handler} to be registered with the application's lifecycle during shutdown for this
     * decorator.
     * <p>
     * The handler will be invoked in the given shutdown {@code phase} for each component that has been decorated.
     *
     * @param phase   The phase in which the shutdown handler must be invoked.
     * @param handler The handler to invoke.
     * @return This {@code DecoratorDefinition} fur fluent API.
     */
    default DecoratorDefinition<C, D> onShutdown(int phase, Consumer<D> handler) {
        return onShutdown(phase, (config, component) -> {
            Objects.requireNonNull(handler, "The shutdown handler cannot be null.")
                   .accept(component);
            return CompletableFuture.completedFuture(null);
        });
    }

    /**
     * Represents an intermediate phase in the creation of a {@link DecoratorDefinition}.
     *
     * @param <C> The declared type of the component to decorate.
     */
    interface PartialDecoratorDefinition<C> {

        /**
         * Defines the decorator function to use to decorate target components.
         *
         * @param decorator The function to use to decorate a component.
         * @param <D>       The instance type of the decorated component.
         * @return A {@code DecoratorDefinition} for direct use or further configuration.
         */
        <D extends C> DecoratorDefinition<C, D> with(ComponentDecorator<C, D> decorator);
    }

    /**
     * Defines the behavior that all implementation of {@link DecoratorDefinition} must provide.
     * <p>
     * This separation is used to hide these methods from general use, as they are solely meant for Axon internals.
     *
     * @param <C> The declared type of the component.
     * @param <D> The implementation type of the decorator.
     */
    non-sealed interface CompletedDecoratorDefinition<C, D extends C> extends DecoratorDefinition<C, D> {

        /**
         * The order in which this decorator must be invoked.
         * <p>
         * The relative order of decorators with the same {@code order} is undefined.
         *
         * @return The order in which this decorator must be invoked.
         */
        int order();

        /**
         * Decorates the given {@code delegate} by returning the instance that should be used in its place.
         * <p>
         * Generally, this is a component that delegates calls to the {@code delegate}, although decorators may choose
         * to return a replacement altogether.
         *
         * @param delegate The component to decorate.
         * @return The decorated component.
         */
        Component<C> decorate(Component<C> delegate);

        /**
         * Indicates whether the component with given {@code id} matches the definition of this decorator.
         *
         * @param id The identifier of a component to possibly decorate.
         * @return {@code true} if the component's identifier matches this definition, otherwise {@code false}.
         */
        boolean matches(Component.Identifier<?> id);
    }
}