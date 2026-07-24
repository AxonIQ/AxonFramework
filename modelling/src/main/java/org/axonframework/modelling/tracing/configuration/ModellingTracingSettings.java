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

package org.axonframework.modelling.tracing.configuration;

/**
 * Per-component on/off toggles for modelling tracing, read by {@code ModellingTracingConfigurationEnhancer} to decide
 * which {@code axon-modelling} components to decorate.
 * <p>
 * When no instance is registered, every component defaults to enabled ({@link #enabledByDefault()}). To adjust the
 * toggles declaratively, register an instance as a component -- typically starting from the defaults and using the
 * {@code with*} copy methods:
 * <pre>{@code
 * configurer.componentRegistry(cr -> cr.registerComponent(
 *         ModellingTracingSettings.class,
 *         c -> ModellingTracingSettings.enabledByDefault().withStateManagerEnabled(false)));
 * }</pre>
 * Higher-level integrations (for example property-based configuration layers) register a translated instance on the
 * application's behalf; an explicitly registered component always takes precedence.
 *
 * @param repositoryEnabled       whether the {@code Repository} is decorated with tracing
 * @param stateManagerEnabled     whether the {@code StateManager} is decorated with tracing
 * @param spanAttributesProviders toggles for the built-in span attribute providers contributed by this module
 * @author Mateusz Nowak
 * @since 5.3.0
 */
public record ModellingTracingSettings(boolean repositoryEnabled,
                                       boolean stateManagerEnabled,
                                       SpanAttributesProviders spanAttributesProviders) {

    /**
     * Toggles for the built-in {@link org.axonframework.messaging.tracing.SpanAttributesProvider SpanAttributesProviders}
     * contributed by the {@code axon-modelling} module (read by
     * {@code ModellingSpanAttributesProviderConfigurationEnhancer}).
     *
     * @param aggregateIdentifierEnabled whether the {@code AggregateIdentifierSpanAttributesProvider} is contributed
     */
    public record SpanAttributesProviders(boolean aggregateIdentifierEnabled) {

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
     * Returns the default settings, with every modelling component enabled for tracing and every built-in span
     * attribute provider enabled.
     *
     * @return the all-enabled default settings
     */
    public static ModellingTracingSettings enabledByDefault() {
        return new ModellingTracingSettings(true, true, SpanAttributesProviders.enabledByDefault());
    }

    /**
     * Returns a copy of these settings with {@link #repositoryEnabled()} replaced by the given value.
     *
     * @param repositoryEnabled whether the {@code Repository} is decorated with tracing
     * @return a copy of these settings with the given {@code repositoryEnabled}
     */
    public ModellingTracingSettings withRepositoryEnabled(boolean repositoryEnabled) {
        return new ModellingTracingSettings(repositoryEnabled, stateManagerEnabled, spanAttributesProviders);
    }

    /**
     * Returns a copy of these settings with {@link #stateManagerEnabled()} replaced by the given value.
     *
     * @param stateManagerEnabled whether the {@code StateManager} is decorated with tracing
     * @return a copy of these settings with the given {@code stateManagerEnabled}
     */
    public ModellingTracingSettings withStateManagerEnabled(boolean stateManagerEnabled) {
        return new ModellingTracingSettings(repositoryEnabled, stateManagerEnabled, spanAttributesProviders);
    }

    /**
     * Returns a copy of these settings with {@link #spanAttributesProviders()} replaced by the given value.
     *
     * @param spanAttributesProviders toggles for the built-in span attribute providers contributed by this module
     * @return a copy of these settings with the given {@code spanAttributesProviders}
     */
    public ModellingTracingSettings withSpanAttributesProviders(SpanAttributesProviders spanAttributesProviders) {
        return new ModellingTracingSettings(repositoryEnabled, stateManagerEnabled, spanAttributesProviders);
    }
}
