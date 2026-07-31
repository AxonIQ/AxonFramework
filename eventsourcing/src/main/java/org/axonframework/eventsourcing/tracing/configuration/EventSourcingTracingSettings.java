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

/**
 * Per-component on/off toggles for event-sourcing tracing, read by
 * {@code EventSourcingTracingConfigurationEnhancer} to decide which {@code axon-eventsourcing} components to decorate,
 * and by {@code TracingEventTagsHandlerEnhancerDefinition} to decide whether event handler spans are enriched with the
 * event's tags.
 * <p>
 * When no instance is registered, every toggle defaults to enabled ({@link #enabledByDefault()}). To adjust the
 * toggles declaratively, register an instance as a component -- typically starting from the defaults and using the
 * {@code with*} copy methods:
 * <pre>{@code
 * configurer.componentRegistry(cr -> cr.registerComponent(
 *         EventSourcingTracingSettings.class,
 *         c -> EventSourcingTracingSettings.enabledByDefault().withSnapshotStoreEnabled(false)));
 * }</pre>
 * Higher-level integrations (for example property-based configuration layers) register a translated instance on the
 * application's behalf; an explicitly registered component always takes precedence.
 *
 * @param eventStoreEnabled       whether the {@code EventStorageEngine} is decorated with tracing
 * @param snapshotStoreEnabled    whether the {@code SnapshotStore} is decorated with tracing
 * @param spanAttributesProviders toggles for the built-in span attribute providers contributed by this module
 * @author Mateusz Nowak
 * @since 5.3.0
 */
public record EventSourcingTracingSettings(boolean eventStoreEnabled,
                                           boolean snapshotStoreEnabled,
                                           SpanAttributesProviders spanAttributesProviders) {

    /**
     * Toggles for the built-in
     * {@link org.axonframework.messaging.tracing.SpanAttributesProvider SpanAttributesProviders}
     * contributed by the {@code axon-eventsourcing} module (read by
     * {@code EventSourcingSpanAttributesProviderConfigurationEnhancer} and
     * {@code TracingEventTagsHandlerEnhancerDefinition}).
     *
     * @param eventTagsEnabled whether event tags are recorded as span attributes (both by the
     *                         {@code EventTagsSpanAttributesProvider} on the publish side and by the
     *                         {@code TracingEventTagsHandlerEnhancerDefinition} on the handling side)
     */
    public record SpanAttributesProviders(boolean eventTagsEnabled) {

        /**
         * Returns the default provider toggles, with every built-in provider enabled.
         *
         * @return the all-enabled default provider toggles
         */
        public static SpanAttributesProviders enabledByDefault() {
            return new SpanAttributesProviders(true);
        }
    }

    /**
     * Returns the default settings, with every event-sourcing tracing toggle enabled.
     *
     * @return the all-enabled default settings
     */
    public static EventSourcingTracingSettings enabledByDefault() {
        return new EventSourcingTracingSettings(true, true, SpanAttributesProviders.enabledByDefault());
    }

    /**
     * Convenience accessor for {@link SpanAttributesProviders#eventTagsEnabled()}.
     *
     * @return whether event tags are recorded as span attributes
     */
    public boolean eventTagsEnabled() {
        return spanAttributesProviders.eventTagsEnabled();
    }

    /**
     * Returns a copy of these settings with {@link #eventStoreEnabled()} replaced by the given value.
     *
     * @param eventStoreEnabled whether the {@code EventStorageEngine} is decorated with tracing
     * @return a copy of these settings with the given {@code eventStoreEnabled}
     */
    public EventSourcingTracingSettings withEventStoreEnabled(boolean eventStoreEnabled) {
        return new EventSourcingTracingSettings(eventStoreEnabled, snapshotStoreEnabled, spanAttributesProviders);
    }

    /**
     * Returns a copy of these settings with {@link #snapshotStoreEnabled()} replaced by the given value.
     *
     * @param snapshotStoreEnabled whether the {@code SnapshotStore} is decorated with tracing
     * @return a copy of these settings with the given {@code snapshotStoreEnabled}
     */
    public EventSourcingTracingSettings withSnapshotStoreEnabled(boolean snapshotStoreEnabled) {
        return new EventSourcingTracingSettings(eventStoreEnabled, snapshotStoreEnabled, spanAttributesProviders);
    }

    /**
     * Returns a copy of these settings with {@link #spanAttributesProviders()} replaced by the given value.
     *
     * @param spanAttributesProviders toggles for the built-in span attribute providers contributed by this module
     * @return a copy of these settings with the given {@code spanAttributesProviders}
     */
    public EventSourcingTracingSettings withSpanAttributesProviders(SpanAttributesProviders spanAttributesProviders) {
        return new EventSourcingTracingSettings(eventStoreEnabled, snapshotStoreEnabled, spanAttributesProviders);
    }
}
