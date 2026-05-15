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

package org.axonframework.extension.springboot.test;

import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.test.fixture.AxonTestFixture;
import org.axonframework.test.fixture.MessagesRecordingConfigurationEnhancer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Test configuration that registers the infrastructure required for {@link AxonTestFixture} in a Spring Boot test
 * context.
 * <p>
 * Registers:
 * <ul>
 *     <li>{@link MessagesRecordingConfigurationEnhancer} — decorates the command bus and event sink with recording
 *     wrappers so that the fixture can capture dispatched commands and published events.</li>
 *     <li>{@link AxonTestFixture} — the main test fixture, wired with the Spring-managed
 *     {@link AxonConfiguration}.</li>
 * </ul>
 * <p>
 * The fixture's {@link AxonTestFixture.Customization} can be overridden by declaring a bean of that type in the test
 * class (e.g. in an inner {@code @TestConfiguration} class). When no such bean is present, a default
 * {@code Customization} is derived from the Spring {@link org.springframework.core.env.Environment}: the
 * {@code axon.axonserver.enabled} property controls whether
 * {@link AxonTestFixture.Customization#integrationEnabled() integration is enabled} in the fixture.
 *
 * @author Mateusz Nowak
 * @since 5.1.0
 */
@TestConfiguration
class AxonTestConfiguration {

    /**
     * Registers a {@link MessagesRecordingConfigurationEnhancer} that decorates the {@code CommandBus} and
     * {@code EventSink} with recording wrappers. This is required for {@link AxonTestFixture} to capture dispatched
     * commands and published events during test execution.
     *
     * @return a new {@link MessagesRecordingConfigurationEnhancer}
     */
    @Bean
    MessagesRecordingConfigurationEnhancer recordingEnhancer() {
        return new MessagesRecordingConfigurationEnhancer();
    }

    /**
     * Registers an {@link AxonTestFixture} wired with the Spring-managed {@link AxonConfiguration}.
     * <p>
     * If the test context contains a {@link AxonTestFixture.Customization} bean (e.g. declared in an inner
     * {@code @TestConfiguration} class), it is used to configure the fixture. Otherwise, a default
     * {@code Customization} is built by reading the {@code axon.axonserver.enabled} property from the Spring
     * {@link Environment}:
     * <ul>
     *     <li>When the property is {@code true}, we mark the test as an
     *     {@link AxonTestFixture.Customization#asIntegrationTest() integration test}.</li>
     *     <li>When the property is {@code false} or absent, we keep the fixture as a regular test (the default).</li>
     * </ul>
     * A custom {@link AxonTestFixture.Customization} bean always takes full precedence over this default.
     *
     * @param configuration the Axon application configuration provided by the Spring context
     * @param customization optional provider for a {@link AxonTestFixture.Customization} bean
     * @param environment   the Spring environment used to resolve the {@code axon.axonserver.enabled} property
     * @return a ready-to-use {@link AxonTestFixture}
     */
    @Bean(destroyMethod = "stop")
    AxonTestFixture axonTestFixture(
            AxonConfiguration configuration,
            ObjectProvider<AxonTestFixture.Customization> customization,
            Environment environment
    ) {
        return new AxonTestFixture(
                configuration,
                customization.getIfAvailable(() -> defaultCustomization(environment))
        );
    }

    private static AxonTestFixture.Customization defaultCustomization(Environment environment) {
        boolean axonServerEnabled = environment.getProperty("axon.axonserver.enabled", Boolean.class, false);
        var customization = new AxonTestFixture.Customization();
        return axonServerEnabled ? customization.asIntegrationTest() : customization;
    }
}
