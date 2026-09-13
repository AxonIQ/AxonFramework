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
import org.axonframework.messaging.eventhandling.configuration.EventProcessorConfiguration;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorConfiguration;
import org.axonframework.messaging.eventhandling.processing.subscribing.SubscribingEventProcessorConfiguration;
import org.axonframework.spring.stereotype.Saga;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Configures the event processor carrying one or more Axon Framework 4 {@link Saga @Saga} types, declared as a Spring
 * bean.
 * <p>
 * Sagas do not take part in the {@code PooledStreamingEventProcessorModule.Customization} channel, because that is
 * how cross-cutting Axon Framework 5 infrastructure such as dead-lettering attaches itself to every processor, and a
 * Saga should not silently acquire behavior Axon Framework 4 never gave it. This is the replacement for that
 * channel, scoped to Sagas: a type only a Saga's own configuration declares, so no Axon Framework 5 extension can
 * arrive through it.
 * <p>
 * An {@link EventProcessorDefinition} configures a Saga's processor too, but it answers two questions a Saga has
 * already settled. It has to state which handlers it assigns, which for a Saga is nobody, and it fixes the
 * processor's mode, so a definition written only to change a batch size also overrules a {@code mode=subscribing}
 * property. This type states neither.
 * <p>
 * A pooled streaming and a subscribing processor expose different configuration, so a customization touching
 * mode-specific settings says which mode it was written against: {@link Selector#whenPooledStreaming(UnaryOperator)}
 * or {@link Selector#whenSubscribing(UnaryOperator)}. Naming the mode does not select it. A Saga's mode comes from
 * {@code axon.eventhandling.processors.<name>.mode} or a matching {@link EventProcessorDefinition}, and a definition
 * written for the other mode is skipped, with a warning, so a customization never quietly does nothing. Letting an
 * adjustment switch the processor's kind would be a far larger change than the adjustment itself, since it decides
 * whether there is a token store, whether there are segments, and whether a second application instance starts a
 * duplicate Saga.
 * <p>
 * {@link Selector#customized(UnaryOperator)} covers the settings both modes share and never mentions a mode.
 * <p>
 * Example usage:
 * <pre>{@code
 * @Bean
 * SagaProcessorDefinition replayIntoOrderSaga() {
 *     return SagaProcessorDefinition.forSaga(OrderSaga.class)
 *                                   .whenPooledStreaming(config -> config.initialToken(
 *                                           source -> source.firstToken(null)
 *                                   ));
 * }
 *
 * @Bean
 * SagaProcessorDefinition orderSagaEventSource(SubscribableEventSource source) {
 *     return SagaProcessorDefinition.forSaga(OrderSaga.class)
 *                                   .whenSubscribing(config -> config.eventSource(source));
 * }
 * }</pre>
 * <p>
 * Applied after everything else that configures the processor: the Saga defaults, the
 * {@code axon.eventhandling.processors.<name>.*} properties, and a matching {@link EventProcessorDefinition}. Several
 * definitions matching one processor are applied in Spring bean order.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
public final class SagaProcessorDefinition {

    private final @Nullable Class<?> sagaType;
    private final @Nullable String processorName;
    private final @Nullable UnaryOperator<PooledStreamingEventProcessorConfiguration> pooledCustomization;
    private final @Nullable UnaryOperator<SubscribingEventProcessorConfiguration> subscribingCustomization;
    private final @Nullable UnaryOperator<EventProcessorConfiguration> anyModeCustomization;

    private SagaProcessorDefinition(
            @Nullable Class<?> sagaType,
            @Nullable String processorName,
            @Nullable UnaryOperator<PooledStreamingEventProcessorConfiguration> pooledCustomization,
            @Nullable UnaryOperator<SubscribingEventProcessorConfiguration> subscribingCustomization,
            @Nullable UnaryOperator<EventProcessorConfiguration> anyModeCustomization
    ) {
        this.sagaType = sagaType;
        this.processorName = processorName;
        this.pooledCustomization = pooledCustomization;
        this.subscribingCustomization = subscribingCustomization;
        this.anyModeCustomization = anyModeCustomization;
    }

    /**
     * Starts a definition for the processor carrying the given {@code sagaType}, wherever that Saga was assigned.
     * <p>
     * The Saga type selects the processor; it does not narrow the customization to that one Saga. A processor is the
     * unit of configuration, so when {@code sagaType} shares its processor with other Sagas, the customization
     * applies to all of them. That case is logged, since naming one Saga and configuring several is worth noticing;
     * prefer {@link #forProcessor(String)} there, which says so in the code.
     * <p>
     * Preferred over {@link #forProcessor(String)} for a Saga on its own processor: it follows the Saga if the
     * processor is renamed with a {@link org.axonframework.messaging.core.annotation.Namespace @Namespace} or
     * assigned by an {@link EventProcessorDefinition} selector, and it does not go stale when the Saga type is
     * renamed.
     *
     * @param sagaType the Saga type whose processor to configure
     * @return the next step, naming the processor mode and the customization to apply
     */
    public static Selector forSaga(Class<?> sagaType) {
        Objects.requireNonNull(sagaType, "The sagaType must not be null.");
        return new Selector(sagaType, null);
    }

    /**
     * Starts a definition for the Saga processor named {@code processorName}.
     * <p>
     * Use this to configure a processor shared by several Sagas in one place. To configure the processor of a single
     * Saga, prefer {@link #forSaga(Class)}, which needs no knowledge of how the name was derived.
     *
     * @param processorName the name of the Saga processor to configure
     * @return the next step, naming the processor mode and the customization to apply
     */
    public static Selector forProcessor(String processorName) {
        Objects.requireNonNull(processorName, "The processorName must not be null.");
        return new Selector(null, processorName);
    }

    /**
     * The step naming the processor mode the customization is written for, and the customization itself.
     */
    public static final class Selector {

        private final @Nullable Class<?> sagaType;
        private final @Nullable String processorName;

        private Selector(@Nullable Class<?> sagaType, @Nullable String processorName) {
            this.sagaType = sagaType;
            this.processorName = processorName;
        }

        /**
         * Completes the definition with a {@code customization} applied only when the selected processor runs in
         * pooled streaming mode, which is the default for a Saga.
         * <p>
         * Naming the mode does not select it. The processor's mode comes from
         * {@code axon.eventhandling.processors.<name>.mode} or a matching {@link EventProcessorDefinition}; this
         * only says which configuration the customization was written against. Use {@link #customized(UnaryOperator)}
         * for settings both modes share.
         *
         * @param customization the customization to apply
         * @return the completed definition, to be declared as a Spring bean
         */
        public SagaProcessorDefinition whenPooledStreaming(
                UnaryOperator<PooledStreamingEventProcessorConfiguration> customization
        ) {
            Objects.requireNonNull(customization, "The customization must not be null.");
            return new SagaProcessorDefinition(sagaType, processorName, customization, null, null);
        }

        /**
         * Completes the definition with a {@code customization} applied only when the selected processor runs in
         * subscribing mode.
         * <p>
         * Naming the mode does not select it. Switch a Saga to a subscribing processor with
         * {@code axon.eventhandling.processors.<name>.mode=subscribing}; this only says which configuration the
         * customization was written against. Use {@link #customized(UnaryOperator)} for settings both modes share.
         *
         * @param customization the customization to apply
         * @return the completed definition, to be declared as a Spring bean
         */
        public SagaProcessorDefinition whenSubscribing(
                UnaryOperator<SubscribingEventProcessorConfiguration> customization
        ) {
            Objects.requireNonNull(customization, "The customization must not be null.");
            return new SagaProcessorDefinition(sagaType, processorName, null, customization, null);
        }

        /**
         * Completes the definition with a {@code customization} applied whatever mode the selected processor runs
         * in, covering the settings both modes share: the error handler, the unit of work factory, and extensions.
         * <p>
         * The {@code customization} must return the configuration it was given. These configuration objects are
         * mutable builders whose setters return {@code this}, so returning anything else replaces a pooled streaming
         * or subscribing configuration with a plain one and is rejected.
         *
         * @param customization the customization to apply
         * @return the completed definition, to be declared as a Spring bean
         */
        public SagaProcessorDefinition customized(UnaryOperator<EventProcessorConfiguration> customization) {
            Objects.requireNonNull(customization, "The customization must not be null.");
            return new SagaProcessorDefinition(sagaType, processorName, null, null, customization);
        }
    }

    /**
     * Indicates whether this definition configures the processor named {@code candidateProcessorName}, carrying
     * {@code sagaTypes}.
     *
     * @param candidateProcessorName the name of the processor being assembled
     * @param sagaTypes              the Saga types assigned to that processor
     * @return {@code true} when this definition applies to that processor
     */
    boolean matches(String candidateProcessorName, Iterable<Class<?>> sagaTypes) {
        if (processorName != null) {
            return processorName.equals(candidateProcessorName);
        }
        for (Class<?> type : sagaTypes) {
            if (type.equals(sagaType)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the customization for a pooled streaming processor, or {@code null} when this definition was written
     * for a subscribing one.
     *
     * @return the pooled streaming customization, if any
     */
    @Nullable
    UnaryOperator<PooledStreamingEventProcessorConfiguration> pooledCustomization() {
        return pooledCustomization;
    }

    /**
     * Returns the customization for a subscribing processor, or {@code null} when this definition was written for a
     * pooled streaming one.
     *
     * @return the subscribing customization, if any
     */
    @Nullable
    UnaryOperator<SubscribingEventProcessorConfiguration> subscribingCustomization() {
        return subscribingCustomization;
    }

    /**
     * Applies the mode-independent customization, if any, to the given {@code configuration}, returning it
     * unchanged when this definition has none.
     *
     * @param configuration the processor configuration to customize
     * @param type          the configuration type to hand back
     * @param <T>           the processor configuration type
     * @return the customized configuration
     */
    <T extends EventProcessorConfiguration> T applyAnyModeCustomization(T configuration, Class<T> type) {
        if (anyModeCustomization == null) {
            return configuration;
        }
        EventProcessorConfiguration result = anyModeCustomization.apply(configuration);
        if (!type.isInstance(result)) {
            throw new AxonConfigurationException(
                    "A SagaProcessorDefinition for " + describeSelector() + " returned a "
                            + result.getClass().getSimpleName() + " where a " + type.getSimpleName()
                            + " was expected. A mode-independent customization must return the configuration it was "
                            + "given; its setters already do, by returning the same instance.");
        }
        return type.cast(result);
    }

    /**
     * Indicates whether this definition was written for one specific processor mode, rather than for the settings
     * both modes share.
     *
     * @return {@code true} when this definition applies to one mode only
     */
    boolean isModeSpecific() {
        return anyModeCustomization == null;
    }

    /**
     * Returns the Saga type this definition selected its processor by, or {@code null} when it selected the processor
     * by name.
     *
     * @return the Saga type used to select the processor, if any
     */
    @Nullable
    Class<?> sagaType() {
        return sagaType;
    }

    /**
     * Returns a description of this definition's selector, for logging.
     *
     * @return the selector description
     */
    String describeSelector() {
        return sagaType != null ? "Saga [" + sagaType.getName() + "]" : "processor [" + processorName + "]";
    }
}
