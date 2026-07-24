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

package org.axonframework.messaging.tracing.attributes;

import org.axonframework.common.annotation.Internal;
import org.axonframework.messaging.tracing.SpanAttributesProvider;
import org.axonframework.messaging.tracing.SpanFactory;
import org.axonframework.common.configuration.ComponentBuilder;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.DecoratorDefinition;

import java.util.List;

/**
 * A registry of {@link SpanAttributesProvider SpanAttributesProviders}, collecting the providers a
 * {@link SpanFactory} implementation is constructed with.
 * <p>
 * Provides operations to register providers one by one. Registered providers can be retrieved through
 * {@link #providers(Configuration)} -- typically by the component builder constructing the {@code SpanFactory}, so the
 * factory receives its complete, immutable provider list at construction time.
 * <p>
 * Registration operations are expected to be invoked within a {@link DecoratorDefinition DecoratorDefinition} on this
 * registry component. As such, <b>any</b> registered provider is <b>only</b> applied when the {@code SpanFactory}
 * requiring it is constructed. Providers that are registered once the {@code SpanFactory} has already been constructed
 * are not taken into account.
 * <p>
 * This registry is {@link Internal}: the built-in providers are contributed by the tracing modules' configuration
 * enhancers, driven by the {@code *TracingSettings} components, and applications contribute custom providers through
 * {@link
 * org.axonframework.messaging.core.configuration.MessagingConfigurer#registerSpanAttributesProvider(ComponentBuilder)}
 * rather than interacting with this registry directly.
 *
 * @author Mateusz Nowak
 * @since 5.3.0
 */
@Internal
public interface SpanAttributesProviderRegistry {

    /**
     * Registers the given {@code providerBuilder} constructing a {@link SpanAttributesProvider} to include in the
     * {@link SpanFactory} built from this registry.
     *
     * @param providerBuilder the {@link SpanAttributesProvider} builder to register
     * @return this {@code SpanAttributesProviderRegistry}, for fluent interfacing
     */
    SpanAttributesProviderRegistry registerProvider(ComponentBuilder<SpanAttributesProvider> providerBuilder);

    /**
     * Returns the list of {@link SpanAttributesProvider SpanAttributesProviders} registered in this registry, in
     * registration order.
     *
     * @param config the configuration to build all {@link SpanAttributesProvider SpanAttributesProviders} with
     * @return the list of {@link SpanAttributesProvider SpanAttributesProviders}
     */
    List<SpanAttributesProvider> providers(Configuration config);
}
