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

import org.axonframework.messaging.tracing.SpanAttributesProvider;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.configuration.ComponentBuilder;
import org.axonframework.common.configuration.ComponentDefinition;
import org.axonframework.common.configuration.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Default implementation of the {@link SpanAttributesProviderRegistry}, maintaining a list of
 * {@link SpanAttributesProvider} builders resolved on {@link #providers(Configuration)}.
 * <p>
 * Registered as the default {@code SpanAttributesProviderRegistry} component by
 * {@link TracingConfigurationDefaults}.
 *
 * @author Mateusz Nowak
 * @since 5.3.0
 */
@Internal
public final class DefaultSpanAttributesProviderRegistry implements SpanAttributesProviderRegistry {

    private final List<ComponentDefinition<SpanAttributesProvider>> providerDefinitions = new ArrayList<>();

    @Override
    public SpanAttributesProviderRegistry registerProvider(ComponentBuilder<SpanAttributesProvider> providerBuilder) {
        providerDefinitions.add(ComponentDefinition.ofType(SpanAttributesProvider.class)
                                                   .withBuilder(providerBuilder));
        return this;
    }

    @Override
    public List<SpanAttributesProvider> providers(Configuration config) {
        List<SpanAttributesProvider> providers = new ArrayList<>();
        for (ComponentDefinition<SpanAttributesProvider> providerDefinition : providerDefinitions) {
            if (!(providerDefinition instanceof ComponentDefinition.ComponentCreator<SpanAttributesProvider> creator)) {
                // The compiler should avoid this from happening.
                throw new IllegalArgumentException("Unsupported component definition type: " + providerDefinition);
            }
            providers.add(creator.createComponent().resolve(config));
        }
        return providers;
    }
}
