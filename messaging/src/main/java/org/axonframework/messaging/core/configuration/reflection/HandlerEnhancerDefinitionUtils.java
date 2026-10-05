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
import org.axonframework.messaging.core.annotation.HandlerEnhancerDefinition;
import org.axonframework.messaging.core.annotation.MultiHandlerEnhancerDefinition;

import java.util.function.Function;

import static java.util.Objects.requireNonNull;

/**
 * Utility class that provides methods to register a {@link HandlerEnhancerDefinition} to the
 * {@link ComponentRegistry}.
 * <p>
 * Ensures that the {@code ComponentRegistry} at all times has <b>one</b> {@code HandlerEnhancerDefinition} component.
 * Subsequent invocations of
 * {@link #registerToComponentRegistry(ComponentRegistry, Function)}/{@link
 * #registerToComponentRegistry(ComponentRegistry, int, Function)} will
 * {@link ComponentRegistry#registerDecorator(Class, String, int, ComponentDecorator) decorate} the existing
 * {@code HandlerEnhancerDefinition} and given {@code HandlerEnhancerDefinition} into a
 * {@link MultiHandlerEnhancerDefinition}.
 *
 * @author Steven van Beelen
 * @since 5.3.2
 */
public class HandlerEnhancerDefinitionUtils {

    /**
     * Register a {@link HandlerEnhancerDefinition} to the {@link SearchScope#CURRENT current} {@link ComponentRegistry}
     * using the given {@code handlerEnhancerDefinitionBuilder} function.
     * <p>
     * It will be registered with order {@code 0}.
     *
     * @param componentRegistry                the {@link ComponentRegistry} to register the
     *                                         {@link HandlerEnhancerDefinition} to.
     * @param handlerEnhancerDefinitionBuilder the {@link Function} that creates the {@link HandlerEnhancerDefinition}
     *                                         based on the {@link Configuration}
     */
    public static void registerToComponentRegistry(
            ComponentRegistry componentRegistry,
            Function<Configuration, HandlerEnhancerDefinition> handlerEnhancerDefinitionBuilder
    ) {
        requireNonNull(componentRegistry, "The ComponentRegistry must not be null.");

        registerToComponentRegistry(componentRegistry, 0, handlerEnhancerDefinitionBuilder);
    }

    /**
     * Register a {@link HandlerEnhancerDefinition} to the {@link SearchScope#CURRENT current} {@link ComponentRegistry}
     * using the given {@code handlerEnhancerDefinitionBuilder} function.
     *
     * @param componentRegistry                the {@link ComponentRegistry} to register the
     *                                         {@link HandlerEnhancerDefinition} to
     * @param order                            the order in which the {@link HandlerEnhancerDefinition} should be
     *                                         registered
     * @param handlerEnhancerDefinitionBuilder the {@link Function} that creates the {@link HandlerEnhancerDefinition}
     *                                         based on the {@link Configuration}
     */
    public static void registerToComponentRegistry(
            ComponentRegistry componentRegistry,
            int order,
            Function<Configuration, HandlerEnhancerDefinition> handlerEnhancerDefinitionBuilder
    ) {
        requireNonNull(componentRegistry, "The ComponentRegistry must not be null.");
        requireNonNull(handlerEnhancerDefinitionBuilder, "The HandlerEnhancerDefinition builder must not be null.");

        if (!componentRegistry.hasComponent(HandlerEnhancerDefinition.class, SearchScope.CURRENT)) {
            componentRegistry.registerComponent(
                    HandlerEnhancerDefinition.class, handlerEnhancerDefinitionBuilder::apply
            );
            return;
        }

        componentRegistry.registerDecorator(
                HandlerEnhancerDefinition.class,
                order,
                (config, componentName, component) -> MultiHandlerEnhancerDefinition.ordered(
                        component, handlerEnhancerDefinitionBuilder.apply(config)
                )
        );
    }

    private HandlerEnhancerDefinitionUtils() {
        // Utility class
    }
}
