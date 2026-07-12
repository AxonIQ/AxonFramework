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

import org.axonframework.messaging.tracing.SpanAttributesProvider;
import org.axonframework.messaging.tracing.SpanFactory;
import org.axonframework.common.configuration.ComponentBuilder;
import org.axonframework.common.configuration.ComponentRegistry;
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
 * Registration operations are expected to be invoked within a
 * {@link DecoratorDefinition DecoratorDefinition} on this registry component
 * (see {@link #register(ComponentRegistry, ComponentBuilder)}). As such, <b>any</b> registered provider is
 * <b>only</b> applied when the {@code SpanFactory} requiring it is constructed. Providers that are registered once
 * the {@code SpanFactory} has already been constructed are not taken into account.
 * <p>
 * The built-in providers are contributed by the tracing modules' configuration enhancers, driven by the
 * {@code *TracingSettings} components. Applications contribute custom providers through
 * {@link #register(ComponentRegistry, ComponentBuilder)} (plain Java) or by declaring a
 * {@code SpanAttributesProvider} bean (Spring Boot).
 *
 * @author Mateusz Nowak
 * @since 5.3.0
 */
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

    /**
     * Contributes a custom {@link SpanAttributesProvider} to the registry component in the given
     * {@code componentRegistry}.
     * <p>
     * This is the canonical contribution idiom: a decorator on the {@code SpanAttributesProviderRegistry} component,
     * applied when the registry is resolved -- before the {@link SpanFactory} consuming it is constructed. Example:
     * <pre>{@code
     * configurer.componentRegistry(cr -> SpanAttributesProviderRegistry.register(
     *         cr, config -> new TenantSpanAttributesProvider()));
     * }</pre>
     *
     * @param componentRegistry the component registry to contribute the provider to
     * @param providerBuilder   the {@link SpanAttributesProvider} builder to register
     */
    static void register(ComponentRegistry componentRegistry,
                         ComponentBuilder<SpanAttributesProvider> providerBuilder) {
        componentRegistry.registerDecorator(
                SpanAttributesProviderRegistry.class,
                0,
                (config, name, delegate) -> delegate.registerProvider(providerBuilder)
        );
    }
}
