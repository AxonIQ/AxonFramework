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

package org.axonframework.eventsourcing.tracing.configuration;

import org.axonframework.messaging.tracing.SpanFactory;
import org.axonframework.messaging.tracing.configuration.TracingConfigurationOrder;
import org.axonframework.eventsourcing.eventstore.tracing.TracingEventStore;
import org.axonframework.eventsourcing.eventstore.tracing.TracingEventStorageEngine;
import org.axonframework.eventsourcing.handler.tracing.annotation.TracingEventTagsHandlerEnhancerDefinition;
import org.axonframework.eventsourcing.snapshot.store.tracing.TracingSnapshotStore;
import org.axonframework.messaging.tracing.configuration.MessagingTracingSettings;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.annotation.RegistrationScope;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.snapshot.store.SnapshotStore;
import org.axonframework.messaging.core.annotation.EnhancingHandlerDefinition;
import org.axonframework.messaging.core.annotation.HandlerDefinition;
import org.jspecify.annotations.Nullable;

/**
 * {@link ConfigurationEnhancer} that wires tracing into {@code axon-eventsourcing} components. Discovered automatically
 * via ServiceLoader, so dropping the {@code axon-eventsourcing} module on the classpath is enough.
 * <p>
 * A component is only decorated when a non-no-op {@link SpanFactory} is configured and the corresponding toggle in
 * {@link EventSourcingTracingSettings} is enabled.
 * <p>
 * Also owns the conditional registration of the {@link TracingEventTagsHandlerEnhancerDefinition}: the
 * {@link HandlerDefinition} component (registered by the {@code axon-messaging} tracing defaults) is decorated with the
 * tag-enriching enhancer only when a {@code SpanFactory} is configured, at
 * {@link TracingConfigurationOrder#EVENT_TAG_HANDLER_ENHANCER_ORDER} -- inside the method-span enhancer, so resolved
 * tags land on the method span when one is active.
 *
 * @author Mateusz Nowak
 * @since 5.3.0
 */
@Internal
@RegistrationScope("Register decorators once at the root; do not re-invoke in child module registries "
        + "(the DecoratorDefinitions are copied down and reach module-built components on their own). "
        + "Re-invoking per nesting level re-registers the decorators and produces duplicate nested spans.")
public final class EventSourcingTracingConfigurationEnhancer implements ConfigurationEnhancer {

    /**
     * Decorator order for the event-sourcing tracing decorators. Near-maximal so tracing is applied last and is the
     * <em>outermost</em> wrapper - spans cover all inner decorators, and tracing wrappers are reliably detectable by
     * an outermost {@code instanceof} check. Same value and rationale as
     * {@link
     * org.axonframework.messaging.tracing.configuration.MessagingTracingConfigurationEnhancer#TRACING_DECORATOR_ORDER}.
     */
    public static final int TRACING_DECORATOR_ORDER = TracingConfigurationOrder.TRACING_DECORATOR_ORDER;

    @Override
    public void enhance(ComponentRegistry registry) {
        registry.registerIfNotPresent(EventSourcingTracingSettings.class,
                                      c -> EventSourcingTracingSettings.enabledByDefault());
        registry.registerDecorator(
                EventStorageEngine.class,
                TRACING_DECORATOR_ORDER,
                (config, name, delegate) -> {
                    SpanFactory spanFactory = spanFactory(config);
                    if (spanFactory == null || !settings(config).eventStoreEnabled()) {
                        return delegate;
                    }
                    return new TracingEventStorageEngine(delegate, spanFactory);
                }
        );
        registry.registerDecorator(
                SnapshotStore.class,
                TRACING_DECORATOR_ORDER,
                (config, name, delegate) -> {
                    SpanFactory spanFactory = spanFactory(config);
                    if (spanFactory == null || !settings(config).snapshotStoreEnabled()) {
                        return delegate;
                    }
                    return new TracingSnapshotStore(delegate, spanFactory);
                }
        );
        // Type-preserving decorator for EventStore: the messaging module's EventSink/EventBus decorators skip the
        // EventStore subtype (see MessagingTracingConfigurationEnhancer#isEventStore), so this enhancer owns the
        // EventStore.class slot and produces an EventStore-typed wrapper that survives the component-registry
        // assignment check.
        registry.registerDecorator(
                EventStore.class,
                TRACING_DECORATOR_ORDER,
                (config, name, delegate) -> {
                    SpanFactory spanFactory = spanFactory(config);
                    // Gated on the same event-sink.enabled toggle as the messaging-side EventSink wrappers: they
                    // trace the same publish path, just on a different slot.
                    if (spanFactory == null || !messagingSettings(config).eventSinkEnabled()) {
                        return delegate;
                    }
                    return new TracingEventStore(delegate, spanFactory);
                }
        );
        // The event-tags handler enhancer only exists in the handler chain when a SpanFactory is configured. Its
        // order keeps it INSIDE the method-span enhancer registered by the axon-messaging tracing defaults, so the
        // tags it resolves are recorded on the method span when one is active.
        registry.registerDecorator(
                HandlerDefinition.class,
                TracingConfigurationOrder.EVENT_TAG_HANDLER_ENHANCER_ORDER,
                (config, name, delegate) -> {
                    if (spanFactory(config) == null) {
                        return delegate;
                    }
                    return new EnhancingHandlerDefinition(delegate, new TracingEventTagsHandlerEnhancerDefinition());
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
     * Resolves the {@link EventSourcingTracingSettings} component. Always present: registered as a default by
     * {@link #enhance(ComponentRegistry)} via {@code registerIfNotPresent}, unless a user-supplied or
     * property-translated registration took precedence.
     */
    private static EventSourcingTracingSettings settings(Configuration config) {
        return config.getComponent(EventSourcingTracingSettings.class);
    }

    @Override
    public int order() {
        return TracingConfigurationOrder.TRACING_DEFAULTS_ENHANCER_ORDER;
    }

    /**
     * Resolves the {@link MessagingTracingSettings} component. Always present: {@code axon-messaging} registers the
     * default via {@code registerIfNotPresent}.
     */
    private static MessagingTracingSettings messagingSettings(Configuration config) {
        return config.getComponent(MessagingTracingSettings.class);
    }
}
