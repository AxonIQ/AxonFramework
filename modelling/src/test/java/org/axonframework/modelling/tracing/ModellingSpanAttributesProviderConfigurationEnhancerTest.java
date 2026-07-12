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

import org.axonframework.messaging.tracing.SpanAttributesProvider;
import org.axonframework.messaging.tracing.attributes.AggregateIdentifierSpanAttributesProvider;
import org.axonframework.messaging.tracing.configuration.SpanAttributesProviderRegistry;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModellingSpanAttributesProviderConfigurationEnhancerTest {

    private static List<SpanAttributesProvider> providers(@Nullable ModellingTracingSettings settings) {
        MessagingConfigurer configurer = MessagingConfigurer.create();
        if (settings != null) {
            configurer.componentRegistry(
                    cr -> cr.registerComponent(ModellingTracingSettings.class, c -> settings));
        }
        Configuration configuration = configurer.build();
        return configuration.getComponent(SpanAttributesProviderRegistry.class).providers(configuration);
    }

    @Nested
    class DefaultSettings {

        @Test
        void contributesTheAggregateIdentifierProviderByDefault() {
            // when the configuration is built without explicit settings (ServiceLoader defaults apply)
            List<SpanAttributesProvider> providers = providers(null);

            // then
            assertThat(providers).anyMatch(p -> p instanceof AggregateIdentifierSpanAttributesProvider);
        }
    }

    @Nested
    class ToggledSettings {

        @Test
        void omitsTheAggregateIdentifierProviderWhenDisabled() {
            // given
            ModellingTracingSettings settings = new ModellingTracingSettings(
                    true, true, new ModellingTracingSettings.SpanAttributesProviders(false));

            // when
            List<SpanAttributesProvider> providers = providers(settings);

            // then
            assertThat(providers).noneMatch(p -> p instanceof AggregateIdentifierSpanAttributesProvider);
        }
    }
}
