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

package org.axonframework.messaging.eventhandling.processing.subscribing;

import org.axonframework.common.configuration.BaseModule;
import org.axonframework.common.configuration.ComponentBuilder;
import org.axonframework.common.configuration.ComponentDefinition;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.ModuleBuilder;
import org.axonframework.common.lifecycle.Phase;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.configuration.DefaultEventHandlingComponentsConfigurer;
import org.axonframework.messaging.eventhandling.configuration.EventHandlingComponentsConfigurer;
import org.axonframework.messaging.eventhandling.configuration.EventProcessingConfigurer;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorConfiguration;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorCustomization;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorModule;
import org.axonframework.messaging.eventhandling.interception.InterceptingEventHandlingComponent;
import org.axonframework.messaging.eventhandling.processing.EventProcessor;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.SequenceCachingEventHandlingComponent;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * A configuration module for configuring and registering a single {@link SubscribingEventProcessor} component.
 * <p>
 * The main capabilities provided by this module include:
 * <ul>
 * <li>Event handling component decoration with tracing, monitoring, and interception</li>
 * <li>Integration with shared configuration customizations from parent modules</li>
 * <li>Lifecycle management for the created processor</li>
 * <li>Automatic subscription to the configured message source</li>
 * </ul>
 * <p>
 * This module is typically not instantiated directly but created through
 * {@link EventProcessorModule#subscribing(String)} or registered via
 * {@link SubscribingEventProcessorsConfigurer#defaultProcessor} methods.
 * <p>
 * The module applies shared defaults from {@link SubscribingEventProcessorsConfigurer} and
 * {@link EventProcessingConfigurer} before applying
 * processor-specific customizations.
 *
 * @author Mateusz Nowak
 * @since 5.0.0
 */
public class SubscribingEventProcessorModule extends BaseModule<SubscribingEventProcessorModule>
        implements EventProcessorModule, ModuleBuilder<SubscribingEventProcessorModule>,
        EventProcessorModule.EventHandlingPhase<SubscribingEventProcessorModule, SubscribingEventProcessorConfiguration>,
        EventProcessorModule.CustomizationPhase<SubscribingEventProcessorModule, SubscribingEventProcessorConfiguration> {

    private final String processorName;
    private Map<String, ComponentBuilder<EventHandlingComponent>> eventHandlingComponentBuilders;
    private ComponentBuilder<SubscribingEventProcessorConfiguration> customizedProcessorConfigurationBuilder;

    /**
     * Constructs a module with the given processor name.
     * <p>
     * The processor name will be used as the module name and as the unique identifier for the
     * {@link SubscribingEventProcessor} component created by this module.
     *
     * @param processorName The unique name for the subscribing event processor.
     */
    public SubscribingEventProcessorModule(String processorName) {
        super("EventProcessor[" + processorName + "]");
        this.processorName = processorName;
    }

    @Override
    public SubscribingEventProcessorModule build() {
        registerCustomizedConfiguration();
        registerEventHandlingComponents();
        registerEventProcessor();
        return this;
    }

    private void registerCustomizedConfiguration() {
        componentRegistry(cr -> cr.registerComponent(
                ComponentDefinition.ofType(SubscribingEventProcessorConfiguration.class)
                                   .withBuilder(cfg -> customizedProcessorConfigurationBuilder.build(cfg))
        ));
    }

    private void registerEventProcessor() {
        var processorComponentDefinition = ComponentDefinition
                .ofTypeAndName(EventProcessor.class, processorName)
                .withBuilder(cfg -> new SubscribingEventProcessor(
                        processorName,
                        getEventHandlingComponents(cfg),
                        cfg.getComponent(SubscribingEventProcessorConfiguration.class)
                ))
                .onStart(Phase.LOCAL_MESSAGE_HANDLER_REGISTRATIONS, (cfg, processor) -> {
                    return processor.start();
                }).onShutdown(Phase.LOCAL_MESSAGE_HANDLER_REGISTRATIONS, (cfg, processor) -> {
                    return processor.shutdown();
                });

        componentRegistry(cr -> cr.registerComponent(processorComponentDefinition));
    }

    private void registerEventHandlingComponents() {
        for (var componentBuilderEntry : eventHandlingComponentBuilders.entrySet()) {
            var componentBuilder = componentBuilderEntry.getValue();
            var componentName = processorEventHandlingComponentName(componentBuilderEntry.getKey());
            componentRegistry(cr -> {
                cr.registerComponent(EventHandlingComponent.class, componentName,
                                     cfg -> {
                                         var component = componentBuilder.build(cfg);
                                         return new SequenceCachingEventHandlingComponent(component);
                                     });
                cr.registerDecorator(EventHandlingComponent.class, componentName,
                                     InterceptingEventHandlingComponent.DECORATION_ORDER,
                                     (config, name, delegate) -> {
                                         var configuration = config.getComponent(
                                                 SubscribingEventProcessorConfiguration.class
                                         );
                                         return new InterceptingEventHandlingComponent(
                                                 configuration.interceptors(),
                                                 delegate
                                         );
                                     });
            });
        }
    }

    private List<EventHandlingComponent> getEventHandlingComponents(Configuration configuration) {
        return eventHandlingComponentBuilders.keySet()
                                             .stream()
                                             .map(componentName -> configuration.getComponent(
                                                     EventHandlingComponent.class,
                                                     processorEventHandlingComponentName(componentName)
                                             ))
                                             .toList();
    }

    private String processorEventHandlingComponentName(String componentName) {
        return "EventHandlingComponent[" + processorName + "][" + componentName + "]";
    }

    @Override
    public SubscribingEventProcessorModule customized(
            BiFunction<Configuration, SubscribingEventProcessorConfiguration, SubscribingEventProcessorConfiguration> instanceCustomization
    ) {
        this.customizedProcessorConfigurationBuilder = config -> {
            var typeCustomization = typeSpecificCustomizationOrNoOp(config)
                    .apply(config, defaultEventProcessorsConfiguration(config, processorName));
            return instanceCustomization.apply(config, typeCustomization);
        };
        return this;
    }

    @Override
    public SubscribingEventProcessorModule notCustomized() {
        if (customizedProcessorConfigurationBuilder == null) {
            customized((cfg, config) -> config);
        }
        return this;
    }

    static SubscribingEventProcessorConfiguration defaultEventProcessorsConfiguration(
            Configuration config,
            String processorName
    ) {
        return new SubscribingEventProcessorConfiguration(
                parentSharedCustomizationOrDefault(config)
                        .apply(config, new EventProcessorConfiguration(processorName, config)),
                config
        );
    }

    private static SubscribingEventProcessorModule.Customization typeSpecificCustomizationOrNoOp(
            Configuration cfg
    ) {
        return cfg.getOptionalComponent(SubscribingEventProcessorModule.Customization.class)
                  .orElseGet(SubscribingEventProcessorModule.Customization::noOp);
    }

    private static EventProcessorCustomization parentSharedCustomizationOrDefault(
            Configuration cfg
    ) {
        return cfg.getOptionalComponent(EventProcessorCustomization.class)
                  .orElseGet(EventProcessorCustomization::noOp);
    }

    @Override
    public CustomizationPhase<SubscribingEventProcessorModule, SubscribingEventProcessorConfiguration> eventHandlingComponents(
            Function<EventHandlingComponentsConfigurer.RequiredComponentPhase, EventHandlingComponentsConfigurer.CompletePhase> configurerTask
    ) {
        Objects.requireNonNull(configurerTask, "configurerTask may not be null");
        var componentsConfigurer = new DefaultEventHandlingComponentsConfigurer();
        this.eventHandlingComponentBuilders = configurerTask.apply(componentsConfigurer).toMap();
        return this;
    }

    /**
     * Allows customizing the {@link SubscribingEventProcessorConfiguration}.
     * <p>
     * The interface provides composition capabilities through
     * {@link #andThen(SubscribingEventProcessorModule.Customization)} to allow chaining multiple customizations in a
     * specific order.
     *
     * @author Mateusz Nowak
     * @since 5.0.0
     */
    @FunctionalInterface
    public interface Customization extends
            BiFunction<Configuration, SubscribingEventProcessorConfiguration, SubscribingEventProcessorConfiguration> {

        /**
         * Creates a no-operation customization that returns the processor configuration unchanged.
         *
         * @return A customization that applies no changes to the processor configuration.
         */
        static Customization noOp() {
            return (config, pConfig) -> pConfig;
        }

        /**
         * Returns a composed customization that applies this customization first, then applies the other
         * customization.
         * <p>
         * This allows for chaining multiple customizations together, with each subsequent customization receiving the
         * result of the previous one.
         *
         * @param other The customization to apply after this one.
         * @return A composed customization that applies both customizations in sequence.
         */
        default Customization andThen(Customization other) {
            Objects.requireNonNull(other, "other may not be null");
            return (config, pConfig) -> other.apply(config, this.apply(config, pConfig));
        }
    }
}
