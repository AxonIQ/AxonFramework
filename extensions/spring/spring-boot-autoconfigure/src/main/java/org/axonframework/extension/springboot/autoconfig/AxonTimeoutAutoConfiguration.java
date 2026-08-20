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
import org.axonframework.messaging.core.timeout.UnitOfWorkTimeoutConfiguration;
import org.axonframework.messaging.core.timeout.UnitOfWorkTimeoutConfigurationEnhancer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Translates {@link TimeoutProperties} into the {@link HandlerTimeoutConfiguration} and
 * {@link UnitOfWorkTimeoutConfiguration} components that drive the timeout behavior.
 * <p>
 * The actual wiring of the timeout behavior &mdash; wrapping message handlers and registering interceptors on the
 * command bus, query bus, and event processors &mdash; is done by the {@link HandlerTimeoutConfigurationEnhancer} and
 * {@link UnitOfWorkTimeoutConfigurationEnhancer}. Both are automatically discovered through the
 * {@link java.util.ServiceLoader} mechanism, so this class only needs to make the two configuration components
 * available; Spring is not required for timeout behavior to work.
 * <p>
 * Setting {@code axon.timeout.enabled} to {@code false} prevents both components from being registered, which leaves
 * the enhancers at their fully disabled defaults.
 *
 * @author Mitchell Herrijgers
 * @author Steven van Beelen
 * @since 4.11.0
 */
@AutoConfiguration
@EnableConfigurationProperties(value = {
        TimeoutProperties.class
})
@ConditionalOnProperty(prefix = "axon.timeout", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AxonTimeoutAutoConfiguration {

    /**
     * Bean creation method for the {@link HandlerTimeoutConfiguration}, translated from the given {@code properties}.
     * Picked up by the {@link HandlerTimeoutConfigurationEnhancer} to wrap message handlers with a timeout.
     *
     * @param properties The timeout properties to translate into a {@link HandlerTimeoutConfiguration}.
     * @return The {@link HandlerTimeoutConfiguration} driving handler-level timeout behavior.
     */
    @Bean
    public HandlerTimeoutConfiguration handlerTimeoutConfiguration(TimeoutProperties properties) {
        return properties.getHandler().toMessageHandlerTimeoutConfiguration();
    }

    /**
     * Bean creation method for the {@link UnitOfWorkTimeoutConfiguration}, translated from the given
     * {@code properties}. Picked up by the {@link UnitOfWorkTimeoutConfigurationEnhancer} to register timeout
     * interceptors on the command bus, query bus, and every event processor.
     *
     * @param properties The timeout properties to translate into a {@link UnitOfWorkTimeoutConfiguration}.
     * @return The {@link UnitOfWorkTimeoutConfiguration} driving transaction-level timeout behavior.
     */
    @Bean
    public UnitOfWorkTimeoutConfiguration unitOfWorkTimeoutConfiguration(TimeoutProperties properties) {
        return properties.getUnitOfWork().toUnitOfWorkTimeoutConfiguration();
    }
}
