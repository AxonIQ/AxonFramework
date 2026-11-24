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
package org.axonframework.extension.metrics.micrometer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.DefaultComponentRegistry;
import org.axonframework.common.configuration.StubLifecycleRegistry;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating the {@link MicrometerMetricsConfigurationEnhancer}.
 *
 * @author Steven van Beelen
 */
class MicrometerMetricsConfigurationEnhancerTest {

    @Test
    void registersDefaultConfigurationWithDimensions() {
        // given
        DefaultComponentRegistry registry = new DefaultComponentRegistry().disableEnhancerScanning();
        registry.registerEnhancer(new MicrometerMetricsConfigurationEnhancer());

        // when
        Configuration config = registry.build(new StubLifecycleRegistry());

        // then
        MicrometerMetricsConfiguration result = config.getComponent(MicrometerMetricsConfiguration.class);
        assertThat(result.registry()).isInstanceOf(SimpleMeterRegistry.class);
        assertThat(result.useDimensions()).isTrue();
    }

    @Test
    void keepsAlreadyRegisteredConfiguration() {
        // given
        MicrometerMetricsConfiguration custom = new MicrometerMetricsConfiguration(new SimpleMeterRegistry(), false);
        DefaultComponentRegistry registry = new DefaultComponentRegistry().disableEnhancerScanning();
        registry.registerComponent(MicrometerMetricsConfiguration.class, c -> custom);
        registry.registerEnhancer(new MicrometerMetricsConfigurationEnhancer());

        // when
        Configuration config = registry.build(new StubLifecycleRegistry());

        // then
        assertThat(config.getComponent(MicrometerMetricsConfiguration.class)).isSameAs(custom);
    }

    @Test
    @SuppressWarnings("deprecation")
    void configurationFromDeprecatedEnhancerConstructorIsNotOverridden() {
        // given
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        DefaultComponentRegistry registry = new DefaultComponentRegistry().disableEnhancerScanning();
        registry.registerEnhancer(new MetricsConfigurationEnhancer(meterRegistry, false));
        registry.registerEnhancer(new MicrometerMetricsConfigurationEnhancer());

        // when
        Configuration config = registry.build(new StubLifecycleRegistry());

        // then
        assertThat(config.getComponent(MicrometerMetricsConfiguration.class))
                .isEqualTo(new MicrometerMetricsConfiguration(meterRegistry, false));
    }
}
