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

package org.axonframework.extension.springboot.autoconfig;

import org.axonframework.extension.springboot.TimeoutProperties;
import org.axonframework.messaging.core.timeout.HandlerTimeoutConfiguration;
import org.axonframework.messaging.core.timeout.TimeoutUnitOfWorkFactoryConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests validating the wiring of {@link AxonTimeoutAutoConfiguration} through Spring Boot's
 * {@link ApplicationContextRunner}.
 * <p>
 * {@code AxonTimeoutAutoConfiguration} always registers exactly one {@link HandlerTimeoutConfiguration} bean and
 * exactly one {@link TimeoutUnitOfWorkFactoryConfiguration} bean, chosen through two mutually exclusive
 * {@code @ConditionalOnProperty} bean methods per type: a bean translating {@link TimeoutProperties} into real
 * settings when {@code axon.timeout.enabled} is {@code true} or absent, and
 * {@link HandlerTimeoutConfiguration#DISABLED}/{@link TimeoutUnitOfWorkFactoryConfiguration#DISABLED} when it is
 * explicitly {@code false}. This is deliberate: the underlying {@code ConfigurationEnhancer}s
 * ({@code HandlerTimeoutConfigurationEnhancer}/{@code TimeoutUnitOfWorkFactoryConfigurationEnhancer}) apply their own
 * enabled-by-default {@code DEFAULT} configuration whenever nothing is registered at all, so this auto-configuration
 * must always register something, in both the enabled and the disabled case, for {@code axon.timeout.enabled=false}
 * to have any effect.
 *
 * @author Steven van Beelen
 */
class AxonTimeoutAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AxonTimeoutAutoConfiguration.class));

    @Test
    void handlerTimeoutConfigurationIsEnabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(HandlerTimeoutConfiguration.class);
            HandlerTimeoutConfiguration configuration = context.getBean(HandlerTimeoutConfiguration.class);

            assertThat(configuration).isNotSameAs(HandlerTimeoutConfiguration.DISABLED);
            assertThat(configuration.getEvents().timeoutMs()).isEqualTo(30_000);
            assertThat(configuration.getEvents().warningThresholdMs()).isEqualTo(10_000);
            assertThat(configuration.getEvents().warningIntervalMs()).isEqualTo(1_000);
            assertThat(configuration.getCommands().timeoutMs()).isEqualTo(30_000);
            assertThat(configuration.getQueries().timeoutMs()).isEqualTo(30_000);
        });
    }

    @Test
    void timeoutUnitOfWorkFactoryConfigurationIsEnabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(TimeoutUnitOfWorkFactoryConfiguration.class);
            TimeoutUnitOfWorkFactoryConfiguration configuration =
                    context.getBean(TimeoutUnitOfWorkFactoryConfiguration.class);

            assertThat(configuration).isNotSameAs(TimeoutUnitOfWorkFactoryConfiguration.DISABLED);
            assertThat(configuration.getCommandBus().timeoutMs()).isEqualTo(60_000);
            assertThat(configuration.getCommandBus().warningThresholdMs()).isEqualTo(10_000);
            assertThat(configuration.getCommandBus().warningIntervalMs()).isEqualTo(1_000);
            assertThat(configuration.getQueryBus().timeoutMs()).isEqualTo(60_000);
            assertThat(configuration.getEventProcessors().timeoutMs()).isEqualTo(60_000);
            assertThat(configuration.getEventProcessor()).isEmpty();
        });
    }

    @Test
    void handlerTimeoutConfigurationIsEnabledWhenExplicitlyEnabled() {
        contextRunner.withPropertyValues("axon.timeout.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(HandlerTimeoutConfiguration.class);
            assertThat(context.getBean(HandlerTimeoutConfiguration.class))
                    .isNotSameAs(HandlerTimeoutConfiguration.DISABLED);
        });
    }

    @Test
    void timeoutUnitOfWorkFactoryConfigurationIsEnabledWhenExplicitlyEnabled() {
        contextRunner.withPropertyValues("axon.timeout.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(TimeoutUnitOfWorkFactoryConfiguration.class);
            assertThat(context.getBean(TimeoutUnitOfWorkFactoryConfiguration.class))
                    .isNotSameAs(TimeoutUnitOfWorkFactoryConfiguration.DISABLED);
        });
    }

    @Test
    void handlerTimeoutConfigurationIsDisabledWhenExplicitlyDisabled() {
        contextRunner.withPropertyValues("axon.timeout.enabled=false").run(context -> {
            assertThat(context).hasSingleBean(HandlerTimeoutConfiguration.class);
            assertThat(context.getBean(HandlerTimeoutConfiguration.class)).isSameAs(HandlerTimeoutConfiguration.DISABLED);
        });
    }

    @Test
    void timeoutUnitOfWorkFactoryConfigurationIsDisabledWhenExplicitlyDisabled() {
        contextRunner.withPropertyValues("axon.timeout.enabled=false").run(context -> {
            assertThat(context).hasSingleBean(TimeoutUnitOfWorkFactoryConfiguration.class);
            assertThat(context.getBean(TimeoutUnitOfWorkFactoryConfiguration.class))
                    .isSameAs(TimeoutUnitOfWorkFactoryConfiguration.DISABLED);
        });
    }

    @Test
    void handlerTimeoutConfigurationReflectsCustomPropertiesWhenEnabled() {
        contextRunner.withPropertyValues(
                "axon.timeout.handler.events.timeout-ms=1000",
                "axon.timeout.handler.events.warning-threshold-ms=500",
                "axon.timeout.handler.events.warning-interval-ms=100",
                "axon.timeout.handler.commands.timeout-ms=2000",
                "axon.timeout.handler.queries.timeout-ms=3000"
        ).run(context -> {
            HandlerTimeoutConfiguration configuration = context.getBean(HandlerTimeoutConfiguration.class);

            assertThat(configuration.getEvents().timeoutMs()).isEqualTo(1000);
            assertThat(configuration.getEvents().warningThresholdMs()).isEqualTo(500);
            assertThat(configuration.getEvents().warningIntervalMs()).isEqualTo(100);
            assertThat(configuration.getCommands().timeoutMs()).isEqualTo(2000);
            assertThat(configuration.getQueries().timeoutMs()).isEqualTo(3000);
        });
    }

    @Test
    void timeoutUnitOfWorkFactoryConfigurationReflectsCustomPropertiesWhenEnabled() {
        contextRunner.withPropertyValues(
                "axon.timeout.unit-of-work.command-bus.timeout-ms=1000",
                "axon.timeout.unit-of-work.query-bus.timeout-ms=2000",
                "axon.timeout.unit-of-work.event-processors.timeout-ms=3000",
                "axon.timeout.unit-of-work.event-processor.my-processor.timeout-ms=4000"
        ).run(context -> {
            TimeoutUnitOfWorkFactoryConfiguration configuration =
                    context.getBean(TimeoutUnitOfWorkFactoryConfiguration.class);

            assertThat(configuration.getCommandBus().timeoutMs()).isEqualTo(1000);
            assertThat(configuration.getQueryBus().timeoutMs()).isEqualTo(2000);
            assertThat(configuration.getEventProcessors().timeoutMs()).isEqualTo(3000);
            assertThat(configuration.eventProcessorSettings("my-processor").timeoutMs()).isEqualTo(4000);
            assertThat(configuration.eventProcessorSettings("other-processor").timeoutMs()).isEqualTo(3000);
        });
    }

    @Test
    void customHandlerPropertiesAreIgnoredWhenExplicitlyDisabled() {
        contextRunner.withPropertyValues(
                "axon.timeout.enabled=false",
                "axon.timeout.handler.events.timeout-ms=1000"
        ).run(context -> assertThat(context.getBean(HandlerTimeoutConfiguration.class))
                .isSameAs(HandlerTimeoutConfiguration.DISABLED));
    }

    @Test
    void customUnitOfWorkPropertiesAreIgnoredWhenExplicitlyDisabled() {
        contextRunner.withPropertyValues(
                "axon.timeout.enabled=false",
                "axon.timeout.unit-of-work.command-bus.timeout-ms=1000"
        ).run(context -> assertThat(context.getBean(TimeoutUnitOfWorkFactoryConfiguration.class))
                .isSameAs(TimeoutUnitOfWorkFactoryConfiguration.DISABLED));
    }

    @Test
    void timeoutPropertiesBeanIsAlwaysPresent() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(TimeoutProperties.class));
    }
}
