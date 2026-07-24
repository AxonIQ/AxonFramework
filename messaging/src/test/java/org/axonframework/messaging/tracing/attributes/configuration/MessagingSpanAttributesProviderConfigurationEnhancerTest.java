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

package org.axonframework.messaging.tracing.attributes.configuration;

import org.axonframework.messaging.tracing.attributes.MessageIdSpanAttributesProvider;
import org.axonframework.messaging.tracing.attributes.MessageTypeSpanAttributesProvider;
import org.axonframework.messaging.tracing.attributes.MetadataSpanAttributesProvider;
import org.axonframework.messaging.tracing.attributes.SpanAttributesProviderRegistry;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.messaging.tracing.SpanAttributesProvider;
import org.axonframework.messaging.tracing.configuration.MessagingTracingSettings;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MessagingSpanAttributesProviderConfigurationEnhancerTest {

    private static List<SpanAttributesProvider> providers(@Nullable MessagingTracingSettings settings) {
        MessagingConfigurer configurer = MessagingConfigurer.create();
        if (settings != null) {
            configurer.componentRegistry(
                    cr -> cr.registerComponent(MessagingTracingSettings.class, c -> settings));
        }
        Configuration configuration = configurer.build();
        return configuration.getComponent(SpanAttributesProviderRegistry.class).providers(configuration);
    }

    private static MessagingTracingSettings settingsWithProviders(boolean messageId,
                                                                  boolean messageType,
                                                                  boolean metadata) {
        return new MessagingTracingSettings(
                true, true, true, false, false,
                Duration.ofMinutes(2), true, false,
                new MessagingTracingSettings.SpanAttributesProviders(messageId, messageType, metadata));
    }

    @Nested
    class DefaultSettings {

        @Test
        void contributesAllBuiltInProvidersByDefault() {
            // when the configuration is built without explicit settings (ServiceLoader defaults apply)
            List<SpanAttributesProvider> providers = providers(null);

            // then
            assertThat(providers).anyMatch(p -> p instanceof MessageIdSpanAttributesProvider)
                                 .anyMatch(p -> p instanceof MessageTypeSpanAttributesProvider)
                                 .anyMatch(p -> p instanceof MetadataSpanAttributesProvider);
        }
    }

    @Nested
    class ToggledSettings {

        @Test
        void omitsMessageIdProviderWhenDisabled() {
            // when
            List<SpanAttributesProvider> providers = providers(settingsWithProviders(false, true, true));

            // then
            assertThat(providers).noneMatch(p -> p instanceof MessageIdSpanAttributesProvider)
                                 .anyMatch(p -> p instanceof MessageTypeSpanAttributesProvider)
                                 .anyMatch(p -> p instanceof MetadataSpanAttributesProvider);
        }

        @Test
        void omitsMessageTypeProviderWhenDisabled() {
            // when
            List<SpanAttributesProvider> providers = providers(settingsWithProviders(true, false, true));

            // then
            assertThat(providers).noneMatch(p -> p instanceof MessageTypeSpanAttributesProvider)
                                 .anyMatch(p -> p instanceof MessageIdSpanAttributesProvider);
        }

        @Test
        void omitsMetadataProviderWhenDisabled() {
            // when
            List<SpanAttributesProvider> providers = providers(settingsWithProviders(true, true, false));

            // then
            assertThat(providers).noneMatch(p -> p instanceof MetadataSpanAttributesProvider)
                                 .anyMatch(p -> p instanceof MessageIdSpanAttributesProvider);
        }

        @Test
        void contributesNothingWhenAllProvidersDisabled() {
            // when
            List<SpanAttributesProvider> providers = providers(settingsWithProviders(false, false, false));

            // then none of this module's providers are contributed
            assertThat(providers).noneMatch(p -> p instanceof MessageIdSpanAttributesProvider)
                                 .noneMatch(p -> p instanceof MessageTypeSpanAttributesProvider)
                                 .noneMatch(p -> p instanceof MetadataSpanAttributesProvider);
        }
    }
}
