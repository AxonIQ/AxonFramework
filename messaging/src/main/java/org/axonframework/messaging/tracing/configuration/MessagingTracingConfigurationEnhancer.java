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

import org.axonframework.messaging.tracing.SpanFactory;
import org.axonframework.messaging.commandhandling.tracing.TracingCommandBus;
import org.axonframework.messaging.eventhandling.tracing.TracingEventHandlingComponent;
import org.axonframework.messaging.eventhandling.tracing.TracingEventSink;
import org.axonframework.messaging.queryhandling.tracing.TracingQueryBus;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.annotation.RegistrationScope;
import org.axonframework.common.configuration.CapabilityPreservingDecorator;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.EventSink;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorConfiguration;
import org.axonframework.messaging.queryhandling.QueryBus;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 * {@link ConfigurationEnhancer} that wires tracing into {@code axon-messaging} components. Discovered automatically via
 * ServiceLoader, so dropping the {@code axon-messaging} module on the classpath is enough to enable messaging tracing.
 * <p>
 * A component is only decorated when a non-no-op {@link SpanFactory} is configured (so component-level tracing imposes
 * no overhead when disabled) and the corresponding toggle in {@link MessagingTracingSettings} is enabled. Per-method
 * handler spans (for {@code @CommandHandler} / {@code @EventHandler} / {@code @QueryHandler}) are produced by
 * {@code TracingHandlerEnhancerDefinition}, which is contributed via the standard {@code HandlerEnhancerDefinition}
 * ServiceLoader entry shipped with this module -- no additional registration is needed here.
 *
 * @author Mateusz Nowak
 * @since 5.3.0
 */
@Internal
@RegistrationScope("Register decorators once at the root; do not re-invoke in child module registries "
        + "(the DecoratorDefinitions are copied down and reach module-built components on their own). "
        + "Re-invoking per nesting level re-registers the decorators and produces duplicate nested spans.")
public final class MessagingTracingConfigurationEnhancer implements ConfigurationEnhancer {

    /**
     * Decorator order for the messaging tracing decorators -- see
     * {@link TracingConfigurationOrder#TRACING_DECORATOR_ORDER} for the full ordering rationale.
     */
    public static final int TRACING_DECORATOR_ORDER = TracingConfigurationOrder.TRACING_DECORATOR_ORDER;

    @Override
    public void enhance(ComponentRegistry registry) {
        registry.registerIfNotPresent(MessagingTracingSettings.class,
                                      c -> MessagingTracingSettings.enabledByDefault());
        registry.registerDecorator(
                CommandBus.class,
                TRACING_DECORATOR_ORDER,
                (config, name, delegate) -> {
                    SpanFactory spanFactory = spanFactory(config);
                    if (spanFactory == null || !settings(config).commandBusEnabled()) {
                        return delegate;
                    }
                    return new TracingCommandBus(delegate, spanFactory);
                }
        );
        // Matching is assignability-based, so a decorator registered for EventSink also reaches EventBus and
        // EventStore components. preservingCapabilitiesOf keeps it from narrowing those wider components down to a
        // bare EventSink: it widens the result back to the delegate's full original interface set whenever
        // TracingEventSink alone would not cover it, routing EventSink-declared methods (publish) to the tracing
        // wrapper and every other method (subscribe, EventStore's transaction/open/token methods, ...) straight to
        // the raw, undecorated delegate.
        registry.registerDecorator(
                EventSink.class,
                TRACING_DECORATOR_ORDER,
                CapabilityPreservingDecorator.preservingCapabilitiesOf((config, name, delegate) -> {
                    SpanFactory spanFactory = spanFactory(config);
                    if (spanFactory == null || !settings(config).eventSinkEnabled()) {
                        return delegate;
                    }
                    return new TracingEventSink(delegate, spanFactory);
                })
        );
        registry.registerDecorator(
                EventHandlingComponent.class,
                TRACING_DECORATOR_ORDER,
                (config, name, delegate) -> {
                    // Only event-handling components owned by an event processor are traced -- not ad-hoc components.
                    Optional<EventProcessorConfiguration> processorConfig = config.getOptionalComponent(
                            EventProcessorConfiguration.class);
                    if (processorConfig.isEmpty()) {
                        return delegate;
                    }
                    SpanFactory spanFactory = spanFactory(config);
                    MessagingTracingSettings settings = settings(config);
                    if (spanFactory == null || !settings.eventProcessorEnabled()) {
                        return delegate;
                    }
                    return new TracingEventHandlingComponent(
                            delegate,
                            spanFactory,
                            processorConfig.get().processorName(),
                            processorConfig.get().streaming(),
                            settings.eventProcessorBatchTraceEnabled(),
                            settings.eventProcessorDistributedInSameTrace(),
                            settings.eventProcessorDistributedInSameTraceTimeLimit()
                    );
                }
        );
        registry.registerDecorator(
                QueryBus.class,
                TRACING_DECORATOR_ORDER,
                (config, name, delegate) -> {
                    SpanFactory spanFactory = spanFactory(config);
                    if (spanFactory == null || !settings(config).queryBusEnabled()) {
                        return delegate;
                    }
                    return new TracingQueryBus(delegate, spanFactory);
                }
        );
    }

    /**
     * Resolves the configured {@link SpanFactory}, or {@code null} when none is configured (tracing disabled). The
     * {@code SpanFactory} is an optional bean contributed only by a tracing backend; when absent the component is left
     * undecorated.
     */
    private static @Nullable SpanFactory spanFactory(Configuration config) {
        return config.getOptionalComponent(SpanFactory.class).orElse(null);
    }

    /**
     * Resolves the {@link MessagingTracingSettings} component. Always present: registered as a default by
     * {@link #enhance(ComponentRegistry)} via {@code registerIfNotPresent}, unless a user-supplied or
     * property-translated registration took precedence.
     */
    private static MessagingTracingSettings settings(Configuration config) {
        return config.getComponent(MessagingTracingSettings.class);
    }

    @Override
    public int order() {
        return TracingConfigurationOrder.TRACING_DEFAULTS_ENHANCER_ORDER;
    }
}
