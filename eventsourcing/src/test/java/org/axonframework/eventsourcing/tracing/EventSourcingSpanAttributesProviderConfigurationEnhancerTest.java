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

package org.axonframework.eventsourcing.tracing;

import org.axonframework.messaging.tracing.SpanAttributesProvider;
import org.axonframework.messaging.tracing.configuration.SpanAttributesProviderRegistry;
import org.axonframework.eventsourcing.eventstore.tracing.EventTagsSpanAttributesProvider;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.eventsourcing.eventstore.TagResolver;
import org.axonframework.messaging.eventstreaming.Tag;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EventSourcingSpanAttributesProviderConfigurationEnhancerTest {

    private static final TagResolver TAG_RESOLVER = event -> Set.of(Tag.of("Army", "army-42"));

    private static List<SpanAttributesProvider> providers(@Nullable TagResolver tagResolver,
                                                          @Nullable EventSourcingTracingSettings settings) {
        MessagingConfigurer configurer = MessagingConfigurer.create();
        configurer.componentRegistry(cr -> {
            if (tagResolver != null) {
                cr.registerComponent(TagResolver.class, c -> tagResolver);
            }
            if (settings != null) {
                cr.registerComponent(EventSourcingTracingSettings.class, c -> settings);
            }
        });
        Configuration configuration = configurer.build();
        return configuration.getComponent(SpanAttributesProviderRegistry.class).providers(configuration);
    }

    @Nested
    class WithTagResolver {

        @Test
        void contributesTheEventTagsProviderByDefault() {
            // when a TagResolver component is configured and settings default to enabled
            List<SpanAttributesProvider> providers = providers(TAG_RESOLVER, null);

            // then
            assertThat(providers).anyMatch(p -> p instanceof EventTagsSpanAttributesProvider);
        }

        @Test
        void omitsTheEventTagsProviderWhenDisabledViaSettings() {
            // given
            EventSourcingTracingSettings settings = new EventSourcingTracingSettings(
                    true, new EventSourcingTracingSettings.SpanAttributesProviders(false));

            // when
            List<SpanAttributesProvider> providers = providers(TAG_RESOLVER, settings);

            // then
            assertThat(providers).noneMatch(p -> p instanceof EventTagsSpanAttributesProvider);
        }
    }

    @Nested
    class WithoutExplicitTagResolver {

        @Test
        void contributesUsingTheFrameworkDefaultTagResolver() {
            // when no TagResolver is registered explicitly, axon-eventsourcing's configuration defaults provide one
            List<SpanAttributesProvider> providers = providers(null, null);

            // then the provider is contributed against the framework-default resolver
            assertThat(providers).anyMatch(p -> p instanceof EventTagsSpanAttributesProvider);
        }
    }
}
