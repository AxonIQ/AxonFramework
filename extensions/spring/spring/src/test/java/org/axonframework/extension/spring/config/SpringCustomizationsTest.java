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

package org.axonframework.extension.spring.config;

import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.messaging.core.SubscribableEventSource;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.messaging.eventhandling.EventBus;
import org.axonframework.messaging.eventhandling.SimpleEventBus;
import org.axonframework.messaging.eventhandling.configuration.EventBusConfigurationDefaults;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorConfiguration;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorConfiguration;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.inmemory.InMemoryTokenStore;
import org.axonframework.messaging.eventhandling.processing.subscribing.SubscribingEventProcessorConfiguration;
import org.axonframework.messaging.eventstreaming.StreamableEventSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.*;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Test class validating how {@link SpringCustomizations} resolve processor components from
 * {@link EventProcessorSettings}.
 *
 * @author Jakob Hatzl
 */
class SpringCustomizationsTest {

    private static final String PROCESSOR_NAME = "test-processor";

    @Nested
    class SubscribingExplicitlyConfiguredSource {

        @Test
        void appliesTheSourceRegisteredUnderTheConfiguredName() {
            // given
            SimpleEventBus namedSource = new SimpleEventBus();
            var configuration = configuration(
                    cr -> cr.registerComponent(SubscribableEventSource.class, "my-source", cfg -> namedSource)
            );

            // when
            var result = customizeSubscribing(configuration, "my-source");

            // then
            assertThat(result.eventSource()).isSameAs(namedSource);
            assertThat(result.unitOfWorkFactory()).isSameAs(configuration.getComponent(UnitOfWorkFactory.class));
        }

        @Test
        void failsWhenTheConfiguredSourceCannotBeResolved() {
            // given - the type-level default EventBus must not satisfy an explicitly configured source name
            var configuration = configuration(cr -> {
            });

            // when / then
            assertThatThrownBy(() -> customizeSubscribing(configuration, "unknown-source"))
                    .isInstanceOf(AxonConfigurationException.class)
                    .hasMessageContaining("'unknown-source'")
                    .hasMessageContaining(PROCESSOR_NAME);
        }
    }

    @Nested
    class SubscribingUnsetSource {

        @Test
        void appliesTheUniqueTypeLevelDefaultWhenTheSourceIsUnset() {
            // given
            var configuration = configuration(cr -> {
            });

            // when
            var result = customizeSubscribing(configuration, null);

            // then - the default EventBus is the unique type-level SubscribableEventSource
            assertThat(result.eventSource()).isSameAs(configuration.getComponent(EventBus.class));
        }

        @Test
        void leavesTheSourceUnsetWhenNoTypeLevelDefaultIsPresent() {
            // given - only a named source is registered, which an unset source setting must not resolve to
            var configuration = configurationWithoutTypeLevelSubscribableSource(
                    cr -> cr.registerComponent(SubscribableEventSource.class,
                                               "named-source",
                                               cfg -> new SimpleEventBus())
            );

            // when
            var result = customizeSubscribing(configuration, null);

            // then - a customization applied after this one, like an EventProcessorDefinition, may supply the source
            assertThat(result.eventSource()).isNull();
        }

        @Test
        void keepsAnAlreadyAssignedSourceWhenNoTypeLevelDefaultIsPresent() {
            // given
            SimpleEventBus assignedSource = new SimpleEventBus();
            var configuration = configurationWithoutTypeLevelSubscribableSource(cr -> {
            });
            var processorConfiguration = subscribingProcessorConfiguration().eventSource(assignedSource);

            // when
            var result = SpringCustomizations
                    .subscribingCustomizations(PROCESSOR_NAME, new TestSubscribingSettings(null))
                    .apply(configuration, processorConfiguration);

            // then
            assertThat(result.eventSource()).isSameAs(assignedSource);
        }

        @Test
        void allowsASubsequentCustomizationToSupplyTheSourceWhenTheSettingIsUnset() {
            // given
            SimpleEventBus definitionSource = new SimpleEventBus();
            var configuration = configurationWithoutTypeLevelSubscribableSource(cr -> {
            });
            // mirrors how settings customizations are chained before definition customizations
            var chained = SpringCustomizations
                    .subscribingCustomizations(PROCESSOR_NAME, new TestSubscribingSettings(null))
                    .andThen((cfg, processorConfig) -> processorConfig.eventSource(definitionSource));

            // when
            var result = chained.apply(configuration, subscribingProcessorConfiguration());

            // then
            assertThat(result.eventSource()).isSameAs(definitionSource);
        }
    }

    @Nested
    class SubscribingEmptySourceName {

        @Test
        void appliesTheUniqueTypeLevelDefaultWhenTheSourceIsEmpty() {
            // given
            var configuration = configuration(cr -> {
            });

            // when
            var result = customizeSubscribing(configuration, "");

            // then
            assertThat(result.eventSource()).isSameAs(configuration.getComponent(EventBus.class));
        }

        @Test
        void leavesTheSourceUnsetWhenTheSourceIsEmptyAndNoTypeLevelDefaultIsPresent() {
            // given
            var configuration = configurationWithoutTypeLevelSubscribableSource(cr -> {
            });

            // when
            var result = customizeSubscribing(configuration, "");

            // then
            assertThat(result.eventSource()).isNull();
        }
    }

    @Nested
    class PooledExplicitlyConfiguredComponents {

        @Test
        void appliesTheSourceAndTokenStoreRegisteredUnderTheConfiguredNames() {
            // given
            StreamableEventSource namedSource = mock(StreamableEventSource.class);
            TokenStore namedTokenStore = new InMemoryTokenStore();
            var configuration = configuration(cr -> cr
                    .registerComponent(StreamableEventSource.class, "my-source", cfg -> namedSource)
                    .registerComponent(TokenStore.class, "my-token-store", cfg -> namedTokenStore)
            );

            // when
            var result = customizePooled(configuration, "my-source", "my-token-store");

            // then
            assertThat(result.eventSource()).isSameAs(namedSource);
            assertThat(result.tokenStore()).isSameAs(namedTokenStore);
            assertThat(result.unitOfWorkFactory()).isSameAs(configuration.getComponent(UnitOfWorkFactory.class));
        }

        @Test
        void failsWhenTheConfiguredSourceCannotBeResolved() {
            // given
            var configuration = configuration(cr -> cr.registerComponent(TokenStore.class,
                                                                         "tokenStore",
                                                                         cfg -> new InMemoryTokenStore()));

            // when / then
            assertThatThrownBy(() -> customizePooled(configuration, "unknown-source", "tokenStore"))
                    .isInstanceOf(AxonConfigurationException.class)
                    .hasMessageContaining("'unknown-source'")
                    .hasMessageContaining(PROCESSOR_NAME);
        }

        @Test
        void failsWhenTheConfiguredTokenStoreCannotBeResolved() {
            // given
            StreamableEventSource namedSource = mock(StreamableEventSource.class);
            var configuration = configuration(cr -> cr.registerComponent(StreamableEventSource.class,
                                                                         "my-source",
                                                                         cfg -> namedSource));

            // when / then
            assertThatThrownBy(() -> customizePooled(configuration, "my-source", "unknown-token-store"))
                    .isInstanceOf(AxonConfigurationException.class)
                    .hasMessageContaining("'unknown-token-store'")
                    .hasMessageContaining(PROCESSOR_NAME);
        }
    }

    @Nested
    class PooledUnsetComponents {

        @Test
        void appliesUniqueTypeLevelDefaultsWhenSourceAndTokenStoreAreUnset() {
            // given
            StreamableEventSource typeLevelSource = mock(StreamableEventSource.class);
            TokenStore typeLevelTokenStore = new InMemoryTokenStore();
            var configuration = configuration(cr -> cr
                    .registerComponent(StreamableEventSource.class, cfg -> typeLevelSource)
                    .registerComponent(TokenStore.class, cfg -> typeLevelTokenStore)
            );

            // when
            var result = customizePooled(configuration, null, null);

            // then
            assertThat(result.eventSource()).isSameAs(typeLevelSource);
            assertThat(result.tokenStore()).isSameAs(typeLevelTokenStore);
        }

        @Test
        void prefersTheConventionallyNamedTokenStoreOverAnAmbiguousTypeLevelLookup() {
            // given - two token stores, one of them under the conventional bean name
            StreamableEventSource typeLevelSource = mock(StreamableEventSource.class);
            TokenStore conventionalTokenStore = new InMemoryTokenStore();
            var configuration = configuration(cr -> cr
                    .registerComponent(StreamableEventSource.class, cfg -> typeLevelSource)
                    .registerComponent(TokenStore.class, "tokenStore", cfg -> conventionalTokenStore)
                    .registerComponent(TokenStore.class, "other-token-store", cfg -> new InMemoryTokenStore())
            );

            // when
            var result = customizePooled(configuration, null, null);

            // then
            assertThat(result.tokenStore()).isSameAs(conventionalTokenStore);
        }

        @Test
        void leavesSourceAndTokenStoreUnsetWhenNoTypeLevelDefaultsArePresent() {
            // given - only named components are registered
            var configuration = configuration(cr -> cr
                    .registerComponent(StreamableEventSource.class, "named-source", cfg -> mock(StreamableEventSource.class))
                    .registerComponent(TokenStore.class, "named-token-store", cfg -> new InMemoryTokenStore())
            );

            // when
            var result = customizePooled(configuration, null, null);

            // then
            assertThat(result.eventSource()).isNull();
            assertThat(result.tokenStore()).isNull();
        }

        @Test
        void allowsASubsequentCustomizationToSupplySourceAndTokenStoreWhenSettingsAreUnset() {
            // given
            StreamableEventSource definitionSource = mock(StreamableEventSource.class);
            TokenStore definitionTokenStore = new InMemoryTokenStore();
            var configuration = configuration(cr -> {
            });
            var chained = SpringCustomizations
                    .pooledStreamingCustomizations(PROCESSOR_NAME, new TestPooledSettings(null, null))
                    .andThen((cfg, processorConfig) -> processorConfig.eventSource(definitionSource)
                                                                      .tokenStore(definitionTokenStore));

            // when
            var result = chained.apply(configuration, pooledProcessorConfiguration());

            // then
            assertThat(result.eventSource()).isSameAs(definitionSource);
            assertThat(result.tokenStore()).isSameAs(definitionTokenStore);
        }
    }

    @Nested
    class PooledEmptyComponentNames {

        @Test
        void treatsEmptySourceAndTokenStoreNamesAsUnset() {
            // given
            var configuration = configuration(cr -> cr
                    .registerComponent(StreamableEventSource.class, "named-source", cfg -> mock(StreamableEventSource.class))
                    .registerComponent(TokenStore.class, "named-token-store", cfg -> new InMemoryTokenStore())
            );

            // when
            var result = customizePooled(configuration, "", "");

            // then
            assertThat(result.eventSource()).isNull();
            assertThat(result.tokenStore()).isNull();
        }
    }

    private static AxonConfiguration configuration(Consumer<ComponentRegistry> components) {
        MessagingConfigurer configurer = MessagingConfigurer.create();
        // classpath enhancers, like the event sourcing defaults, would register additional event sources
        configurer.componentRegistry(ComponentRegistry::disableEnhancerScanning);
        configurer.componentRegistry(components);
        return configurer.build();
    }

    private static AxonConfiguration configurationWithoutTypeLevelSubscribableSource(
            Consumer<ComponentRegistry> components
    ) {
        MessagingConfigurer configurer = MessagingConfigurer.create();
        // without the default EventBus and classpath enhancers no type-level SubscribableEventSource is present
        configurer.componentRegistry(cr -> cr.disableEnhancerScanning()
                                             .disableEnhancer(EventBusConfigurationDefaults.class));
        configurer.componentRegistry(components);
        return configurer.build();
    }

    private static SubscribingEventProcessorConfiguration customizeSubscribing(Configuration configuration,
                                                                               @Nullable String source) {
        return SpringCustomizations
                .subscribingCustomizations(PROCESSOR_NAME, new TestSubscribingSettings(source))
                .apply(configuration, subscribingProcessorConfiguration());
    }

    private static PooledStreamingEventProcessorConfiguration customizePooled(Configuration configuration,
                                                                              @Nullable String source,
                                                                              @Nullable String tokenStore) {
        return SpringCustomizations
                .pooledStreamingCustomizations(PROCESSOR_NAME, new TestPooledSettings(source, tokenStore))
                .apply(configuration, pooledProcessorConfiguration());
    }

    private static SubscribingEventProcessorConfiguration subscribingProcessorConfiguration() {
        return new SubscribingEventProcessorConfiguration(new EventProcessorConfiguration(PROCESSOR_NAME, null));
    }

    private static PooledStreamingEventProcessorConfiguration pooledProcessorConfiguration() {
        return new PooledStreamingEventProcessorConfiguration(new EventProcessorConfiguration(PROCESSOR_NAME, null));
    }

    private record TestSubscribingSettings(@Nullable String source)
            implements EventProcessorSettings.SubscribingEventProcessorSettings {

    }

    private record TestPooledSettings(@Nullable String source, @Nullable String tokenStore)
            implements EventProcessorSettings.PooledEventProcessorSettings {

        @Override
        public int initialSegmentCount() {
            return 1;
        }

        @Override
        public long tokenClaimIntervalInMillis() {
            return 5000;
        }

        @Override
        public int threadCount() {
            return 1;
        }

        @Override
        public int batchSize() {
            return 1;
        }
    }
}
