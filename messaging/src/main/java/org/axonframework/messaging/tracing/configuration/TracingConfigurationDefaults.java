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

import org.axonframework.common.annotation.Internal;
import org.axonframework.common.annotation.RegistrationScope;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.messaging.core.annotation.ClasspathHandlerDefinition;
import org.axonframework.messaging.core.annotation.EnhancingHandlerDefinition;
import org.axonframework.messaging.core.annotation.HandlerDefinition;
import org.axonframework.messaging.tracing.SpanFactory;
import org.axonframework.messaging.tracing.TracingHandlerEnhancerDefinition;

/**
 * {@link ConfigurationEnhancer} registering the tracing defaults of the {@code axon-messaging} module. Discovered
 * automatically via ServiceLoader.
 * <p>
 * Registers the {@link SpanAttributesProviderRegistry} component (a {@link DefaultSpanAttributesProviderRegistry})
 * when none is present. Runs at {@link TracingConfigurationOrder#TRACING_DEFAULTS_ENHANCER_ORDER} so user-supplied
 * registrations take precedence.
 * <p>
 * Additionally owns the conditional registration of the per-method tracing handler enhancer: a
 * {@link HandlerDefinition} component is registered when none is present (the same classpath-scanning default the
 * handling modules fall back to), and it is decorated with the {@link TracingHandlerEnhancerDefinition} <em>only</em>
 * when a {@link SpanFactory} component is configured. Without a {@code SpanFactory}, the handler chain is left
 * completely untouched -- no handler is wrapped and tracing imposes no cost.
 *
 * @author Mateusz Nowak
 * @since 5.3.0
 */
@Internal
@RegistrationScope("Register the registry default once at the root; do not re-invoke in child module registries. "
        + "The component is resolved through the parent chain, and re-registering per nesting level would give each "
        + "module its own registry, splitting the provider contributions.")
public final class TracingConfigurationDefaults implements ConfigurationEnhancer {

    @Override
    public void enhance(ComponentRegistry registry) {
        registry.registerIfNotPresent(SpanAttributesProviderRegistry.class,
                                      c -> new DefaultSpanAttributesProviderRegistry());
        registry.registerIfNotPresent(HandlerDefinition.class,
                                      c -> ClasspathHandlerDefinition.forClass(c.getClass()));
        registry.registerDecorator(
                HandlerDefinition.class,
                TracingConfigurationOrder.METHOD_SPAN_HANDLER_ENHANCER_ORDER,
                (config, name, delegate) -> {
                    if (config.getOptionalComponent(SpanFactory.class).isEmpty()) {
                        return delegate;
                    }
                    return new EnhancingHandlerDefinition(delegate, new TracingHandlerEnhancerDefinition());
                }
        );
    }

    @Override
    public int order() {
        return TracingConfigurationOrder.TRACING_DEFAULTS_ENHANCER_ORDER;
    }
}
