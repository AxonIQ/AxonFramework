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

package org.axonframework.messaging.core.configuration.reflection;

import org.axonframework.common.configuration.ComponentDecorator;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.SearchScope;
import org.axonframework.messaging.core.annotation.HandlerDefinition;
import org.axonframework.messaging.core.annotation.HandlerEnhancerDefinition;
import org.axonframework.messaging.core.annotation.MultiHandlerDefinition;

import java.util.function.Function;

import static java.util.Objects.requireNonNull;

/**
 * Utility class that provides methods to register a {@link HandlerDefinition} to the {@link ComponentRegistry}.
 * <p>
 * Ensures that the {@code ComponentRegistry} at all times has <b>one</b> {@code HandlerDefinition} component.
 * Subsequent invocations of
 * {@link #registerToComponentRegistry(ComponentRegistry, Function)}/{@link
 * #registerToComponentRegistry(ComponentRegistry, int, Function)} will
 * {@link ComponentRegistry#registerDecorator(Class, String, int, ComponentDecorator) decorate} the existing
 * {@code HandlerDefinition} and given {@code HandlerDefinition} into a {@link MultiHandlerDefinition}.
 * <p>
 * Only a {@link MultiHandlerDefinition} applies a {@link HandlerEnhancerDefinition} to created handlers, so every
 * {@code HandlerDefinition} registered or decorated through this class is (re)assembled using the
 * {@link SearchScope#CURRENT current} {@code ComponentRegistry}'s composed {@code HandlerEnhancerDefinition} component
 * (see {@link HandlerEnhancerDefinitionUtils}). This guarantees the {@code HandlerDefinition} component always reflects
 * every configured enhancer, regardless of how many separate calls contributed to either component or in what order.
 *
 * @author Steven van Beelen
 * @since 5.3.2
 */
public class HandlerDefinitionUtils {

    /**
     * Register a {@link HandlerDefinition} to the {@link SearchScope#CURRENT current} {@link ComponentRegistry} using
     * the given {@code handlerDefinitionBuilder} function.
     * <p>
     * It will be registered with order {@code 0}. To register with a different order, use
     * {@link #registerToComponentRegistry(ComponentRegistry, int, Function)} instead.
     *
     * @param componentRegistry        the {@link ComponentRegistry} to register the {@link HandlerDefinition} to
     * @param handlerDefinitionBuilder the {@link Function} that creates the {@link HandlerDefinition} based on the
     *                                 {@link Configuration}
     */
    public static void registerToComponentRegistry(
            ComponentRegistry componentRegistry,
            Function<Configuration, HandlerDefinition> handlerDefinitionBuilder
    ) {
        requireNonNull(componentRegistry, "The ComponentRegistry must not be null.");
        registerToComponentRegistry(componentRegistry, 0, handlerDefinitionBuilder);
    }

    /**
     * Register a {@link HandlerDefinition} to the {@link SearchScope#CURRENT current} {@link ComponentRegistry} using
     * the given {@code handlerDefinitionBuilder} function, placing the handlerDefinitionBuilder at the given
     * {@code order}.
     *
     * @param componentRegistry        the {@link ComponentRegistry} to register the {@link HandlerDefinition} to
     * @param order                    the order in which the {@link HandlerDefinition} should be registered
     * @param handlerDefinitionBuilder the {@link Function} that creates the {@link HandlerDefinition} based on the
     *                                 {@link Configuration}
     */
    public static void registerToComponentRegistry(
            ComponentRegistry componentRegistry,
            int order,
            Function<Configuration, HandlerDefinition> handlerDefinitionBuilder
    ) {
        requireNonNull(componentRegistry, "The ComponentRegistry must not be null.");
        requireNonNull(handlerDefinitionBuilder, "The HandlerDefinition builder must not be null.");

        if (!componentRegistry.hasComponent(HandlerDefinition.class, SearchScope.CURRENT)) {
            componentRegistry.registerComponent(
                    HandlerDefinition.class,
                    config -> MultiHandlerDefinition.ordered(
                            config.getComponent(HandlerEnhancerDefinition.class), handlerDefinitionBuilder.apply(config)
                    )
            );
            return;
        }

        componentRegistry.registerDecorator(
                HandlerDefinition.class,
                order,
                (config, componentName, component) -> MultiHandlerDefinition.ordered(
                        config.getComponent(HandlerEnhancerDefinition.class),
                        component,
                        handlerDefinitionBuilder.apply(config)
                )
        );
    }

    private HandlerDefinitionUtils() {
        // Utility class
    }
}
