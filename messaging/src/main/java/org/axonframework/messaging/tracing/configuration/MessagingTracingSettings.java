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

package org.axonframework.messaging.tracing.configuration;

import java.time.Duration;

/**
 * Per-component on/off toggles for messaging tracing, read by {@code MessagingTracingConfigurationEnhancer} to decide
 * which {@code axon-messaging} components to decorate.
 * <p>
 * When no instance is registered, every component defaults to enabled ({@link #enabledByDefault()}). To adjust the
 * toggles declaratively, register an instance as a component -- typically starting from the defaults and using the
 * {@code with*} copy methods:
 * <pre>{@code
 * configurer.componentRegistry(cr -> cr.registerComponent(
 *         MessagingTracingSettings.class,
 *         c -> MessagingTracingSettings.enabledByDefault().withEventProcessorDistributedInSameTrace(true)));
 * }</pre>
 * Higher-level integrations (for example property-based configuration layers) register a translated instance on the
 * application's behalf; an explicitly registered component always takes precedence.
 *
 * @param commandBusEnabled                          whether the {@code CommandBus} is decorated with tracing
 * @param eventSinkEnabled                           whether the {@code EventSink} is decorated with tracing
 * @param eventProcessorEnabled                      whether event-handling components (event processors) are decorated with tracing
 * @param eventProcessorBatchTraceEnabled            whether streaming-processor batches get an enclosing batch span; when disabled, each event handler span becomes a trace root of its own
 * @param eventProcessorDistributedInSameTrace       when {@code true}, a handler span continues the publisher's trace; when {@code false} (default), it is parented to the streaming batch and links back to the publisher, or starts a linked trace if batch tracing is disabled
 * @param eventProcessorDistributedInSameTraceTimeLimit how recent an event must be to continue the publisher's trace when {@code distributedInSameTrace} is {@code true}; older events (e.g. replays) start their own trace linked back to the publisher (default {@code PT2M})
 * @param queryBusEnabled                            whether the {@code QueryBus} is decorated with tracing
 * @param eventSourcingHandlersEnabled               when {@code true}, {@code @EventSourcingHandler} invocations get their own per-method span; defaults to {@code false} because event sourcing handlers fire once per event during entity replay and would flood traces
 * @param spanAttributesProviders                    toggles for the built-in span attribute providers contributed by this module
 * @author Mateusz Nowak
 * @since 5.3.0
 */
public record MessagingTracingSettings(boolean commandBusEnabled,
                                       boolean eventSinkEnabled,
                                       boolean eventProcessorEnabled,
                                       boolean eventProcessorBatchTraceEnabled,
                                       boolean eventProcessorDistributedInSameTrace,
                                       Duration eventProcessorDistributedInSameTraceTimeLimit,
                                       boolean queryBusEnabled,
                                       boolean eventSourcingHandlersEnabled,
                                       SpanAttributesProviders spanAttributesProviders) {

    /**
     * Default time limit for {@code distributedInSameTrace}.
     */
    public static final Duration DEFAULT_DISTRIBUTED_IN_SAME_TRACE_TIME_LIMIT = Duration.ofMinutes(2);

    /**
     * Toggles for the built-in {@link org.axonframework.messaging.tracing.SpanAttributesProvider SpanAttributesProviders}
     * contributed by the {@code axon-messaging} module (read by
     * {@code MessagingSpanAttributesProviderConfigurationEnhancer}).
     *
     * @param messageIdEnabled   whether the {@code MessageIdSpanAttributesProvider} is contributed
     * @param messageTypeEnabled whether the {@code MessageTypeSpanAttributesProvider} is contributed
     * @param metadataEnabled    whether the {@code MetadataSpanAttributesProvider} is contributed
     */
    public record SpanAttributesProviders(boolean messageIdEnabled,
                                          boolean messageTypeEnabled,
                                          boolean metadataEnabled) {

        /**
         * Returns the default provider toggles, with every built-in provider enabled.
         *
         * @return the all-enabled default provider toggles
         */
        public static SpanAttributesProviders enabledByDefault() {
            return new SpanAttributesProviders(true, true, true);
        }
    }

    /**
     * Returns the default settings, with every messaging component enabled for tracing and default values for the
     * event-processor sub-toggles ({@code batchTraceEnabled=true}, {@code distributedInSameTrace=false},
     * {@code distributedInSameTraceTimeLimit=PT2M}) and the handler enhancer
     * ({@code eventSourcingHandlersEnabled=false} -- replay-noisy event sourcing handlers are not traced). Every built-in
     * span attribute provider is enabled.
     *
     * @return the all-enabled default settings
     */
    public static MessagingTracingSettings enabledByDefault() {
        return new MessagingTracingSettings(true, true, true,
                                            true, false, DEFAULT_DISTRIBUTED_IN_SAME_TRACE_TIME_LIMIT,
                                            true, false, SpanAttributesProviders.enabledByDefault());
    }

    /**
     * Returns a copy of these settings with {@link #commandBusEnabled()} replaced by the given value.
     *
     * @param commandBusEnabled whether the {@code CommandBus} is decorated with tracing
     * @return a copy of these settings with the given {@code commandBusEnabled}
     */
    public MessagingTracingSettings withCommandBusEnabled(boolean commandBusEnabled) {
        return new MessagingTracingSettings(commandBusEnabled, eventSinkEnabled, eventProcessorEnabled,
                                            eventProcessorBatchTraceEnabled, eventProcessorDistributedInSameTrace,
                                            eventProcessorDistributedInSameTraceTimeLimit,
                                            queryBusEnabled, eventSourcingHandlersEnabled, spanAttributesProviders);
    }

    /**
     * Returns a copy of these settings with {@link #eventSinkEnabled()} replaced by the given value.
     *
     * @param eventSinkEnabled whether the {@code EventSink} is decorated with tracing
     * @return a copy of these settings with the given {@code eventSinkEnabled}
     */
    public MessagingTracingSettings withEventSinkEnabled(boolean eventSinkEnabled) {
        return new MessagingTracingSettings(commandBusEnabled, eventSinkEnabled, eventProcessorEnabled,
                                            eventProcessorBatchTraceEnabled, eventProcessorDistributedInSameTrace,
                                            eventProcessorDistributedInSameTraceTimeLimit,
                                            queryBusEnabled, eventSourcingHandlersEnabled, spanAttributesProviders);
    }

    /**
     * Returns a copy of these settings with {@link #eventProcessorEnabled()} replaced by the given value.
     *
     * @param eventProcessorEnabled whether event-handling components (event processors) are decorated with tracing
     * @return a copy of these settings with the given {@code eventProcessorEnabled}
     */
    public MessagingTracingSettings withEventProcessorEnabled(boolean eventProcessorEnabled) {
        return new MessagingTracingSettings(commandBusEnabled, eventSinkEnabled, eventProcessorEnabled,
                                            eventProcessorBatchTraceEnabled, eventProcessorDistributedInSameTrace,
                                            eventProcessorDistributedInSameTraceTimeLimit,
                                            queryBusEnabled, eventSourcingHandlersEnabled, spanAttributesProviders);
    }

    /**
     * Returns a copy of these settings with {@link #eventProcessorBatchTraceEnabled()} replaced by the given value.
     *
     * @param eventProcessorBatchTraceEnabled whether streaming-processor batches get an enclosing batch span
     * @return a copy of these settings with the given {@code eventProcessorBatchTraceEnabled}
     */
    public MessagingTracingSettings withEventProcessorBatchTraceEnabled(boolean eventProcessorBatchTraceEnabled) {
        return new MessagingTracingSettings(commandBusEnabled, eventSinkEnabled, eventProcessorEnabled,
                                            eventProcessorBatchTraceEnabled, eventProcessorDistributedInSameTrace,
                                            eventProcessorDistributedInSameTraceTimeLimit,
                                            queryBusEnabled, eventSourcingHandlersEnabled, spanAttributesProviders);
    }

    /**
     * Returns a copy of these settings with {@link #eventProcessorDistributedInSameTrace()} replaced by the given
     * value.
     *
     * @param eventProcessorDistributedInSameTrace when {@code true}, a handler span continues the publisher's trace
     * @return a copy of these settings with the given {@code eventProcessorDistributedInSameTrace}
     */
    public MessagingTracingSettings withEventProcessorDistributedInSameTrace(
            boolean eventProcessorDistributedInSameTrace) {
        return new MessagingTracingSettings(commandBusEnabled, eventSinkEnabled, eventProcessorEnabled,
                                            eventProcessorBatchTraceEnabled, eventProcessorDistributedInSameTrace,
                                            eventProcessorDistributedInSameTraceTimeLimit,
                                            queryBusEnabled, eventSourcingHandlersEnabled, spanAttributesProviders);
    }

    /**
     * Returns a copy of these settings with {@link #eventProcessorDistributedInSameTraceTimeLimit()} replaced by the
     * given value.
     *
     * @param eventProcessorDistributedInSameTraceTimeLimit how recent an event must be to continue the publisher's
     *                                                      trace when {@code distributedInSameTrace} is {@code true}
     * @return a copy of these settings with the given {@code eventProcessorDistributedInSameTraceTimeLimit}
     */
    public MessagingTracingSettings withEventProcessorDistributedInSameTraceTimeLimit(
            Duration eventProcessorDistributedInSameTraceTimeLimit) {
        return new MessagingTracingSettings(commandBusEnabled, eventSinkEnabled, eventProcessorEnabled,
                                            eventProcessorBatchTraceEnabled, eventProcessorDistributedInSameTrace,
                                            eventProcessorDistributedInSameTraceTimeLimit,
                                            queryBusEnabled, eventSourcingHandlersEnabled, spanAttributesProviders);
    }

    /**
     * Returns a copy of these settings with {@link #queryBusEnabled()} replaced by the given value.
     *
     * @param queryBusEnabled whether the {@code QueryBus} is decorated with tracing
     * @return a copy of these settings with the given {@code queryBusEnabled}
     */
    public MessagingTracingSettings withQueryBusEnabled(boolean queryBusEnabled) {
        return new MessagingTracingSettings(commandBusEnabled, eventSinkEnabled, eventProcessorEnabled,
                                            eventProcessorBatchTraceEnabled, eventProcessorDistributedInSameTrace,
                                            eventProcessorDistributedInSameTraceTimeLimit,
                                            queryBusEnabled, eventSourcingHandlersEnabled, spanAttributesProviders);
    }

    /**
     * Returns a copy of these settings with {@link #eventSourcingHandlersEnabled()} replaced by the given value.
     *
     * @param eventSourcingHandlersEnabled whether {@code @EventSourcingHandler} invocations get their own span
     * @return a copy of these settings with the given {@code eventSourcingHandlersEnabled}
     */
    public MessagingTracingSettings withEventSourcingHandlersEnabled(boolean eventSourcingHandlersEnabled) {
        return new MessagingTracingSettings(commandBusEnabled, eventSinkEnabled, eventProcessorEnabled,
                                            eventProcessorBatchTraceEnabled, eventProcessorDistributedInSameTrace,
                                            eventProcessorDistributedInSameTraceTimeLimit,
                                            queryBusEnabled, eventSourcingHandlersEnabled, spanAttributesProviders);
    }

    /**
     * Returns a copy of these settings with {@link #spanAttributesProviders()} replaced by the given value.
     *
     * @param spanAttributesProviders toggles for the built-in span attribute providers contributed by this module
     * @return a copy of these settings with the given {@code spanAttributesProviders}
     */
    public MessagingTracingSettings withSpanAttributesProviders(SpanAttributesProviders spanAttributesProviders) {
        return new MessagingTracingSettings(commandBusEnabled, eventSinkEnabled, eventProcessorEnabled,
                                            eventProcessorBatchTraceEnabled, eventProcessorDistributedInSameTrace,
                                            eventProcessorDistributedInSameTraceTimeLimit,
                                            queryBusEnabled, eventSourcingHandlersEnabled, spanAttributesProviders);
    }
}
