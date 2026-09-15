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
import org.axonframework.messaging.core.timeout.HandlerTimeoutConfigurationEnhancer;
import org.axonframework.messaging.core.timeout.TimeoutUnitOfWorkFactoryConfiguration;
import org.axonframework.messaging.core.timeout.TimeoutUnitOfWorkFactoryConfigurationEnhancer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Translates {@link TimeoutProperties} into the {@link HandlerTimeoutConfiguration} and
 * {@link TimeoutUnitOfWorkFactoryConfiguration} components that drive the timeout behavior.
 * <p>
 * The actual wiring of the timeout behavior, wrapping message handlers with a timeout, and decorating the
 * {@code UnitOfWorkFactory} used by the command bus, query bus, and every event processor so every {@code UnitOfWork}
 * they create is time-limited, is done by the {@link HandlerTimeoutConfigurationEnhancer} and
 * {@link TimeoutUnitOfWorkFactoryConfigurationEnhancer}.
 *
 * @author Mitchell Herrijgers
 * @author Steven van Beelen
 * @since 4.11.0
 */
@AutoConfiguration
@EnableConfigurationProperties(value = TimeoutProperties.class)
public class AxonTimeoutAutoConfiguration {

    /**
     * Bean creation method for the {@link HandlerTimeoutConfiguration}, translated from the given {@code properties}.
     * Picked up by the {@link HandlerTimeoutConfigurationEnhancer} to wrap message handlers with a timeout.
     *
     * @param properties the timeout properties to translate into a {@link HandlerTimeoutConfiguration}
     * @return the {@link HandlerTimeoutConfiguration} driving handler-level timeout behavior
     */
    @Bean
    @ConditionalOnProperty(prefix = "axon.timeout", name = "enabled", havingValue = "true", matchIfMissing = true)
    public HandlerTimeoutConfiguration handlerTimeoutConfiguration(TimeoutProperties properties) {
        return properties.getHandler().mapToConfiguration();
    }

    /**
     * Bean creation method specifically to disable handler-timeout behavior, by setting a
     * {@link HandlerTimeoutConfiguration#DISABLED}
     *
     * @return a {@link HandlerTimeoutConfiguration#DISABLED} instance
     */
    @Bean
    @ConditionalOnProperty(prefix = "axon.timeout", name = "enabled", havingValue = "false", matchIfMissing = false)
    public HandlerTimeoutConfiguration disabledHandlerTimeoutConfiguration() {
        return HandlerTimeoutConfiguration.DISABLED;
    }

    /**
     * Bean creation method for the {@link TimeoutUnitOfWorkFactoryConfiguration}, translated from the given
     * {@code properties}. Picked up by the {@link TimeoutUnitOfWorkFactoryConfigurationEnhancer} to decorate the
     * {@code UnitOfWorkFactory} used by the command bus, query bus, and every event processor, constructing
     * timeout-specific {@code UnitOfWork} instances for each.
     *
     * @param properties the timeout properties to translate into a {@link TimeoutUnitOfWorkFactoryConfiguration}
     * @return the {@link TimeoutUnitOfWorkFactoryConfiguration} driving transaction-level timeout behavior
     */
    @Bean
    @ConditionalOnProperty(prefix = "axon.timeout", name = "enabled", havingValue = "true", matchIfMissing = true)
    public TimeoutUnitOfWorkFactoryConfiguration timeoutUnitOfWorkFactoryConfiguration(TimeoutProperties properties) {
        return properties.getUnitOfWork().mapToConfiguration();
    }

    /**
     * Bean creation method specifically to disable
     * {@link org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory}-timeout behavior, by setting a
     * {@link TimeoutUnitOfWorkFactoryConfiguration#DISABLED}
     *
     * @return a {@link TimeoutUnitOfWorkFactoryConfiguration#DISABLED} instance
     */
    @Bean
    @ConditionalOnProperty(prefix = "axon.timeout", name = "enabled", havingValue = "false", matchIfMissing = false)
    public TimeoutUnitOfWorkFactoryConfiguration disabledTimeoutUnitOfWorkFactoryConfiguration() {
        return TimeoutUnitOfWorkFactoryConfiguration.DISABLED;
    }
}
