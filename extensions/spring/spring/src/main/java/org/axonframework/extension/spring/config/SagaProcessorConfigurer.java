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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Processor settings and {@link EventProcessorDefinition}s apply to a Saga's processor as they apply to any other,
 * because this class reads the same beans the regular pipeline reads.
 * {@link PooledStreamingEventProcessorModule.Customization} beans are the exception: they are deliberately not
 * applied to a Saga's processor. That channel is how cross-cutting Axon Framework 5 infrastructure attaches itself
 * to every processor in an application -- dead-lettering, for one, is enabled by a Customization bean that puts a
 * dead letter queue extension on the processor configuration -- and Axon Framework 4 never dead-lettered a Saga: it
 * never wrapped a Saga manager in its dead-lettering invoker. Silently acquiring behaviour the original never had is
 * the opposite of what a migration aid should do, so a Saga processor opts out of the channel as a whole rather than
 * guessing which customization means well.
 * <p>
 * Declare a {@link SagaProcessorDefinition} bean instead. It is the same capability scoped to Sagas, so no Axon
 * Framework 5 extension can arrive through it, and it neither assigns handlers nor fixes the processor's mode the
 * way an {@link EventProcessorDefinition} does. An {@link EventProcessorDefinition} naming the processor still
 * applies as well, for an application that wants to configure a Saga's processor the same way it configures every
 * other one.
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

    private static final Logger logger = LoggerFactory.getLogger(SagaProcessorConfigurer.class);

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
        List<SagaProcessorDefinition> sagaDefinitions = context.getBeanProvider(SagaProcessorDefinition.class)
                                                               .orderedStream()
                                                               .toList();

        sagasByProcessor(discovered.values(), definitions).forEach(
                (processorName, sagas) -> registry.registerModule(
                        module(processorName, sagas, definitions, allSettings, sagaDefinitions)
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
            List<SagaProcessorDefinition> sagaDefinitions
    ) {
        EventProcessorSettings settings = Optional.ofNullable(allSettings.get(processorName))
                                                  .orElseGet(() -> allSettings.get(EventProcessorSettings.DEFAULT));
        Optional<EventProcessorDefinition> definition = definitionFor(processorName, definitions);
        List<Class<?>> sagaTypes = sagas.stream().<Class<?>>map(SpringSagaConfigurer::beanType).toList();
        List<SagaProcessorDefinition> matching = sagaDefinitions.stream()
                                                                .filter(d -> d.matches(processorName, sagaTypes))
                                                                .toList();
        warnAboutSagaSelectorsOnASharedProcessor(processorName, sagaTypes, matching);
        warnAboutModeMismatches(processorName, matching, definition, settings);
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
                UnaryOperator<PooledStreamingEventProcessorConfiguration> headToken = headTokenDefault();
                UnaryOperator<PooledStreamingEventProcessorConfiguration> definitionCustomization =
                        customizeConfiguration(definition);
                PooledStreamingEventProcessorModule.Customization customization =
                        (axonConfig, processorConfig) -> {
                            var result = headToken.apply(processorConfig);
                            result = baseCustomization.apply(axonConfig, result);
                            result = singleSegmentDefault(result);
                            // Deliberately no PooledStreamingEventProcessorModule.Customization beans here; see the
                            // class javadoc. SagaProcessorDefinition is the Saga-scoped replacement.
                            result = definitionCustomization.apply(result);
                            for (SagaProcessorDefinition sagaDefinition : matching) {
                                var sagaCustomization = sagaDefinition.pooledCustomization();
                                if (sagaCustomization != null) {
                                    result = sagaCustomization.apply(result);
                                }
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
                UnaryOperator<SubscribingEventProcessorConfiguration> sagaCustomizations = configuration -> {
                    var result = configuration;
                    for (SagaProcessorDefinition sagaDefinition : matching) {
                        var sagaCustomization = sagaDefinition.subscribingCustomization();
                        if (sagaCustomization != null) {
                            result = sagaCustomization.apply(result);
                        }
                    }
                    return result;
                };
                yield EventProcessorModule
                        .subscribing(processorName)
                        .eventHandlingComponents(componentRegistration)
                        .customized(SpringCustomizations.subscribingCustomizations(processorName, moduleSettings)
                                                        .andThen(definitionCustomization)
                                                        .andThen(sagaCustomizations))
                        .build();
            }
        };
    }

    /**
     * Starts a Saga's processor at the head of the stream, so that it ignores events published before it first ran.
     * <p>
     * This holds whatever else configures the processor, which is what Axon Framework 4 effectively did. There, a
     * customized Saga processor fell back to the generic tracking processor default of
     * {@code createReplayToken(createHeadToken())}: it read from the start of the stream, but every event up to the
     * head was flagged as a replay, and a Saga manager reports {@code supportsReset() == false}, so those events
     * were never delivered to it. The Axon Framework 5 generic default instead resolves to
     * {@code createReplayToken(firstToken)}, whose replay window is empty, so without this default an existing
     * stream reaches the Saga as ordinary events and re-executes its side effects.
     * <p>
     * This is only a default. It is applied before processor settings, an {@link EventProcessorDefinition}'s own
     * customization, and {@link PooledStreamingEventProcessorModule.Customization} beans, so any of those may
     * override the initial token and replay into a Saga deliberately.
     */
    /**
     * Warns when a {@link SagaProcessorDefinition} selected a processor by Saga type and that processor turns out to
     * carry other Sagas as well. The customization applies to all of them, which is easy to miss when the code names
     * one Saga; {@link SagaProcessorDefinition#forProcessor(String)} states the intent instead.
     */
    private void warnAboutSagaSelectorsOnASharedProcessor(String processorName,
                                                          List<Class<?>> sagaTypes,
                                                          List<SagaProcessorDefinition> matching) {
        if (sagaTypes.size() < 2) {
            return;
        }
        matching.stream()
                .map(SagaProcessorDefinition::sagaType)
                .filter(Objects::nonNull)
                .forEach(selectedBy -> logger.warn(
                        "A SagaProcessorDefinition selected the processor of Saga [{}], but processor [{}] also "
                                + "carries {}. The customization applies to the whole processor, so it affects those "
                                + "Sagas too. Use SagaProcessorDefinition.forProcessor(\"{}\") to say so explicitly.",
                        selectedBy.getName(),
                        processorName,
                        sagaTypes.stream().filter(type -> !type.equals(selectedBy)).map(Class::getName).toList(),
                        processorName
                ));
    }

    /**
     * Warns when a {@link SagaProcessorDefinition} was written for the mode the processor it selected does not run
     * in, so that its customization is never silently skipped.
     */
    private void warnAboutModeMismatches(String processorName,
                                         List<SagaProcessorDefinition> matching,
                                         Optional<EventProcessorDefinition> definition,
                                         EventProcessorSettings settings) {
        var mode = definition.map(EventProcessorDefinition::mode).orElse(settings.processorMode());
        boolean pooled = mode == EventProcessorSettings.ProcessorMode.POOLED;
        for (SagaProcessorDefinition sagaDefinition : matching) {
            boolean writtenForPooled = sagaDefinition.pooledCustomization() != null;
            if (writtenForPooled != pooled) {
                logger.warn("Skipping the SagaProcessorDefinition for {}: it customizes a {} processor "
                                    + "configuration, and processor [{}] runs in {} mode.",
                            sagaDefinition.describeSelector(),
                            writtenForPooled ? "pooled streaming" : "subscribing",
                            processorName,
                            pooled ? "pooled streaming" : "subscribing");
            }
        }
    }

    private UnaryOperator<PooledStreamingEventProcessorConfiguration> headTokenDefault() {
        return configuration -> configuration.initialToken(source -> source.latestToken(null));
    }

    /**
     * Runs a Saga's processor on a single segment, as Axon Framework 4 did: its Saga processor defaults derive from
     * {@code TrackingEventProcessorConfiguration.forSingleThreadedProcessing()}, and the tracking processor was the
     * Spring Boot default there.
     * <p>
     * A Saga manager reports {@link org.axonframework.messaging.core.sequencing.SequencingPolicy#BROADCAST}
     * as its sequence identifier, so every segment reads every event and then keeps only the Sagas whose identifier
     * the segment owns. Extra segments therefore multiply the reads of the whole event stream and the token store
     * rows without splitting the stream, which is why one is the right default rather than the sixteen an ordinary
     * Axon Framework 5 processor starts with.
     * <p>
     * Applied after the processor settings, so {@code initial-segment-count} does not raise it: those properties
     * always carry a value and cannot express "unset", so honouring them would silently give every Saga sixteen
     * segments as soon as an unrelated property such as {@code batch-size} was set. Raise it deliberately through an
     * {@link EventProcessorDefinition} or a {@link PooledStreamingEventProcessorModule.Customization} bean, both of
     * which run afterwards.
     */
    private PooledStreamingEventProcessorConfiguration singleSegmentDefault(
            PooledStreamingEventProcessorConfiguration configuration
    ) {
        return configuration.initialSegmentCount(1);
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
