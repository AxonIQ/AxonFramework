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

import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.StringUtils;
import org.axonframework.common.annotation.AnnotationUtils;
import org.axonframework.common.annotation.Internal;
import org.axonframework.extension.spring.BeanDefinitionUtils;
import org.axonframework.messaging.core.annotation.Namespace;
import org.axonframework.messaging.eventhandling.configuration.EventHandlingComponentsConfigurer;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorConfiguration;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorModule;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorConfiguration;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * Default implementation of {@link ProcessorModuleFactory} that assigns event handlers to processors using
 * {@link EventProcessorDefinition ProcessorDefinitions}.
 * <p>
 * This factory evaluates each event handler against the configured processor definitions. When a handler matches a
 * definition's selector, it is assigned to that processor. If no processor definition matches, the handler is assigned
 * to a processor named after the handler's package (or "default" if the package cannot be determined).
 * <p>
 * The factory uses {@link EventProcessorSettings} to configure each processor according to its type (pooled streaming
 * or subscribing). Custom configuration from processor definitions is applied on top of the base settings.
 *
 * @author Allard Buijze
 * @see ProcessorModuleFactory
 * @see EventProcessorDefinition
 * @since 5.0.2
 */
@Internal
public class DefaultProcessorModuleFactory implements ProcessorModuleFactory {

    private static final Logger logger = LoggerFactory.getLogger(DefaultProcessorModuleFactory.class);

    private final List<EventProcessorDefinition> eventProcessorDefinitions;
    private final Map<String, EventProcessorSettings> allSettings;
    private final List<PooledStreamingEventProcessorModule.Customization> extensionsCustomizations;

    /**
     * Creates a new factory with the given processor definitions and settings.
     *
     * @param eventProcessorDefinitions The list of processor definitions that define handler assignment rules.
     * @param settings                  The map of processor settings, keyed by processor name.
     */
    public DefaultProcessorModuleFactory(List<EventProcessorDefinition> eventProcessorDefinitions,
                                         Map<String, EventProcessorSettings> settings) {
        this(eventProcessorDefinitions, settings, Collections.emptyList());
    }

    /**
     * Creates a new factory with the given processor definitions, settings, and additional customizations.
     *
     * @param eventProcessorDefinitions The list of processor definitions that define handler assignment rules.
     * @param settings                  The map of processor settings, keyed by processor name.
     * @param extensionsCustomizations  Additional {@link PooledStreamingEventProcessorModule.Customization} beans
     *                                  (e.g., DLQ configuration) to apply to each processor during module creation.
     */
    public DefaultProcessorModuleFactory(List<EventProcessorDefinition> eventProcessorDefinitions,
                                         Map<String, EventProcessorSettings> settings,
                                         List<PooledStreamingEventProcessorModule.Customization> extensionsCustomizations) {
        this.eventProcessorDefinitions = eventProcessorDefinitions;
        this.allSettings = settings;
        this.extensionsCustomizations = extensionsCustomizations;
    }

    /**
     * {@inheritDoc}
     * <p>
     * This implementation groups handlers by their assigned processor name (determined by
     * {@link #assignedProcessor(EventProcessorDefinition.EventHandlerDescriptor)}), then creates an
     * {@link EventProcessorModule} for each processor with its assigned handlers.
     */
    @Override
    public Set<EventProcessorModule> buildProcessorModules(Set<EventProcessorDefinition.EventHandlerDescriptor> handlers) {

        Set<EventProcessorModule> modules = new LinkedHashSet<>();

        var assignments = handlers.stream().collect(Collectors.groupingBy(this::assignedProcessor));

        for (EventProcessorDefinition definition : eventProcessorDefinitions) {
            if (!assignments.containsKey(definition.name())) {
                logger.warn("No handlers assigned to explicitly defined processor: {}", definition.name());
            }
        }

        assignments.forEach((processorName, beanDefs) -> {
            Function<EventHandlingComponentsConfigurer.RequiredComponentPhase, EventHandlingComponentsConfigurer.CompletePhase> componentRegistration = (EventHandlingComponentsConfigurer.RequiredComponentPhase phase) -> {
                EventHandlingComponentsConfigurer.ComponentsPhase resultOfRegistration = phase;
                for (EventProcessorDefinition.EventHandlerDescriptor namedBeanDefinition : beanDefs) {
                    resultOfRegistration = resultOfRegistration.autodetected(
                            namedBeanDefinition.beanName(),
                            namedBeanDefinition.component()
                    );
                }
                return (EventHandlingComponentsConfigurer.CompletePhase) resultOfRegistration;
            };

            var settings = Optional.ofNullable(allSettings.get(processorName))
                                   .orElseGet(() -> allSettings.get(EventProcessorSettings.DEFAULT));
            var processorMode = definitionFor(processorName).map(EventProcessorDefinition::mode)
                                                            .orElse(settings.processorMode());
            EventProcessorModule module = switch (processorMode) {
                case POOLED -> {
                    var moduleSettings = (EventProcessorSettings.PooledEventProcessorSettings) settings;
                    var baseCustomization = SpringCustomizations.pooledStreamingCustomizations(
                            processorName, moduleSettings
                    );
                    UnaryOperator<PooledStreamingEventProcessorConfiguration> definitionCustomization =
                            customizeConfiguration(processorName);
                    PooledStreamingEventProcessorModule.Customization customization =
                            (axonConfig, processorConfig) -> {
                                var result = baseCustomization.apply(axonConfig, processorConfig);
                                result = definitionCustomization.apply(result);
                                for (var extension : extensionsCustomizations) {
                                    result = extension.apply(axonConfig, result);
                                }
                                return result;
                            };
                    yield EventProcessorModule
                            .pooledStreaming(processorName)
                            .eventHandlingComponents(componentRegistration)
                            .customized(customization)
                            .build();
                }
                case SUBSCRIBING -> {
                    var moduleSettings = (EventProcessorSettings.SubscribingEventProcessorSettings) settings;
                    yield EventProcessorModule
                            .subscribing(processorName)
                            .eventHandlingComponents(componentRegistration)
                            .customized(SpringCustomizations.subscribingCustomizations(processorName, moduleSettings)
                                                            .andThen(customizeConfiguration(processorName)))
                            .build();
                }
            };

            modules.add(module);
        });
        return modules;
    }

    /**
     * Returns a configuration customizer function for a subscribing processor with the given name.
     * <p>
     * If a processor definition exists for this processor, its configuration is applied. Otherwise, the identity
     * function is returned (no customization).
     *
     * @param processorName The name of the processor.
     * @return A function that customizes the subscribing processor configuration.
     */
    @SuppressWarnings({"unchecked"})
    private <T extends EventProcessorConfiguration> UnaryOperator<T> customizeConfiguration(String processorName) {
        for (EventProcessorDefinition eventProcessorDefinition : eventProcessorDefinitions) {
            if (eventProcessorDefinition.name().equals(processorName)) {
                return c -> (T) eventProcessorDefinition.applySettings(c);
            }
        }
        return UnaryOperator.identity();
    }

    /**
     * Resolves the name of the processor the given event handler should be assigned to.
     * <p>
     * This method evaluates the handler against all processor definitions. If exactly one definition matches, the
     * handler is assigned to that processor. If no definitions match, the handler is assigned to a processor named
     * after its {@link Namespace} annotation value, or after its package if no namespace is present. If multiple
     * definitions match, an {@link AxonConfigurationException} is thrown.
     * <p>
     * The resolution order is:
     * <ol>
     *     <li>Explicit {@link EventProcessorDefinition} selector match</li>
     *     <li>{@link Namespace} annotation on the handler's type, enclosing classes, package, or module</li>
     *     <li>Package name derived from the bean definition</li>
     * </ol>
     *
     * @param handler The event handler descriptor.
     * @return The name of the processor this handler should be assigned to.
     * @throws AxonConfigurationException If multiple processor definitions match the handler.
     */
    private String assignedProcessor(EventProcessorDefinition.EventHandlerDescriptor handler) {
        Set<String> matches = new HashSet<>();
        for (EventProcessorDefinition eventProcessorDefinition : eventProcessorDefinitions) {
            if (eventProcessorDefinition.matchesSelector(handler)) {
                matches.add(eventProcessorDefinition.name());
            }
        }
        if (matches.isEmpty()) {
            // First, check if the handler type has a @Namespace annotation
            return resolveNamespace(handler)
                    // Fall back to the package name derived from the bean definition
                    .orElseGet(() -> BeanDefinitionUtils.extractPackageName(handler.beanDefinition()));
        }
        if (matches.size() == 1) {
            return matches.iterator().next();
        }
        throw new AxonConfigurationException(
                "Handler [" + handler.beanName() + " (of type " + handler.beanDefinition().getBeanClassName()
                        + ")] matched with multiple processors selectors: " + matches);
    }

    /**
     * Resolves the {@link Namespace} value from the handler's bean type.
     * <p>
     * The {@code Namespace} annotation is searched for on several levels in the following order:
     * <ol>
     *     <li>On the bean type itself</li>
     *     <li>The enclosing classes (from innermost to outermost)</li>
     *     <li>The package (via {@code package-info.java})</li>
     *     <li>The module</li>
     * </ol>
     *
     * @param handler The event handler descriptor.
     * @return An Optional containing the namespace value if found, or empty if not found.
     */
    private Optional<String> resolveNamespace(EventProcessorDefinition.EventHandlerDescriptor handler) {
        Class<?> type = handler.beanType();
        if (type == null) {
            return Optional.empty();
        }
        return AnnotationUtils.findAnnotationAttributesOnType(
                                      type,
                                      Namespace.class,
                                      attrs -> !StringUtils.emptyOrNull((String) attrs.get("namespace"))
                              )
                              .map(attrs -> (String) attrs.get("namespace"));
    }

    /**
     * Finds the processor definition for the given processor name.
     *
     * @param name The processor name.
     * @return An Optional containing the processor definition if found, or empty if not found.
     */
    private Optional<EventProcessorDefinition> definitionFor(String name) {
        for (EventProcessorDefinition eventProcessorDefinition : eventProcessorDefinitions) {
            if (eventProcessorDefinition.name().equals(name)) {
                return Optional.of(eventProcessorDefinition);
            }
        }
        return Optional.empty();
    }
}
