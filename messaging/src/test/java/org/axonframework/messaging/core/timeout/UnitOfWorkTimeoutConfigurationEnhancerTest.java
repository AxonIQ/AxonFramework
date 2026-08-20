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
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.core.interception.DefaultHandlerInterceptorRegistry;
import org.axonframework.messaging.core.interception.HandlerInterceptorRegistry;
import org.axonframework.messaging.queryhandling.QueryBus;
import org.junit.jupiter.api.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Test class validating the {@link UnitOfWorkTimeoutConfigurationEnhancer}.
 *
 * @author Steven van Beelen
 */
class UnitOfWorkTimeoutConfigurationEnhancerTest {

    private DefaultComponentRegistry componentRegistry;

    @BeforeEach
    void setUp() {
        componentRegistry = new DefaultComponentRegistry();
        componentRegistry.disableEnhancerScanning();
        componentRegistry.registerComponent(HandlerInterceptorRegistry.class,
                                            c -> new DefaultHandlerInterceptorRegistry());
        componentRegistry.registerEnhancer(new UnitOfWorkTimeoutConfigurationEnhancer());
    }

    @Test
    void noInterceptorsAreRegisteredWithoutConfiguration() {
        // given / when
        Configuration config = componentRegistry.build(mock(LifecycleRegistry.class));
        HandlerInterceptorRegistry registry = config.getComponent(HandlerInterceptorRegistry.class);

        // then
        assertThat(registry.commandInterceptors(config, CommandBus.class, null)).isEmpty();
        assertThat(registry.queryInterceptors(config, QueryBus.class, null)).isEmpty();
        assertThat(registry.eventInterceptors(config, Object.class, "any-processor")).isEmpty();
        assertThat(registry.eventInterceptors(config, Object.class, null)).isEmpty();
    }

    @Test
    void registersCommandAndQueryInterceptorsWhenConfigured() {
        // given
        componentRegistry.registerComponent(
                UnitOfWorkTimeoutConfiguration.class,
                c -> new UnitOfWorkTimeoutConfiguration(
                        new TaskTimeoutSettings(100, 50, 10),
                        new TaskTimeoutSettings(100, 50, 10),
                        new TaskTimeoutSettings(),
                        Map.of()
                )
        );

        // when
        Configuration config = componentRegistry.build(mock(LifecycleRegistry.class));
        HandlerInterceptorRegistry registry = config.getComponent(HandlerInterceptorRegistry.class);

        // then
        assertThat(registry.commandInterceptors(config, CommandBus.class, null)).hasSize(1);
        assertThat(registry.queryInterceptors(config, QueryBus.class, null)).hasSize(1);
        assertThat(registry.eventInterceptors(config, Object.class, "any-processor")).isEmpty();
    }

    @Test
    void resolvesPerNamedEventProcessorSettingsIndependently() {
        // given
        componentRegistry.registerComponent(
                UnitOfWorkTimeoutConfiguration.class,
                c -> new UnitOfWorkTimeoutConfiguration(
                        new TaskTimeoutSettings(),
                        new TaskTimeoutSettings(),
                        new TaskTimeoutSettings(),
                        Map.of("slow-processor", new TaskTimeoutSettings(100, 50, 10))
                )
        );

        // when
        Configuration config = componentRegistry.build(mock(LifecycleRegistry.class));
        HandlerInterceptorRegistry registry = config.getComponent(HandlerInterceptorRegistry.class);

        // then
        assertThat(registry.eventInterceptors(config, Object.class, "slow-processor")).hasSize(1);
        assertThat(registry.eventInterceptors(config, Object.class, "fast-processor")).isEmpty();
    }
}
