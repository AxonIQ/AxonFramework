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
import org.axonframework.messaging.eventhandling.processing.subscribing.SubscribingEventProcessorConfiguration;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.*;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test class validating how the {@link SpringCustomizations.SpringSubscribingEventProcessingModuleCustomization}
 * resolves the {@link SubscribableEventSource} from the
 * {@link EventProcessorSettings.SubscribingEventProcessorSettings#source() source setting}.
 *
 * @author Jakob Hatzl
 */
class SpringCustomizationsTest {

    private static final String PROCESSOR_NAME = "test-processor";

    @Nested
    class ExplicitlyConfiguredSource {

        @Test
        void appliesTheSourceRegisteredUnderTheConfiguredName() {
            // given
            SimpleEventBus namedSource = new SimpleEventBus();
            var configuration = configuration(
                    cr -> cr.registerComponent(SubscribableEventSource.class, "my-source", cfg -> namedSource)
            );

            // when
            var result = customize(configuration, "my-source");

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
            assertThatThrownBy(() -> customize(configuration, "unknown-source"))
                    .isInstanceOf(AxonConfigurationException.class)
                    .hasMessageContaining("'unknown-source'")
                    .hasMessageContaining(PROCESSOR_NAME);
        }
    }

    @Nested
    class UnsetSource {

        @Test
        void appliesTheUniqueTypeLevelDefaultWhenTheSourceIsUnset() {
            // given
            var configuration = configuration(cr -> {
            });

            // when
            var result = customize(configuration, null);

            // then - the default EventBus is the unique type-level SubscribableEventSource
            assertThat(result.eventSource()).isSameAs(configuration.getComponent(EventBus.class));
        }

        @Test
        void leavesTheSourceUnsetWhenNoTypeLevelDefaultIsPresent() {
            // given - only a named source is registered, which an unset source setting must not resolve to
            var configuration = configurationWithoutTypeLevelSource(
                    cr -> cr.registerComponent(SubscribableEventSource.class,
                                               "named-source",
                                               cfg -> new SimpleEventBus())
            );

            // when
            var result = customize(configuration, null);

            // then - a customization applied after this one, like an EventProcessorDefinition, may supply the source
            assertThat(result.eventSource()).isNull();
        }

        @Test
        void keepsAnAlreadyAssignedSourceWhenNoTypeLevelDefaultIsPresent() {
            // given
            SimpleEventBus assignedSource = new SimpleEventBus();
            var configuration = configurationWithoutTypeLevelSource(cr -> {
            });
            var processorConfiguration = processorConfiguration().eventSource(assignedSource);

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
            var configuration = configurationWithoutTypeLevelSource(cr -> {
            });
            // mirrors how settings customizations are chained before definition customizations
            var chained = SpringCustomizations
                    .subscribingCustomizations(PROCESSOR_NAME, new TestSubscribingSettings(null))
                    .andThen((cfg, processorConfig) -> processorConfig.eventSource(definitionSource));

            // when
            var result = chained.apply(configuration, processorConfiguration());

            // then
            assertThat(result.eventSource()).isSameAs(definitionSource);
        }
    }

    @Nested
    class EmptySourceName {

        @Test
        void appliesTheUniqueTypeLevelDefaultWhenTheSourceIsEmpty() {
            // given
            var configuration = configuration(cr -> {
            });

            // when
            var result = customize(configuration, "");

            // then
            assertThat(result.eventSource()).isSameAs(configuration.getComponent(EventBus.class));
        }

        @Test
        void leavesTheSourceUnsetWhenTheSourceIsEmptyAndNoTypeLevelDefaultIsPresent() {
            // given
            var configuration = configurationWithoutTypeLevelSource(cr -> {
            });

            // when
            var result = customize(configuration, "");

            // then
            assertThat(result.eventSource()).isNull();
        }
    }

    private static AxonConfiguration configuration(Consumer<ComponentRegistry> components) {
        MessagingConfigurer configurer = MessagingConfigurer.create();
        // classpath enhancers, like the event sourcing defaults, would register additional event sources
        configurer.componentRegistry(ComponentRegistry::disableEnhancerScanning);
        configurer.componentRegistry(components);
        return configurer.build();
    }

    private static AxonConfiguration configurationWithoutTypeLevelSource(Consumer<ComponentRegistry> components) {
        MessagingConfigurer configurer = MessagingConfigurer.create();
        // without the default EventBus and classpath enhancers no type-level SubscribableEventSource is present
        configurer.componentRegistry(cr -> cr.disableEnhancerScanning()
                                             .disableEnhancer(EventBusConfigurationDefaults.class));
        configurer.componentRegistry(components);
        return configurer.build();
    }

    private static SubscribingEventProcessorConfiguration customize(Configuration configuration,
                                                                    @Nullable String source) {
        return SpringCustomizations
                .subscribingCustomizations(PROCESSOR_NAME, new TestSubscribingSettings(source))
                .apply(configuration, processorConfiguration());
    }

    private static SubscribingEventProcessorConfiguration processorConfiguration() {
        return new SubscribingEventProcessorConfiguration(new EventProcessorConfiguration(PROCESSOR_NAME, null));
    }

    private record TestSubscribingSettings(@Nullable String source)
            implements EventProcessorSettings.SubscribingEventProcessorSettings {

    }
}
