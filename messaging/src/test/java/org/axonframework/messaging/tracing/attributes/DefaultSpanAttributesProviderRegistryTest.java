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
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.infra.MockComponentDescriptor;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultSpanAttributesProviderRegistryTest {

    private static final SpanAttributesProvider PROVIDER_A = (message, context) -> Map.of("a", "1");
    private static final SpanAttributesProvider PROVIDER_B = (message, context) -> Map.of("b", "2");

    private final DefaultSpanAttributesProviderRegistry testSubject = new DefaultSpanAttributesProviderRegistry();

    private static Configuration configuration() {
        return MessagingConfigurer.create().build();
    }

    @Nested
    class RegisterProvider {

        @Test
        void providersAreResolvedInRegistrationOrder() {
            // given
            testSubject.registerProvider(c -> PROVIDER_A)
                       .registerProvider(c -> PROVIDER_B);

            // when
            List<SpanAttributesProvider> providers = testSubject.providers(configuration());

            // then
            assertThat(providers).containsExactly(PROVIDER_A, PROVIDER_B);
        }

        @Test
        void returnsItselfForFluentInterfacing() {
            // when
            SpanAttributesProviderRegistry result = testSubject.registerProvider(c -> PROVIDER_A);

            // then
            assertThat(result).isSameAs(testSubject);
        }

        @Test
        void builderIsOnlyInvokedWhenProvidersAreResolved() {
            // given
            AtomicInteger builderInvocations = new AtomicInteger();
            testSubject.registerProvider(c -> {
                builderInvocations.incrementAndGet();
                return PROVIDER_A;
            });

            // then registration alone does not build the provider
            assertThat(builderInvocations).hasValue(0);

            // when
            testSubject.providers(configuration());

            // then
            assertThat(builderInvocations).hasValue(1);
        }

        @Test
        void builderCanResolveComponentsFromTheConfiguration() {
            // given a provider depending on a configured component
            testSubject.registerProvider(c -> c.getComponent(SpanAttributesProvider.class));
            AxonConfiguration configuration = MessagingConfigurer.create()
                    .componentRegistry(cr -> cr.registerComponent(SpanAttributesProvider.class, c -> PROVIDER_B))
                    .build();

            // when
            List<SpanAttributesProvider> providers = testSubject.providers(configuration);

            // then
            assertThat(providers).containsExactly(PROVIDER_B);
        }
    }

    @Nested
    class ConfigurationDefaultsAndContribution {

        @Test
        void serviceLoaderDefaultsRegisterTheRegistryComponent() {
            // when a configuration is built without any explicit tracing registration
            Configuration configuration = configuration();

            // then the TracingConfigurationDefaults enhancer contributed the default registry
            assertThat(configuration.getComponent(SpanAttributesProviderRegistry.class))
                    .isInstanceOf(DefaultSpanAttributesProviderRegistry.class);
        }

        @Test
        void defaultsDoNotOverrideAPreRegisteredRegistry() {
            // given a user-registered registry
            SpanAttributesProviderRegistry custom = new DefaultSpanAttributesProviderRegistry();
            Configuration configuration = MessagingConfigurer.create()
                    .componentRegistry(cr -> cr.registerComponent(SpanAttributesProviderRegistry.class, c -> custom))
                    .build();

            // then registerIfNotPresent left the user registry in place
            assertThat(configuration.getComponent(SpanAttributesProviderRegistry.class)).isSameAs(custom);
        }

        @Test
        void registryHelperContributesAProviderToTheRegistryComponent() {
            // given a provider contributed through the registry helper
            Configuration configuration = MessagingConfigurer.create()
                    .componentRegistry(cr -> SpanAttributesProviderRegistry.register(cr, c -> PROVIDER_A))
                    .build();

            // when
            List<SpanAttributesProvider> providers =
                    configuration.getComponent(SpanAttributesProviderRegistry.class).providers(configuration);

            // then
            assertThat(providers).contains(PROVIDER_A);
        }
    }

    @Nested
    class DescribeTo {

        @Test
        void describesRegisteredProviderDefinitions() {
            // given two registered providers
            testSubject.registerProvider(c -> PROVIDER_A)
                       .registerProvider(c -> PROVIDER_B);
            MockComponentDescriptor descriptor = new MockComponentDescriptor();

            // when
            testSubject.describeTo(descriptor);

            // then the registry exposes its provider definitions
            Collection<?> providerDefinitions = descriptor.getProperty("providerDefinitions");
            assertThat(providerDefinitions).hasSize(2);
        }

        @Test
        void describesAnEmptyRegistry() {
            // given no registered providers
            MockComponentDescriptor descriptor = new MockComponentDescriptor();

            // when
            testSubject.describeTo(descriptor);

            // then the provider definitions are described as an empty collection
            Collection<?> providerDefinitions = descriptor.getProperty("providerDefinitions");
            assertThat(providerDefinitions).isEmpty();
        }
    }
}
