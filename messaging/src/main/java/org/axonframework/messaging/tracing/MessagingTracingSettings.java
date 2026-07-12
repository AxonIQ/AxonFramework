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

package org.axonframework.messaging.tracing;

import org.axonframework.common.annotation.Internal;

import java.time.Duration;

/**
 * Per-component on/off toggles for messaging tracing, read by {@code MessagingTracingConfigurationEnhancer} to decide
 * which {@code axon-messaging} components to decorate.
 * <p>
 * This is registered as a framework component by the Spring autoconfiguration (populated from
 * {@code axon.tracing.*} properties). When absent from the configuration, every component defaults to enabled. It is
 * {@code @Internal} because it is the integration point between the Spring property model and the ServiceLoader-
 * discovered enhancer, not a type applications construct directly.
 *
 * @param commandBusEnabled                          whether the {@code CommandBus} is decorated with tracing
 * @param eventSinkEnabled                           whether the {@code EventSink} is decorated with tracing
 * @param eventProcessorEnabled                      whether event-handling components (event processors) are decorated with tracing
 * @param eventProcessorDisableBatchTrace            when {@code true}, suppresses the streaming-processor batch span (each event handler still gets its own span)
 * @param eventProcessorDistributedInSameTrace       when {@code true}, a handler span continues the publisher's trace; when {@code false} (default), it is parented to the streaming batch and links back to the publisher, or starts a linked trace if batch tracing is disabled
 * @param eventProcessorDistributedInSameTraceTimeLimit how recent an event must be to continue the publisher's trace when {@code distributedInSameTrace} is {@code true}; older events (e.g. replays) start their own trace linked back to the publisher (default {@code PT2M})
 * @param queryBusEnabled                            whether the {@code QueryBus} is decorated with tracing
 * @param showEventSourcingHandlers                  when {@code true}, {@code @EventSourcingHandler} invocations get their own per-method span; defaults to {@code false} because event sourcing handlers fire once per event during entity replay and would flood traces
 * @param spanAttributesProviders                    toggles for the built-in span attribute providers contributed by this module
 * @author Mateusz Nowak
 * @since 5.3.0
 */
@Internal
public record MessagingTracingSettings(boolean commandBusEnabled,
                                       boolean eventSinkEnabled,
                                       boolean eventProcessorEnabled,
                                       boolean eventProcessorDisableBatchTrace,
                                       boolean eventProcessorDistributedInSameTrace,
                                       Duration eventProcessorDistributedInSameTraceTimeLimit,
                                       boolean queryBusEnabled,
                                       boolean showEventSourcingHandlers,
                                       SpanAttributesProviders spanAttributesProviders) {

    /**
     * Default time limit for {@code distributedInSameTrace}.
     */
    public static final Duration DEFAULT_DISTRIBUTED_IN_SAME_TRACE_TIME_LIMIT = Duration.ofMinutes(2);

    /**
     * Toggles for the built-in {@link org.axonframework.messaging.tracing.SpanAttributesProvider SpanAttributesProviders}
     * contributed by the {@code axoniq-tracing-messaging} module (read by
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
     * event-processor sub-toggles ({@code disableBatchTrace=false}, {@code distributedInSameTrace=false},
     * {@code distributedInSameTraceTimeLimit=PT2M}) and the handler enhancer
     * ({@code showEventSourcingHandlers=false} -- replay-noisy event sourcing handlers are not traced). Every built-in
     * span attribute provider is enabled.
     *
     * @return the all-enabled default settings
     */
    public static MessagingTracingSettings enabledByDefault() {
        return new MessagingTracingSettings(true, true, true,
                                            false, false, DEFAULT_DISTRIBUTED_IN_SAME_TRACE_TIME_LIMIT,
                                            true, false, SpanAttributesProviders.enabledByDefault());
    }

    /**
     * Returns a copy of these settings with {@link #showEventSourcingHandlers()} replaced by the given value.
     *
     * @param showEventSourcingHandlers whether {@code @EventSourcingHandler} invocations get their own span
     * @return a copy of these settings with the given {@code showEventSourcingHandlers}
     */
    public MessagingTracingSettings withShowEventSourcingHandlers(boolean showEventSourcingHandlers) {
        return new MessagingTracingSettings(commandBusEnabled, eventSinkEnabled, eventProcessorEnabled,
                                            eventProcessorDisableBatchTrace, eventProcessorDistributedInSameTrace,
                                            eventProcessorDistributedInSameTraceTimeLimit,
                                            queryBusEnabled, showEventSourcingHandlers, spanAttributesProviders);
    }
}
