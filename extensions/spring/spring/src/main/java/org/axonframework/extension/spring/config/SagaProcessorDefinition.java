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

import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorConfiguration;
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
 * property. This type states neither: it only customizes, leaving discovery and mode where they were.
 * <p>
 * Example usage:
 * <pre>{@code
 * @Bean
 * SagaProcessorDefinition replayIntoOrderSaga() {
 *     return SagaProcessorDefinition.forSaga(OrderSaga.class)
 *                                   .customized(config -> config.initialToken(
 *                                           source -> source.firstToken(null)
 *                                   ));
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
    private final UnaryOperator<PooledStreamingEventProcessorConfiguration> customization;

    private SagaProcessorDefinition(@Nullable Class<?> sagaType,
                                    @Nullable String processorName,
                                    UnaryOperator<PooledStreamingEventProcessorConfiguration> customization) {
        this.sagaType = sagaType;
        this.processorName = processorName;
        this.customization = customization;
    }

    /**
     * Starts a definition for the processor carrying the given {@code sagaType}, wherever that Saga was assigned.
     * <p>
     * Preferred over {@link #forProcessor(String)}: it follows the Saga if its processor is renamed with a
     * {@link org.axonframework.messaging.core.annotation.Namespace @Namespace} or assigned by an
     * {@link EventProcessorDefinition} selector, and it does not go stale when the Saga type is renamed.
     *
     * @param sagaType the Saga type whose processor to configure
     * @return the next step, taking the customization to apply
     */
    public static Builder forSaga(Class<?> sagaType) {
        Objects.requireNonNull(sagaType, "The sagaType must not be null.");
        return customization -> new SagaProcessorDefinition(sagaType, null, customization);
    }

    /**
     * Starts a definition for the Saga processor named {@code processorName}.
     * <p>
     * Use this to configure a processor shared by several Sagas in one place. To configure the processor of a single
     * Saga, prefer {@link #forSaga(Class)}, which needs no knowledge of how the name was derived.
     *
     * @param processorName the name of the Saga processor to configure
     * @return the next step, taking the customization to apply
     */
    public static Builder forProcessor(String processorName) {
        Objects.requireNonNull(processorName, "The processorName must not be null.");
        return customization -> new SagaProcessorDefinition(null, processorName, customization);
    }

    /**
     * The step taking the customization to apply to the selected Saga processor.
     */
    @FunctionalInterface
    public interface Builder {

        /**
         * Completes the definition with the {@code customization} to apply to the selected Saga processor.
         *
         * @param customization the customization to apply
         * @return the completed definition, to be declared as a Spring bean
         */
        SagaProcessorDefinition customized(UnaryOperator<PooledStreamingEventProcessorConfiguration> customization);
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
     * Returns the customization to apply to the selected Saga processor.
     *
     * @return the customization to apply
     */
    UnaryOperator<PooledStreamingEventProcessorConfiguration> customization() {
        return customization;
    }
}
