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

package org.axonframework.modelling.tracing;

import org.axonframework.messaging.tracing.SpanFactory;
import org.axonframework.messaging.tracing.configuration.TracingConfigurationOrder;
import org.axonframework.modelling.repository.tracing.TracingRepository;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.annotation.RegistrationScope;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.modelling.StateManager;
import org.axonframework.modelling.repository.Repository;
import org.jspecify.annotations.Nullable;

/**
 * {@link ConfigurationEnhancer} that wires tracing into {@code axon-modelling} components. Discovered automatically via
 * ServiceLoader.
 * <p>
 * A component is only decorated when a non-no-op {@link SpanFactory} is configured and the corresponding toggle in
 * {@link ModellingTracingSettings} is enabled.
 *
 * @author Mateusz Nowak
 * @since 5.3.0
 */
@Internal
@RegistrationScope("Register decorators once at the root; do not re-invoke in child module registries "
        + "(the DecoratorDefinitions are copied down and reach module-built components on their own). "
        + "Re-invoking per nesting level re-registers the decorators and produces duplicate nested spans.")
public final class ModellingTracingConfigurationEnhancer implements ConfigurationEnhancer {

    /**
     * Decorator order for the modelling tracing decorators. Near-maximal so tracing is applied last and is the
     * <em>outermost</em> wrapper - spans cover all inner decorators, and tracing wrappers are reliably detectable by
     * an outermost {@code instanceof} check (see {@link TracingStateManager#register(Repository)}). Same value and
     * rationale as {@link org.axonframework.messaging.tracing.MessagingTracingConfigurationEnhancer#TRACING_DECORATOR_ORDER}.
     */
    public static final int TRACING_DECORATOR_ORDER = TracingConfigurationOrder.TRACING_DECORATOR_ORDER;

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void enhance(ComponentRegistry registry) {
        registry.registerIfNotPresent(ModellingTracingSettings.class,
                                      c -> ModellingTracingSettings.enabledByDefault());
        registry.registerDecorator(
                Repository.class,
                TRACING_DECORATOR_ORDER,
                (config, name, delegate) -> {
                    // Repository is sealed; only LifecycleManagement implementations are registered.
                    if (!(delegate instanceof Repository.LifecycleManagement<?, ?> lifecycle)) {
                        return delegate;
                    }
                    SpanFactory spanFactory = spanFactory(config);
                    if (spanFactory == null || !settings(config).repositoryEnabled()) {
                        return delegate;
                    }
                    return new TracingRepository(lifecycle, spanFactory);
                }
        );
        registry.registerDecorator(
                StateManager.class,
                TRACING_DECORATOR_ORDER,
                (config, name, delegate) -> {
                    SpanFactory spanFactory = spanFactory(config);
                    if (spanFactory == null || !settings(config).stateManagerEnabled()) {
                        return delegate;
                    }
                    return new TracingStateManager(delegate, spanFactory);
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
     * Resolves the {@link ModellingTracingSettings} component. Always present: registered as a default by
     * {@link #enhance(ComponentRegistry)} via {@code registerIfNotPresent}, unless a user-supplied or
     * property-translated registration took precedence.
     */
    private static ModellingTracingSettings settings(Configuration config) {
        return config.getComponent(ModellingTracingSettings.class);
    }

    @Override
    public int order() {
        return TracingConfigurationOrder.TRACING_DEFAULTS_ENHANCER_ORDER;
    }
}
