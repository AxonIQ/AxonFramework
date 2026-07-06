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

import org.axonframework.messaging.tracing.attributes.MessageIdSpanAttributesProvider;
import org.axonframework.messaging.tracing.attributes.MessageTypeSpanAttributesProvider;
import org.axonframework.messaging.tracing.attributes.MetadataSpanAttributesProvider;
import org.axonframework.messaging.tracing.configuration.SpanAttributesProviderRegistry;
import org.axonframework.messaging.tracing.configuration.TracingConfigurationOrder;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.annotation.RegistrationScope;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.ConfigurationEnhancer;

/**
 * {@link ConfigurationEnhancer} contributing the built-in message-level
 * {@link org.axonframework.messaging.tracing.SpanAttributesProvider SpanAttributesProviders} to the
 * {@link SpanAttributesProviderRegistry}. Discovered automatically via ServiceLoader.
 * <p>
 * Contribution is a decorator on the registry component, so the toggles in
 * {@link MessagingTracingSettings#spanAttributesProviders()} are read when the registry is resolved -- right before
 * the {@link org.axonframework.messaging.tracing.SpanFactory} consuming it is constructed.
 *
 * @author Mateusz Nowak
 * @since 5.2.0
 */
@Internal
@RegistrationScope("Contribute the providers once at the root; do not re-invoke in child module registries. "
        + "The registry decorator is copied down on its own -- re-invoking per nesting level would register the "
        + "same providers multiple times, duplicating span attributes.")
public final class MessagingSpanAttributesProviderConfigurationEnhancer implements ConfigurationEnhancer {

    @Override
    public void enhance(ComponentRegistry registry) {
        registry.registerDecorator(
                SpanAttributesProviderRegistry.class,
                0,
                (config, name, delegate) -> {
                    var providers = config.getComponent(MessagingTracingSettings.class).spanAttributesProviders();
                    if (providers.messageIdEnabled()) {
                        delegate.registerProvider(c -> new MessageIdSpanAttributesProvider());
                    }
                    if (providers.messageTypeEnabled()) {
                        delegate.registerProvider(c -> new MessageTypeSpanAttributesProvider());
                    }
                    if (providers.metadataEnabled()) {
                        delegate.registerProvider(c -> new MetadataSpanAttributesProvider());
                    }
                    return delegate;
                }
        );
    }

    @Override
    public int order() {
        return TracingConfigurationOrder.PROVIDER_ENHANCER_ORDER;
    }
}
