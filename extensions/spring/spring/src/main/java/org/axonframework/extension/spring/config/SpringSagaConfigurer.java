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

import org.axonframework.common.StringUtils;
import org.axonframework.common.annotation.AnnotationUtils;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.annotation.RegistrationScope;
import org.axonframework.common.configuration.ComponentBuilder;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.messaging.core.annotation.Namespace;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.configuration.EventHandlingComponentsConfigurer;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorModule;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorModule;
import org.axonframework.modelling.saga.configuration.Sagas;
import org.axonframework.modelling.saga.repository.SagaStore;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * A {@link ConfigurationEnhancer} registering the event processor a Saga type is handled by.
 * <p>
 * One instance is registered per {@link org.axonframework.spring.stereotype.Saga @Saga} bean by
 * {@link SpringSagaLookup}, under the bean name {@code "<sagaBeanName>$$Registrar"}. The processor name is taken from
 * the {@link Namespace} annotation on the Saga type, falling back to {@code "<SimpleName>Processor"} -- the name Axon
 * Framework 4 derived. Every Saga deriving the same processor name is co-located on a single processor, as Axon
 * Framework 4 co-located Sagas sharing a processing group. To achieve that from an enhancer registered per Saga, each
 * instance looks up its siblings in the {@link ApplicationContext} and only the first member of a name group registers
 * the module; the others do nothing.
 * <p>
 * The resulting module is a pooled streaming processor by default, configured from the
 * {@code axon.eventhandling.processors.*} settings exactly like any other processor, so {@code mode=subscribing}
 * switches a Saga to a subscribing processor. A pooled Saga processor additionally starts at the head of the stream
 * (the {@link org.axonframework.messaging.eventstreaming.TrackingTokenSource#latestToken latest token}) instead of
 * replaying it, reproducing the Axon Framework 4 default that new Sagas ignore history.
 * The head token survives {@code axon.eventhandling.processors.<name>} entries: those properties cannot express an
 * initial token, so an entry that only tunes the processor carries no intent to replay. This deliberately deviates
 * from Axon Framework 4, where any customization of a Saga's processor name replaced the Saga defaults with the
 * generic read-from-the-start default, whose historic events arrived flagged as replay and were handled by Sagas
 * unless annotated {@code @DisallowReplay}. Replaying into a Saga requires code: a
 * {@link PooledStreamingEventProcessorModule.Customization} bean overriding the initial token, applied after this
 * configurer's base customization.
 * <p>
 * This class is internal wiring: it is instantiated by {@code SpringSagaLookup} as a bean definition and never
 * referenced from application code, so its shape may change with the Saga support it serves.
 *
 * @author Allard Buijze
 * @author Mateusz Nowak
 * @since 5.4.0
 */
@Internal
@RegistrationScope("Don't copy this enhancer in order to avoid cyclic module build in Spring Boot.")
public class SpringSagaConfigurer implements ConfigurationEnhancer, ApplicationContextAware {

    private final Class<?> sagaType;

    private @Nullable String sagaStore;
    private @Nullable ApplicationContext applicationContext;

    /**
     * Initializes a {@code SpringSagaConfigurer} for the given {@code sagaType}.
     *
     * @param sagaType the type of Saga to register an event processor for
     */
    public SpringSagaConfigurer(Class<?> sagaType) {
        this.sagaType = Objects.requireNonNull(sagaType, "The sagaType must not be null.");
    }

    /**
     * Sets the name of the {@link SagaStore} bean the Sagas of this type are kept in.
     * <p>
     * When left unset, the Sagas are stored in the {@code SagaStore} component of the Axon
     * {@link org.axonframework.common.configuration.Configuration Configuration}.
     *
     * @param sagaStore the name of the {@link SagaStore} bean the Sagas of this type are kept in
     */
    public void setSagaStore(String sagaStore) {
        this.sagaStore = sagaStore;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    /**
     * The name of the event processor the Saga of this configurer is handled by.
     *
     * @return the name of the event processor the Saga of this configurer is handled by
     */
    String processorName() {
        return AnnotationUtils.findAnnotationAttributesOnType(
                                      sagaType,
                                      Namespace.class,
                                      attrs -> !StringUtils.emptyOrNull((String) attrs.get("namespace"))
                              )
                              .map(attrs -> (String) attrs.get("namespace"))
                              .orElseGet(() -> sagaType.getSimpleName() + "Processor");
    }

    @Override
    public void enhance(ComponentRegistry registry) {
        List<SpringSagaConfigurer> group = group();
        if (group.isEmpty() || group.getFirst() != this) {
            // Another member of this name group registers the shared module, or this configurer was superseded by a
            // later registration of the same Saga type.
            return;
        }
        registry.registerModule(moduleFor(group));
    }

    /**
     * The configurers sharing this configurer's {@link #processorName()}, in bean registration order.
     * <p>
     * Two beans for the same Saga type collapse into the last one registered, mirroring how Axon Framework 4 keyed its
     * Saga registrations by class name and let a later registration overwrite an earlier one.
     *
     * @return the configurers sharing this configurer's {@link #processorName()}, in bean registration order
     */
    private List<SpringSagaConfigurer> group() {
        ApplicationContext context = requireApplicationContext();
        Map<String, SpringSagaConfigurer> uniqueSagaTypes = new LinkedHashMap<>();
        for (SpringSagaConfigurer sibling : context.getBeansOfType(SpringSagaConfigurer.class).values()) {
            uniqueSagaTypes.put(sibling.sagaType.getName(), sibling);
        }
        String processorName = processorName();
        List<SpringSagaConfigurer> group = new ArrayList<>();
        for (SpringSagaConfigurer sibling : uniqueSagaTypes.values()) {
            if (processorName.equals(sibling.processorName())) {
                group.add(sibling);
            }
        }
        return group;
    }

    private EventProcessorModule moduleFor(List<SpringSagaConfigurer> group) {
        String processorName = processorName();
        var components = componentRegistration(group);

        Map<String, EventProcessorSettings> allSettings = settings();
        EventProcessorSettings explicitSettings = allSettings.get(processorName);
        EventProcessorSettings settings = explicitSettings != null
                ? explicitSettings
                : allSettings.getOrDefault(EventProcessorSettings.DEFAULT, DefaultSagaProcessorSettings.INSTANCE);

        return switch (settings.processorMode()) {
            case POOLED -> {
                var pooledSettings = (EventProcessorSettings.PooledEventProcessorSettings) settings;
                var baseCustomization = SpringCustomizations.pooledStreamingCustomizations(
                        processorName, pooledSettings
                );
                PooledStreamingEventProcessorModule.Customization customization =
                        (axonConfig, processorConfig) -> {
                            // Always start at the head: the settings cannot express an initial token, so no entry
                            // carries an intent to replay. A Customization bean below may still override this.
                            var result = processorConfig.initialToken(source -> source.latestToken(null));
                            result = baseCustomization.apply(axonConfig, result);
                            for (var extension : extensionCustomizations()) {
                                result = extension.apply(axonConfig, result);
                            }
                            SpringCustomizations.requireResolvedTokenStore(processorName, result);
                            return result;
                        };
                yield EventProcessorModule
                        .pooledStreaming(processorName)
                        .eventHandlingComponents(components)
                        .customized(customization)
                        .build();
            }
            case SUBSCRIBING -> {
                var subscribingSettings = (EventProcessorSettings.SubscribingEventProcessorSettings) settings;
                yield EventProcessorModule
                        .subscribing(processorName)
                        .eventHandlingComponents(components)
                        .customized(SpringCustomizations.subscribingCustomizations(processorName,
                                                                                   subscribingSettings))
                        .build();
            }
        };
    }

    private static Function<EventHandlingComponentsConfigurer.RequiredComponentPhase,
            EventHandlingComponentsConfigurer.CompletePhase> componentRegistration(
            List<SpringSagaConfigurer> group
    ) {
        Map<SpringSagaConfigurer, String> componentNames = componentNames(group);
        return phase -> {
            EventHandlingComponentsConfigurer.ComponentsPhase result = phase;
            for (SpringSagaConfigurer member : group) {
                result = result.declarative(componentNames.get(member), member.sagaComponent());
            }
            return (EventHandlingComponentsConfigurer.CompletePhase) result;
        };
    }

    /**
     * The component name each member of the given {@code group} registers its Saga under.
     * <p>
     * Simple names read best, but two Saga types in different packages may share one, so those fall back to the fully
     * qualified class name to keep the component names of a processor unique.
     *
     * @param group the configurers sharing one event processor
     * @return the component name each member of the given {@code group} registers its Saga under
     */
    private static Map<SpringSagaConfigurer, String> componentNames(List<SpringSagaConfigurer> group) {
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        for (SpringSagaConfigurer member : group) {
            occurrences.merge(member.sagaType.getSimpleName(), 1, Integer::sum);
        }
        Map<SpringSagaConfigurer, String> componentNames = new LinkedHashMap<>();
        for (SpringSagaConfigurer member : group) {
            String simpleName = member.sagaType.getSimpleName();
            String name = occurrences.get(simpleName) == 1 ? simpleName : member.sagaType.getName();
            componentNames.put(member, "Saga[" + name + "]");
        }
        return componentNames;
    }

    private ComponentBuilder<EventHandlingComponent> sagaComponent() {
        return sagaComponent(sagaType);
    }

    @SuppressWarnings("unchecked")
    private <T> ComponentBuilder<EventHandlingComponent> sagaComponent(Class<T> type) {
        if (StringUtils.emptyOrNull(sagaStore)) {
            return Sagas.of(type);
        }
        String sagaStoreBeanName = sagaStore;
        ApplicationContext context = requireApplicationContext();
        ComponentBuilder<SagaStore<? super T>> storeBuilder =
                configuration -> (SagaStore<? super T>) context.getBean(sagaStoreBeanName, SagaStore.class);
        return Sagas.of(type, storeBuilder);
    }

    private Map<String, EventProcessorSettings> settings() {
        EventProcessorSettings.MapWrapper settings = requireApplicationContext()
                .getBeanProvider(EventProcessorSettings.MapWrapper.class)
                .getIfAvailable();
        return settings == null ? Map.of() : settings.settings();
    }

    /**
     * The {@link PooledStreamingEventProcessorModule.Customization} beans of the application context.
     * <p>
     * Resolved while the processor configuration is built rather than while enhancing, so declaring a customization
     * does not force its dependencies to be instantiated during the context refresh.
     *
     * @return the {@link PooledStreamingEventProcessorModule.Customization} beans of the application context
     */
    private List<PooledStreamingEventProcessorModule.Customization> extensionCustomizations() {
        return requireApplicationContext()
                .getBeanProvider(PooledStreamingEventProcessorModule.Customization.class)
                .orderedStream()
                .toList();
    }

    private ApplicationContext requireApplicationContext() {
        return Objects.requireNonNull(applicationContext,
                                      "The ApplicationContext must be set before enhancing the configuration.");
    }

    /**
     * The settings a Saga processor falls back to when the application context carries none.
     * <p>
     * Mirrors the defaults of the {@code axon.eventhandling.processors} properties, so a Saga is configured the same
     * whether or not the Spring Boot property support is on the classpath.
     */
    private static final class DefaultSagaProcessorSettings
            implements EventProcessorSettings.PooledEventProcessorSettings,
            EventProcessorSettings.SubscribingEventProcessorSettings {

        private static final DefaultSagaProcessorSettings INSTANCE = new DefaultSagaProcessorSettings();

        @Override
        public ProcessorMode processorMode() {
            return ProcessorMode.POOLED;
        }

        @Override
        public @Nullable String source() {
            return null;
        }

        @Override
        public int initialSegmentCount() {
            return 16;
        }

        @Override
        public long tokenClaimIntervalInMillis() {
            return 5000;
        }

        @Override
        public int threadCount() {
            return 4;
        }

        @Override
        public int batchSize() {
            return 1;
        }

        @Override
        public @Nullable String tokenStore() {
            return null;
        }
    }
}
