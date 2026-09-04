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
package org.axonframework.messaging.core.timeout;

import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.DefaultComponentRegistry;
import org.axonframework.common.configuration.LifecycleRegistry;
import org.axonframework.messaging.core.configuration.MessagingConfigurationDefaults;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorConfiguration;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorCustomization;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Test class validating the {@link TimeoutUnitOfWorkFactoryConfigurationEnhancer}.
 *
 * @author Steven van Beelen
 */
class TimeoutUnitOfWorkFactoryConfigurationEnhancerTest {

    private DefaultComponentRegistry componentRegistry;
    private UnitOfWorkFactory baseUnitOfWorkFactory;

    @BeforeEach
    void setUp() {
        baseUnitOfWorkFactory = mock(UnitOfWorkFactory.class);
        componentRegistry = new DefaultComponentRegistry();
        componentRegistry.disableEnhancerScanning();
        componentRegistry.registerComponent(UnitOfWorkFactory.class, c -> baseUnitOfWorkFactory);
        // Mirrors MessagingConfigurationDefaults's own named CommandBus/QueryBus UnitOfWorkFactory components, which
        // by default simply delegate to the generic, unnamed UnitOfWorkFactory.
        componentRegistry.registerComponent(
                UnitOfWorkFactory.class, MessagingConfigurationDefaults.COMMAND_BUS_UNIT_OF_WORK_FACTORY_NAME,
                c -> c.getComponent(UnitOfWorkFactory.class)
        );
        componentRegistry.registerComponent(
                UnitOfWorkFactory.class, MessagingConfigurationDefaults.QUERY_BUS_UNIT_OF_WORK_FACTORY_NAME,
                c -> c.getComponent(UnitOfWorkFactory.class)
        );
        // Mirrors EventProcessingConfigurer.build()'s own default customization, which assigns the shared
        // UnitOfWorkFactory to every processor's own configuration before any other customization runs.
        componentRegistry.registerComponent(
                EventProcessorCustomization.class,
                c -> EventProcessorCustomization.noOp()
                                                 .andThen((axonConfig, processorConfig) -> processorConfig.unitOfWorkFactory(
                                                         axonConfig.getComponent(UnitOfWorkFactory.class)))
        );
        componentRegistry.registerEnhancer(new TimeoutUnitOfWorkFactoryConfigurationEnhancer());
    }

    @Test
    void decoratesEveryUnitOfWorkFactoryWithDefaultSettingsWhenNothingConfigured() {
        // given / when
        Configuration config = componentRegistry.build(mock(LifecycleRegistry.class));

        // then -- TimeoutUnitOfWorkFactoryConfiguration.DEFAULT is applied automatically, matching Spring Boot's
        // own defaults, so every UnitOfWorkFactory is decorated even without any explicit configuration.
        assertThat(commandBusUnitOfWorkFactory(config)).isInstanceOf(TimeoutUnitOfWorkFactory.class);
        assertThat(queryBusUnitOfWorkFactory(config)).isInstanceOf(TimeoutUnitOfWorkFactory.class);
        assertThat(eventProcessorUnitOfWorkFactory(config, "any-processor")).isInstanceOf(TimeoutUnitOfWorkFactory.class);
    }

    @Test
    void defaultConstantMatchesSpringBootsOwnDefaults() {
        // Guards against silent drift from TimeoutProperties's own hardcoded Spring Boot defaults.
        for (TaskTimeoutSettings settings : List.of(TimeoutUnitOfWorkFactoryConfiguration.DEFAULT.getCommandBus(),
                                                     TimeoutUnitOfWorkFactoryConfiguration.DEFAULT.getQueryBus(),
                                                     TimeoutUnitOfWorkFactoryConfiguration.DEFAULT.getEventProcessors())) {
            assertThat(settings.timeoutMs()).isEqualTo(60_000);
            assertThat(settings.warningThresholdMs()).isEqualTo(10_000);
            assertThat(settings.warningIntervalMs()).isEqualTo(1_000);
        }
        assertThat(TimeoutUnitOfWorkFactoryConfiguration.DEFAULT.getEventProcessor()).isEmpty();
    }

    @Test
    void noUnitOfWorkFactoryIsDecoratedWhenExplicitlyDisabled() {
        // given
        componentRegistry.registerComponent(
                TimeoutUnitOfWorkFactoryConfiguration.class,
                c -> TimeoutUnitOfWorkFactoryConfiguration.DISABLED
        );

        // when
        Configuration config = componentRegistry.build(mock(LifecycleRegistry.class));

        // then
        assertThat(commandBusUnitOfWorkFactory(config)).isSameAs(baseUnitOfWorkFactory);
        assertThat(queryBusUnitOfWorkFactory(config)).isSameAs(baseUnitOfWorkFactory);
        assertThat(eventProcessorUnitOfWorkFactory(config, "any-processor")).isSameAs(baseUnitOfWorkFactory);
    }

    @Test
    void decoratesCommandAndQueryUnitOfWorkFactoriesWhenConfigured() {
        // given
        componentRegistry.registerComponent(
                TimeoutUnitOfWorkFactoryConfiguration.class,
                c -> new TimeoutUnitOfWorkFactoryConfiguration(
                        new TaskTimeoutSettings(100, 50, 10),
                        new TaskTimeoutSettings(100, 50, 10),
                        TaskTimeoutSettings.DISABLED,
                        Map.of()
                )
        );

        // when
        Configuration config = componentRegistry.build(mock(LifecycleRegistry.class));

        // then
        assertThat(commandBusUnitOfWorkFactory(config)).isInstanceOf(TimeoutUnitOfWorkFactory.class);
        assertThat(queryBusUnitOfWorkFactory(config)).isInstanceOf(TimeoutUnitOfWorkFactory.class);
        assertThat(eventProcessorUnitOfWorkFactory(config, "any-processor")).isSameAs(baseUnitOfWorkFactory);
    }

    @Test
    void resolvesPerNamedEventProcessorSettingsIndependently() {
        // given
        componentRegistry.registerComponent(
                TimeoutUnitOfWorkFactoryConfiguration.class,
                c -> new TimeoutUnitOfWorkFactoryConfiguration(
                        TaskTimeoutSettings.DISABLED,
                        TaskTimeoutSettings.DISABLED,
                        TaskTimeoutSettings.DISABLED,
                        Map.of("slow-processor", new TaskTimeoutSettings(100, 50, 10))
                )
        );

        // when
        Configuration config = componentRegistry.build(mock(LifecycleRegistry.class));

        // then
        assertThat(eventProcessorUnitOfWorkFactory(config, "slow-processor")).isInstanceOf(TimeoutUnitOfWorkFactory.class);
        assertThat(eventProcessorUnitOfWorkFactory(config, "fast-processor")).isSameAs(baseUnitOfWorkFactory);
    }

    private static UnitOfWorkFactory commandBusUnitOfWorkFactory(Configuration config) {
        return config.getComponent(UnitOfWorkFactory.class,
                                   MessagingConfigurationDefaults.COMMAND_BUS_UNIT_OF_WORK_FACTORY_NAME);
    }

    private static UnitOfWorkFactory queryBusUnitOfWorkFactory(Configuration config) {
        return config.getComponent(UnitOfWorkFactory.class,
                                   MessagingConfigurationDefaults.QUERY_BUS_UNIT_OF_WORK_FACTORY_NAME);
    }

    private static UnitOfWorkFactory eventProcessorUnitOfWorkFactory(Configuration config, String processorName) {
        EventProcessorConfiguration processorConfiguration =
                config.getComponent(EventProcessorCustomization.class)
                      .apply(config, new EventProcessorConfiguration(processorName, config));
        return processorConfiguration.unitOfWorkFactory();
    }
}
