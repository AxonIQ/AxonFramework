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

import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.configuration.ComponentBuilder;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.interception.InterceptingEventHandlingComponent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;
import static org.axonframework.common.BuilderUtils.assertNonBlank;

/**
 * Default implementation of {@link EventHandlingComponentsConfigurer} providing {@link EventHandlingComponent}s`
 * builders management with decoration support.
 *
 * @author Mateusz Nowak
 * @since 5.0.0
 */
@Internal
public class DefaultEventHandlingComponentsConfigurer
        implements EventHandlingComponentsConfigurer.RequiredComponentPhase,
        EventHandlingComponentsConfigurer.AdditionalComponentPhase {

    private Map<String, ComponentBuilder<EventHandlingComponent>> componentBuilders = new LinkedHashMap<>();
    private int componentIndex = 0;
    private final List<ComponentBuilder<MessageHandlerInterceptor<? super EventMessage>>> interceptors
            = new ArrayList<>();

    /**
     * Creates a new empty configurer instance.
     */
    public DefaultEventHandlingComponentsConfigurer() {
        // Default constructor for empty configuration
    }

    @Override
    @Deprecated(forRemoval = true)
    public EventHandlingComponentsConfigurer.AdditionalComponentPhase declarative(
            ComponentBuilder<EventHandlingComponent> handlingComponentBuilder
    ) {
        requireNonNull(handlingComponentBuilder, "The handling component builder cannot be null.");
        String generatedName = String.valueOf(componentIndex++);
        componentBuilders.put(generatedName, handlingComponentBuilder);
        return this;
    }

    @Override
    public EventHandlingComponentsConfigurer.AdditionalComponentPhase declarative(
            String componentName,
            ComponentBuilder<EventHandlingComponent> handlingComponentBuilder
    ) {
        assertNonBlank(componentName, "The component name cannot be null or blank.");
        requireNonNull(handlingComponentBuilder, "The handling component builder cannot be null.");
        componentIndex++;
        var existingBuilder = componentBuilders.putIfAbsent(componentName, handlingComponentBuilder);
        if (existingBuilder != null) {
            throw new AxonConfigurationException("Event handling component name [%s] is already registered."
                                                         .formatted(componentName));
        }
        return this;
    }

    @Override
    public EventHandlingComponentsConfigurer.CompletePhase intercepted(
            ComponentBuilder<MessageHandlerInterceptor<? super EventMessage>> interceptorBuilder
    ) {
        requireNonNull(interceptorBuilder, "interceptorBuilder must not be null");
        if (interceptors.isEmpty()) {
            // empty, so we're registering the decorator for the first interceptor; subsequent calls only add to the list
            decorated((cfg, component) -> new InterceptingEventHandlingComponent(
                    interceptors.stream()
                                .map(builder -> builder.build(cfg))
                                .collect(Collectors.toList()),
                    component));
        }
        interceptors.add(interceptorBuilder);
        return this;
    }

    @Override
    public EventHandlingComponentsConfigurer.CompletePhase decorated(
            BiFunction<Configuration, EventHandlingComponent, EventHandlingComponent> decorator
    ) {
        Objects.requireNonNull(decorator, "decorator may not be null");
        var decoratedBuilders = new LinkedHashMap<String, ComponentBuilder<EventHandlingComponent>>();
        for (var componentBuilder : componentBuilders.entrySet()) {
            decoratedBuilders.put(componentBuilder.getKey(), cfg -> {
                var builder = componentBuilder.getValue();
                EventHandlingComponent component = builder.build(cfg);
                return decorator.apply(cfg, component);
            });
        }
        componentBuilders = decoratedBuilders;
        return this;
    }

    @Override
    public Map<String, ComponentBuilder<EventHandlingComponent>> toMap() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(componentBuilders));
    }
}
