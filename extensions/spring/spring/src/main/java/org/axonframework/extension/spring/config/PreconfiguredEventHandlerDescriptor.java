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

import org.axonframework.common.configuration.ComponentBuilder;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorConfiguration;

import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Internal extension of an event handler descriptor for a component that already implements event handling semantics.
 * Such components are registered declaratively instead of being inspected for annotated handler methods.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
interface PreconfiguredEventHandlerDescriptor extends EventProcessorDefinition.EventHandlerDescriptor {

    /**
     * Returns the event handling component to register.
     *
     * @return the event handling component builder
     */
    ComponentBuilder<EventHandlingComponent> eventHandlingComponent();

    @Override
    default ComponentBuilder<Object> component() {
        return eventHandlingComponent()::build;
    }

    /**
     * Returns the processor name to use when neither a selector nor a namespace assigns the component.
     *
     * @return the preferred processor name, if any
     */
    default Optional<String> preferredProcessorName() {
        return Optional.empty();
    }

    /**
     * Returns defaults to apply before processor properties and definitions.
     *
     * @return the pooled streaming processor defaults
     */
    default UnaryOperator<PooledStreamingEventProcessorConfiguration> pooledStreamingDefaults() {
        return UnaryOperator.identity();
    }

    /**
     * Returns the key used to collapse repeated registrations of the same logical component.
     *
     * @return the deduplication key
     */
    default String deduplicationKey() {
        return beanName();
    }
}
