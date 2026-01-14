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

package org.axonframework.extension.micronaut.autoconfig;

import org.axonframework.update.UpdateChecker;
import org.axonframework.update.UpdateCheckerHttpClient;
import org.axonframework.update.UpdateCheckerReporter;
import org.axonframework.update.configuration.EnvironmentVariableUsagePropertyProvider;
import org.axonframework.update.configuration.UsagePropertyProvider;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ContextConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.axonframework.update.detection.TestEnvironmentDetector.AXONIQ_USAGE_FORCE_TEST_ENVIRONMENT;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link UpdateCheckerAutoConfiguration}.
 *
 * @author Steven van Beelen
 */
class UpdateCheckerAutoConfigurationTest {

    private ApplicationContextRunner testContext;

    @BeforeEach
    void setUp() {
        this.testContext = new ApplicationContextRunner()
                .withUserConfiguration(DefaultContext.class)
                .withPropertyValues("axon.axonserver.enabled:false");
    }

    @AfterEach
    void cleanup() {
        // Reset the system property after each test to avoid side effects
        System.clearProperty(AXONIQ_USAGE_FORCE_TEST_ENVIRONMENT);
        System.clearProperty(EnvironmentVariableUsagePropertyProvider.DISABLED_KEY);
        System.clearProperty(EnvironmentVariableUsagePropertyProvider.URL_KEY);
    }

    @Test
    void updateCheckerBeansAreRegisteredForNonTestEnvironment() {
        // Force disable the test environment detection
        System.setProperty(AXONIQ_USAGE_FORCE_TEST_ENVIRONMENT, "true");

        testContext.run(context -> {
            assertThat(context).hasBean("usagePropertyProvider");
            assertThat(context).hasSingleBean(UpdateCheckerHttpClient.class);
            assertThat(context).hasSingleBean(UpdateCheckerReporter.class);
            assertThat(context).hasSingleBean(UpdateChecker.class);
            assertThat(context.getBean("usagePropertyProvider", UsagePropertyProvider.class)
                              .getUrl()).isEqualTo("https://get.axoniq.io/updates/framework");
        });
    }

    @Test
    void updateCheckerSpringPropertiesTakePrecedenceOverSystemProperties() {
        System.setProperty(AXONIQ_USAGE_FORCE_TEST_ENVIRONMENT, "true");
        System.setProperty(EnvironmentVariableUsagePropertyProvider.DISABLED_KEY, "false");
        System.setProperty(EnvironmentVariableUsagePropertyProvider.URL_KEY, "wrong_url");
        testContext.withPropertyValues("axon.update-check.disabled:true")
                .withPropertyValues("axon.update-check.url:https://test.example.com")
                .run(context -> {
                    assertThat(context).hasBean("usagePropertyProvider");
                    assertThat(context.getBean("usagePropertyProvider", UsagePropertyProvider.class)
                                      .getDisabled()).isTrue();
                    assertThat(context.getBean("usagePropertyProvider", UsagePropertyProvider.class)
                                      .getUrl()).isEqualTo("https://test.example.com");
                    assertThat(context).hasSingleBean(UpdateCheckerHttpClient.class);
                    assertThat(context).hasSingleBean(UpdateCheckerReporter.class);
                    assertThat(context).hasSingleBean(UpdateChecker.class);
                });
    }

    @Test
    void updateCheckerBeansAreNotRegisteredForTestEnvironment() {
        testContext.run(context -> {
            assertThat(context).doesNotHaveBean("usagePropertyProvider");
            assertThat(context).doesNotHaveBean("updateCheckerHttpClient");
            assertThat(context).doesNotHaveBean("updateCheckerReporter");
            assertThat(context).doesNotHaveBean("updateChecker");
        });
    }

    @Test
    void updateCheckerBeansCanBeOverwritten() {
        testContext.withUserConfiguration(OverrideContext.class).run(context -> {
            assertThat(context).hasBean("customUsagePropertyProvider");
            assertThat(context).hasBean("customUpdateCheckerHttpClient");
            assertThat(context).hasBean("customUpdateCheckerReporter");
            assertThat(context).hasBean("customUpdateChecker");

            assertThat(context).doesNotHaveBean("usagePropertyProvider");
            assertThat(context).doesNotHaveBean("updateCheckerHttpClient");
            assertThat(context).doesNotHaveBean("updateCheckerReporter");
            assertThat(context).doesNotHaveBean("updateChecker");
        });
    }

    @ContextConfiguration
    @EnableAutoConfiguration
    private static class DefaultContext {

    }

    @ContextConfiguration
    @EnableAutoConfiguration
    private static class OverrideContext {

        @Bean
        public UsagePropertyProvider customUsagePropertyProvider() {
            return mock(UsagePropertyProvider.class);
        }

        @Bean
        public UpdateCheckerHttpClient customUpdateCheckerHttpClient() {
            return mock(UpdateCheckerHttpClient.class);
        }

        @Bean
        public UpdateCheckerReporter customUpdateCheckerReporter() {
            return mock(UpdateCheckerReporter.class);
        }

        @Bean
        public UpdateChecker customUpdateChecker() {
            return mock(UpdateChecker.class);
        }
    }
}