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

import jakarta.annotation.Nonnull;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.interception.HandlerInterceptorRegistry;
import org.axonframework.messaging.core.timeout.HandlerTimeoutHandlerEnhancerDefinition;
import org.axonframework.messaging.core.timeout.TaskTimeoutSettings;
import org.axonframework.messaging.core.timeout.UnitOfWorkTimeoutInterceptorBuilder;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.queryhandling.QueryMessage;
import org.axonframework.messaging.queryhandling.QueryBus;
import org.axonframework.extension.micronaut.TimeoutProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Configures the timeout settings for message handlers.
 *
 * @author Mitchell Herrijgers
 * @since 4.11.0
 */
@AutoConfiguration
@EnableConfigurationProperties(value = {
        TimeoutProperties.class
})
@ConditionalOnProperty(prefix = "axon.timeout", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AxonTimeoutAutoConfiguration {

    @Bean
    public HandlerTimeoutHandlerEnhancerDefinition messageTimeoutHandlerEnhancerDefinition(
            TimeoutProperties properties
    ) {
        return new HandlerTimeoutHandlerEnhancerDefinition(
                properties.getHandler().toMessageHandlerTimeoutConfiguration()
        );
    }

    /**
     * Bean creation method for a {@link ConfigurationEnhancer} adding the {@link UnitOfWorkTimeoutInterceptorBuilder}
     * for {@link CommandMessage CommandMessages},
     * {@link EventMessage EventMessages}, and
     * {@link QueryMessage QueryMessages} as
     * {@link MessageHandlerInterceptor MessageHandlerInterceptors}.
     *
     * @param properties The timeout properties influencing the configured
     *                   {@link MessageHandlerInterceptor MessageHandlerInterceptors}.
     * @return A {@link ConfigurationEnhancer} adding
     * {@link MessageHandlerInterceptor MessageHandlerInterceptors} to introduce timeout
     * behavior for every type of {@link Message}.
     */
    @Bean
    public ConfigurationEnhancer axonTimeoutConfigurationEnhancer(@Nonnull TimeoutProperties properties) {
        return new AxonTimeoutConfigurerModule(properties.getTransaction());
    }

    private record AxonTimeoutConfigurerModule(
            @Nonnull TimeoutProperties.TransactionTimeoutProperties properties
    ) implements ConfigurationEnhancer {

        @Override
        public int order() {
            return Integer.MIN_VALUE;
        }

        @Override
        public void enhance(@Nonnull ComponentRegistry registry) {
            registry.registerDecorator(
                    HandlerInterceptorRegistry.class,
                    0,
                    (config, name, delegate) -> delegate.registerEventInterceptor(
                            c -> {
                                TaskTimeoutSettings settings = getSettingsForProcessor(name);
                                return new UnitOfWorkTimeoutInterceptorBuilder(
                                        "EventProcessor " + name,
                                        settings.getTimeoutMs(),
                                        settings.getWarningThresholdMs(),
                                        settings.getWarningIntervalMs()
                                ).buildEventInterceptor();
                            }
                    ).registerCommandInterceptor(
                            c -> new UnitOfWorkTimeoutInterceptorBuilder(
                                    c.getComponent(CommandBus.class).getClass().getSimpleName(),
                                    properties.getCommandBus().getTimeoutMs(),
                                    properties.getCommandBus().getWarningThresholdMs(),
                                    properties.getCommandBus().getWarningIntervalMs()
                            ).buildCommandInterceptor()
                    ).registerQueryInterceptor(
                            c -> new UnitOfWorkTimeoutInterceptorBuilder(
                                    c.getComponent(QueryBus.class).getClass().getSimpleName(),
                                    properties.getQueryBus().getTimeoutMs(),
                                    properties.getQueryBus().getWarningThresholdMs(),
                                    properties.getQueryBus().getWarningIntervalMs()
                            ).buildQueryInterceptor()
                    )
            );
        }

        private TaskTimeoutSettings getSettingsForProcessor(String name) {
            return properties.getEventProcessor().getOrDefault(name, properties.getEventProcessors());
        }
    }
}
