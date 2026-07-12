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

package org.axonframework.eventsourcing.tracing;

import org.axonframework.messaging.tracing.SpanFactory;
import org.axonframework.messaging.tracing.configuration.TracingConfigurationOrder;
import org.axonframework.eventsourcing.eventstore.tracing.TracingEventStore;
import org.axonframework.eventsourcing.eventstore.tracing.TracingEventStorageEngine;
import org.axonframework.eventsourcing.snapshot.store.tracing.TracingSnapshotStore;
import org.axonframework.messaging.tracing.MessagingTracingSettings;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.annotation.RegistrationScope;
import org.axonframework.common.configuration.ComponentNotFoundException;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.snapshot.store.SnapshotStore;
import org.jspecify.annotations.Nullable;

/**
 * {@link ConfigurationEnhancer} that wires tracing into {@code axon-eventsourcing} components. Discovered automatically
 * via ServiceLoader, so dropping the {@code axon-eventsourcing} module on the classpath is enough.
 * <p>
 * A component is only decorated when a non-no-op {@link SpanFactory} is configured and the corresponding toggle in
 * {@link EventSourcingTracingSettings} is enabled.
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
     * {@link org.axonframework.messaging.tracing.MessagingTracingConfigurationEnhancer#TRACING_DECORATOR_ORDER}.
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
        // EventStore.class slot and produces an EventStore-typed wrapper that survives AF5's component-registry
        // assignment check.
        registry.registerDecorator(
                EventStore.class,
                TRACING_DECORATOR_ORDER,
                (config, name, delegate) -> {
                    SpanFactory spanFactory = spanFactory(config);
                    if (spanFactory == null || !messagingSettings(config).eventSinkEnabled()) {
                        return delegate;
                    }
                    return new TracingEventStore(delegate, spanFactory);
                }
        );
    }

    /**
     * Resolves the configured {@link SpanFactory}, or {@code null} when none is configured (tracing disabled). The
     * {@code SpanFactory} is an optional bean; when absent {@link Configuration#getComponent(Class)} throws
     * {@link ComponentNotFoundException}, translated to {@code null} so the component is left undecorated.
     */
    private static @Nullable SpanFactory spanFactory(Configuration config) {
        try {
            return config.getComponent(SpanFactory.class);
        } catch (ComponentNotFoundException e) {
            return null;
        }
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
     * The EventStore wrapper is gated on the same {@code event-sink.enabled} toggle as the messaging-side EventSink
     * wrappers - they trace the same publish path, just on a different slot.
     */
    /**
     * Resolves the {@link MessagingTracingSettings} component. Always present: {@code axon-messaging} registers the
     * default via {@code registerIfNotPresent}.
     */
    private static MessagingTracingSettings messagingSettings(Configuration config) {
        return config.getComponent(MessagingTracingSettings.class);
    }
}
