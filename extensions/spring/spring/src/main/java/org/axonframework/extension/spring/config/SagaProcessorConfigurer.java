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
import org.axonframework.common.annotation.RegistrationScope;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.messaging.core.annotation.Namespace;
import org.axonframework.messaging.eventhandling.configuration.EventHandlingComponentsConfigurer;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorModule;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorConfiguration;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorModule;
import org.axonframework.messaging.eventhandling.processing.subscribing.SubscribingEventProcessorConfiguration;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Assembles the event processors carrying the Axon Framework 4 Sagas discovered in a Spring application context.
 * <p>
 * Sagas are assembled here rather than through {@link DefaultProcessorModuleFactory}, deliberately. Everything Saga
 * support needs -- registering an already assembled Saga manager instead of inspecting a bean for annotated handler
 * methods, deriving a {@code <SagaName>Processor} name, and starting a Saga's processor at the head of the stream --
 * would otherwise have to be threaded as Saga-shaped hooks through the processor factory and the handler discovery
 * that every Axon Framework 5 Spring application relies on. Saga support is a migration aid with a limited life, so
 * it pays for its own assembly instead: this class duplicates a modest amount of {@code DefaultProcessorModuleFactory},
 * and both the duplicate and the rest of Saga support are deleted together with {@code axon-legacy}.
 * <p>
 * The one behavioural consequence is that a Saga and an ordinary event handler can no longer share a processor, as
 * they could under a single {@code @ProcessingGroup} in Axon Framework 4. Both pipelines register a module named after
 * the processor, so a collision surfaces at startup as a
 * {@link org.axonframework.common.configuration.DuplicateModuleRegistrationException} rather than as a silent merge.
 * Several Sagas resolving to the same name do still share one processor.
 * <p>
 * Processor settings, {@link EventProcessorDefinition}s and
 * {@link PooledStreamingEventProcessorModule.Customization} beans apply to a Saga's processor exactly as they apply to
 * any other, because this class reads the same beans the regular pipeline reads.
 * <p>
 * Registered as a bean by the Saga auto configuration; an application never creates this itself.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
@Internal
@RegistrationScope("Copying this enhancer into a module's own registry would make it build a fresh set of Saga "
        + "processor modules for every module it already built, without ever terminating.")
public class SagaProcessorConfigurer implements ConfigurationEnhancer, ApplicationContextAware {

    private @Nullable ApplicationContext applicationContext;

    @Override
    public void enhance(ComponentRegistry registry) {
        ApplicationContext context = requireApplicationContext();
        Map<String, SpringSagaConfigurer> discovered = context.getBeansOfType(SpringSagaConfigurer.class);
        if (discovered.isEmpty()) {
            return;
        }

        List<EventProcessorDefinition> definitions = context.getBeanProvider(EventProcessorDefinition.class)
                                                            .orderedStream()
                                                            .toList();
        Map<String, EventProcessorSettings> allSettings =
                context.getBean(EventProcessorSettings.MapWrapper.class).settings();
        List<PooledStreamingEventProcessorModule.Customization> extensionsCustomizations =
                context.getBeanProvider(PooledStreamingEventProcessorModule.Customization.class)
                       .orderedStream()
                       .toList();

        sagasByProcessor(discovered.values(), definitions).forEach(
                (processorName, sagas) -> registry.registerModule(
                        module(processorName, sagas, definitions, allSettings, extensionsCustomizations)
                )
        );
    }

    /**
     * Groups the discovered Sagas by the processor they are assigned to, collapsing repeated registrations of one
     * Saga type so that declaring the same Saga twice yields a single component.
     */
    private Map<String, List<SpringSagaConfigurer>> sagasByProcessor(
            Iterable<SpringSagaConfigurer> discovered,
            List<EventProcessorDefinition> definitions
    ) {
        Map<String, SpringSagaConfigurer> uniqueSagas = new LinkedHashMap<>();
        for (SpringSagaConfigurer saga : discovered) {
            uniqueSagas.put(saga.deduplicationKey(), saga);
        }
        Map<String, List<SpringSagaConfigurer>> byProcessor = new LinkedHashMap<>();
        for (SpringSagaConfigurer saga : uniqueSagas.values()) {
            byProcessor.computeIfAbsent(assignedProcessor(saga, definitions), name -> new ArrayList<>()).add(saga);
        }
        return byProcessor;
    }

    /**
     * Resolves the processor a Saga belongs to, in the same order
     * {@link DefaultProcessorModuleFactory} resolves it for an ordinary handler, except that a Saga falls back to a
     * name derived from its type rather than to its package name.
     * <ol>
     *     <li>Explicit {@link EventProcessorDefinition} selector match</li>
     *     <li>{@link Namespace} annotation on the Saga type, enclosing classes, package, or module</li>
     *     <li>{@code <SagaSimpleName>Processor}</li>
     * </ol>
     */
    private String assignedProcessor(SpringSagaConfigurer saga, List<EventProcessorDefinition> definitions) {
        Set<String> matches = new HashSet<>();
        for (EventProcessorDefinition definition : definitions) {
            if (definition.matchesSelector(saga)) {
                matches.add(definition.name());
            }
        }
        if (matches.size() > 1) {
            throw new AxonConfigurationException(
                    "Saga [" + saga.beanName() + " (of type " + saga.beanType().getName()
                            + ")] matched with multiple processors selectors: " + matches);
        }
        if (matches.size() == 1) {
            return matches.iterator().next();
        }
        return resolveNamespace(saga).orElseGet(saga::derivedProcessorName);
    }

    private Optional<String> resolveNamespace(SpringSagaConfigurer saga) {
        return AnnotationUtils.findAnnotationAttributesOnType(
                                      saga.beanType(),
                                      Namespace.class,
                                      attrs -> !StringUtils.emptyOrNull((String) attrs.get("namespace"))
                              )
                              .map(attrs -> (String) attrs.get("namespace"));
    }

    private EventProcessorModule module(
            String processorName,
            List<SpringSagaConfigurer> sagas,
            List<EventProcessorDefinition> definitions,
            Map<String, EventProcessorSettings> allSettings,
            List<PooledStreamingEventProcessorModule.Customization> extensionsCustomizations
    ) {
        EventProcessorSettings settings = Optional.ofNullable(allSettings.get(processorName))
                                                  .orElseGet(() -> allSettings.get(EventProcessorSettings.DEFAULT));
        Optional<EventProcessorDefinition> definition = definitionFor(processorName, definitions);
        Function<EventHandlingComponentsConfigurer.RequiredComponentPhase, EventHandlingComponentsConfigurer.CompletePhase>
                componentRegistration = phase -> {
            EventHandlingComponentsConfigurer.ComponentsPhase result = phase;
            for (SpringSagaConfigurer saga : sagas) {
                // Declarative, not autodetected: a Saga manager is an EventHandlingComponent already, and wrapping it
                // in an AnnotatedEventHandlingComponent would look for @EventHandler methods a Saga does not have.
                result = result.declarative(saga.beanName(), saga.eventHandlingComponent());
            }
            return (EventHandlingComponentsConfigurer.CompletePhase) result;
        };

        var processorMode = definition.map(EventProcessorDefinition::mode).orElse(settings.processorMode());
        return switch (processorMode) {
            case POOLED -> {
                var moduleSettings = (EventProcessorSettings.PooledEventProcessorSettings) settings;
                var baseCustomization = SpringCustomizations.pooledStreamingCustomizations(
                        processorName, moduleSettings
                );
                UnaryOperator<PooledStreamingEventProcessorConfiguration> headToken =
                        headTokenDefault(processorName, allSettings, definition);
                UnaryOperator<PooledStreamingEventProcessorConfiguration> definitionCustomization =
                        customizeConfiguration(definition);
                PooledStreamingEventProcessorModule.Customization customization =
                        (axonConfig, processorConfig) -> {
                            var result = headToken.apply(processorConfig);
                            result = baseCustomization.apply(axonConfig, result);
                            result = definitionCustomization.apply(result);
                            for (var extension : extensionsCustomizations) {
                                result = extension.apply(axonConfig, result);
                            }
                            SpringCustomizations.requireResolvedTokenStore(processorName, result);
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
                UnaryOperator<SubscribingEventProcessorConfiguration> definitionCustomization =
                        customizeConfiguration(definition);
                yield EventProcessorModule
                        .subscribing(processorName)
                        .eventHandlingComponents(componentRegistration)
                        .customized(SpringCustomizations.subscribingCustomizations(processorName, moduleSettings)
                                                        .andThen(definitionCustomization))
                        .build();
            }
        };
    }

    /**
     * Starts a Saga's processor at the head of the stream, so that it ignores events published before it first ran,
     * as the Axon Framework 4 tracking processor default for Sagas did.
     * <p>
     * Ported with the Axon Framework 4 back-off intact: any explicit processor entry or named
     * {@link EventProcessorDefinition} drops this default entirely, leaving the generic Axon Framework 5 default of
     * replaying the stream from its first event.
     */
    private UnaryOperator<PooledStreamingEventProcessorConfiguration> headTokenDefault(
            String processorName,
            Map<String, EventProcessorSettings> allSettings,
            Optional<EventProcessorDefinition> definition
    ) {
        if (allSettings.containsKey(processorName) || definition.isPresent()) {
            return UnaryOperator.identity();
        }
        return configuration -> configuration.initialToken(source -> source.latestToken(null));
    }

    @SuppressWarnings("unchecked")
    private <T extends org.axonframework.messaging.eventhandling.configuration.EventProcessorConfiguration>
    UnaryOperator<T> customizeConfiguration(Optional<EventProcessorDefinition> definition) {
        return definition.<UnaryOperator<T>>map(d -> c -> (T) d.applySettings(c))
                         .orElseGet(UnaryOperator::identity);
    }

    private Optional<EventProcessorDefinition> definitionFor(String name, List<EventProcessorDefinition> definitions) {
        for (EventProcessorDefinition definition : definitions) {
            if (definition.name().equals(name)) {
                return Optional.of(definition);
            }
        }
        return Optional.empty();
    }

    private ApplicationContext requireApplicationContext() {
        return Objects.requireNonNull(applicationContext,
                                      "The ApplicationContext must be set before configuring Saga processors.");
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }
}
