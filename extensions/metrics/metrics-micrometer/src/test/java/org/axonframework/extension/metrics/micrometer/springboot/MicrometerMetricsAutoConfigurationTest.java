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
import org.axonframework.extension.metrics.micrometer.MetricsConfigurationEnhancer;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating the {@link MicrometerMetricsAutoConfiguration}.
 *
 * @author Steven van Beelen
 */
class MicrometerMetricsAutoConfigurationTest {

    private ApplicationContextRunner testContext;

    @BeforeEach
    void setUp() {
        testContext = new ApplicationContextRunner().withUserConfiguration(TestContext.class);
    }

    @Test
    void defaultMetricAutoConfigSetsMeterRegistryBeanForEnhancer() {
        testContext.withPropertyValues("axon.metrics.enabled=true")
                   .run(context -> {
                       assertThat(context).hasSingleBean(MeterRegistry.class);
                       assertThat(context).hasSingleBean(MetricsConfigurationEnhancer.class);
                   });
    }

    @Test
    void defaultMetricAutoConfigSetsMeterRegistryBeanForEnhancerWorksWithoutProperty() {
        // Deliberately not included "axon.metrics.enabled=true" per test!
        testContext.run(context -> {
            assertThat(context).hasSingleBean(MeterRegistry.class);
            assertThat(context).hasSingleBean(MetricsConfigurationEnhancer.class);
        });
    }

    @Test
    void disabledMetricsDisablesMetricRegistryAndMetricsConfigurationEnhancer() {
        testContext.withPropertyValues("axon.metrics.enabled=false")
                   .run(context -> {
                       assertThat(context).doesNotHaveBean(MeterRegistry.class);
                       assertThat(context).doesNotHaveBean(MetricsConfigurationEnhancer.class);
                   });
    }

    @Test
    void metricsConfigurationEnhancerReusesUserSuppliedMeterRegistry() {
        new ApplicationContextRunner().withUserConfiguration(UserMeterRegistryContext.class)
                                      .run(context -> {
                                          assertThat(context).hasSingleBean(MeterRegistry.class);
                                          assertThat(context.getBean(MeterRegistry.class))
                                                  .isSameAs(UserMeterRegistryContext.USER_METER_REGISTRY);
                                          assertThat(context).hasSingleBean(MetricsConfigurationEnhancer.class);
                                      });
    }

    @Configuration
    @EnableAutoConfiguration
    public static class TestContext {

    }

    @Configuration
    @EnableAutoConfiguration
    public static class UserMeterRegistryContext {

        private static final MeterRegistry USER_METER_REGISTRY = new SimpleMeterRegistry();

        @Bean
        public MeterRegistry meterRegistry() {
            return USER_METER_REGISTRY;
        }
    }
}
