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

package org.axonframework.extension.metrics.micrometer.springboot;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.extension.metrics.micrometer.MetricsConfigurationEnhancer;
import org.axonframework.extension.springboot.autoconfig.AxonAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Autoconfiguration to set up Micrometer Metrics for the infrastructure components.
 *
 * @author Steven van Beelen
 * @author Marijn van Zelst
 * @since 4.1.0
 */
@AutoConfiguration
@AutoConfigureBefore(value = AxonAutoConfiguration.class)
@AutoConfigureAfter(name = {
        "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration"
})
@ConditionalOnClass(name = {
        "org.axonframework.extension.springboot.autoconfig.AxonAutoConfiguration",
        "io.micrometer.core.instrument.MeterRegistry"
})
@EnableConfigurationProperties(MetricsProperties.class)
public class MicrometerMetricsAutoConfiguration {

    /**
     * Bean creation method constructing a Micrometer {@link MeterRegistry} for the
     * {@link MetricsConfigurationEnhancer}.
     *
     * @return a Micrometer {@link MeterRegistry} to be used by the {@link MetricsConfigurationEnhancer}
     */
    @Bean
    @ConditionalOnMissingBean(MeterRegistry.class)
    @ConditionalOnProperty(value = "axon.metrics.enabled", havingValue = "true", matchIfMissing = true)
    public MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }

    /**
     * Bean creation method constructing a {@link ConfigurationEnhancer} that disables
     * {@link MetricsConfigurationEnhancer} that is only constructed when {@code axon.metrics.enabled} is set to
     * {@code false}.
     *
     * @return a {@link ConfigurationEnhancer} {@link MetricsConfigurationEnhancer} that is only constructed when
     * {@code axon.metrics.enabled} is set to {@code false}
     */
    @Bean
    @ConditionalOnProperty(name = "axon.metrics.enabled", havingValue = "false")
    public ConfigurationEnhancer disableMetricsConfigurationEnhancer() {
        return new ConfigurationEnhancer() {
            @Override
            public void enhance(ComponentRegistry registry) {
                registry.disableEnhancer(MetricsConfigurationEnhancer.class);
            }

            @Override
            public int order() {
                return Integer.MIN_VALUE;
            }
        };
    }
}

