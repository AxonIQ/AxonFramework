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

package org.axonframework.messaging.eventhandling.configuration;

import org.axonframework.common.configuration.ComponentBuilder;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.annotation.ClasspathHandlerDefinition;
import org.axonframework.messaging.core.annotation.HandlerDefinition;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.annotation.AnnotatedEventHandlingComponent;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;

import java.util.Map;
import java.util.function.BiFunction;

import static java.util.Objects.requireNonNull;

/**
 * Builder interface for configuring collections of {@link EventHandlingComponent} instances.
 * <p>
 * Provides a fluent API for specifying single or multiple components and applying decorations to all components in the
 * collection.
 *
 * @author Mateusz Nowak
 * @since 5.0.0
 */
public interface EventHandlingComponentsConfigurer {

    /**
     * Initial phase for specifying event handling components. At least one component must be configured.
     */
    interface RequiredComponentPhase extends ComponentsPhase {

    }

    /**
     * Additional phase for specifying optional event handling components.
     */
    interface AdditionalComponentPhase extends ComponentsPhase, CompletePhase {

    }

    /**
     * Phase that allows configuring event handling components.
     */
    interface ComponentsPhase {

        /**
         * Configures a single event handling component with an auto-generated index-based name.
         *
         * @param handlingComponentBuilder The component to configure.
         * @return The complete phase for decoration and finalization.
         * @deprecated Use {@link #declarative(String, ComponentBuilder)} to provide an explicit component name.
         *             When no name is provided, the component receives its registration index as the name.
         *             Note that the generated index may differ across environments or application restarts,
         *             so it should not be relied upon for stable identification (e.g., in dead letter queues).
         */
        @Deprecated(forRemoval = true)
        AdditionalComponentPhase declarative(
                ComponentBuilder<EventHandlingComponent> handlingComponentBuilder
        );

        /**
         * Configures a single event handling component.
         *
         * @param componentName           The unique component name.
         * @param handlingComponentBuilder The component to configure.
         * @return The complete phase for decoration and finalization.
         */
        AdditionalComponentPhase declarative(
                String componentName,
                ComponentBuilder<EventHandlingComponent> handlingComponentBuilder
        );

        /**
         * Configures an auto-detected event handling component with an auto-generated index-based name.
         *
         * @param handlingComponentBuilder The component builder.
         * @return The additional component phase for further configuration.
         * @deprecated Use {@link #autodetected(String, ComponentBuilder)} to provide an explicit component name.
         *             When no name is provided, the component receives its registration index as the name.
         *             Note that the generated index may differ across environments or application restarts,
         *             so it should not be relied upon for stable identification (e.g., in dead letter queues).
         */
        @Deprecated(forRemoval = true)
        default AdditionalComponentPhase autodetected(ComponentBuilder<Object> handlingComponentBuilder) {
            requireNonNull(handlingComponentBuilder, "The handling component builder cannot be null.");
            return declarative(c -> new AnnotatedEventHandlingComponent<>(
                    handlingComponentBuilder.build(c),
                    c.getComponent(ParameterResolverFactory.class),
                    c.getOptionalComponent(HandlerDefinition.class)
                     .orElse(ClasspathHandlerDefinition.forClass(c.getClass())),
                    c.getComponent(MessageTypeResolver.class),
                    c.getComponent(EventConverter.class)
            ));
        }

        /**
         * Configures an auto-detected event handling component.
         *
         * @param componentName           The unique component name.
         * @param handlingComponentBuilder The component builder.
         * @return The additional component phase for further configuration.
         */
        default AdditionalComponentPhase autodetected(
                String componentName,
                ComponentBuilder<Object> handlingComponentBuilder
        ) {
            requireNonNull(componentName, "The component name cannot be null.");
            requireNonNull(handlingComponentBuilder, "The handling component builder cannot be null.");
            return declarative(componentName, c -> new AnnotatedEventHandlingComponent<>(
                    handlingComponentBuilder.build(c),
                    c.getComponent(ParameterResolverFactory.class),
                    c.getOptionalComponent(HandlerDefinition.class)
                     .orElse(ClasspathHandlerDefinition.forClass(c.getClass())),
                    c.getComponent(MessageTypeResolver.class),
                    c.getComponent(EventConverter.class)
            ));
        }
    }

    /**
     * Final phase for applying decorations and building the component list.
     */
    interface CompletePhase {

        /**
         * Applies a decorator to all components in the collection.
         *
         * @param decorator Function to decorate each component.
         * @return This phase for further decoration or finalization.
         */
        CompletePhase decorated(
                BiFunction<Configuration, EventHandlingComponent, EventHandlingComponent> decorator
        );

        /**
         * Returns the configured map of event handling components.
         *
         * @return The immutable map of configured component names to builders.
         */
        Map<String, ComponentBuilder<EventHandlingComponent>> toMap();
    }
}
