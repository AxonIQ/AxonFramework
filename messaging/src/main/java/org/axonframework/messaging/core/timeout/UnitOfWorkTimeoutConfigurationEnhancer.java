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

import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.configuration.MessagingConfigurationDefaults;
import org.axonframework.messaging.core.interception.HandlerInterceptorRegistry;
import org.jspecify.annotations.Nullable;

/**
 * A {@link ConfigurationEnhancer} that registers {@link UnitOfWorkTimeoutInterceptorBuilder}-backed
 * {@link MessageHandlerInterceptor MessageHandlerInterceptors} for the command bus, query bus, and every event
 * processor, based on the {@link UnitOfWorkTimeoutConfiguration} present in the {@link Configuration}.
 * <p>
 * This enhancer is automatically discovered through the {@link java.util.ServiceLoader} mechanism, so it applies to
 * every application using this module. When no {@link UnitOfWorkTimeoutConfiguration} component is registered,
 * {@link UnitOfWorkTimeoutConfiguration#UnitOfWorkTimeoutConfiguration() the fully disabled default} is used, meaning
 * no interceptor is registered for any bus or processor. Register a {@code UnitOfWorkTimeoutConfiguration} component to
 * opt into transaction-level timeout behavior.
 * <p>
 * Event processors are resolved by name, so different processors can have different timeout settings, as configured
 * through {@link UnitOfWorkTimeoutConfiguration#eventProcessorSettings(String)}.
 *
 * @author Steven van Beelen
 * @see UnitOfWorkTimeoutConfiguration
 * @see UnitOfWorkTimeoutInterceptorBuilder
 * @since 5.4.0
 */
public class UnitOfWorkTimeoutConfigurationEnhancer implements ConfigurationEnhancer {

    /**
     * The order of {@code this} enhancer compared to others, equal to
     * {@link MessagingConfigurationDefaults#ENHANCER_ORDER} minus 10.
     * <p>
     * This value ensure the interceptors set by this enhancer are quickly followed by any default interceptors set by
     * the {@link MessagingConfigurationDefaults}, still leaving some room for customization by the user.
     */
    public static final int ENHANCER_ORDER = MessagingConfigurationDefaults.ENHANCER_ORDER - 10_000;

    @Override
    public void enhance(ComponentRegistry registry) {
        registry.registerIfNotPresent(UnitOfWorkTimeoutConfiguration.class, c -> new UnitOfWorkTimeoutConfiguration());
        registry.registerDecorator(
                HandlerInterceptorRegistry.class,
                0,
                (config, componentName, delegate) -> delegate
                        .registerCommandInterceptor((c, componentType, name) -> buildOrNull(
                                "CommandBus", c.getComponent(UnitOfWorkTimeoutConfiguration.class)
                                               .getCommandBus()
                        ))
                        .registerQueryInterceptor((c, componentType, name) -> buildOrNull(
                                "QueryBus", c.getComponent(UnitOfWorkTimeoutConfiguration.class)
                                             .getQueryBus()
                        ))
                        .registerEventInterceptor((c, componentType, name) -> buildOrNull(
                                "EventProcessor " + name, c.getComponent(UnitOfWorkTimeoutConfiguration.class)
                                                           .eventProcessorSettings(name)
                        ))
        );
    }

    @Override
    public int order() {
        return ENHANCER_ORDER;
    }

    @Nullable
    private static <M extends Message> MessageHandlerInterceptor<? super M> buildOrNull(
            String componentName, TaskTimeoutSettings settings
    ) {
        if (settings.isDisabled()) {
            return null;
        }
        return new UnitOfWorkTimeoutInterceptorBuilder(
                componentName,
                settings.getTimeoutMs(),
                settings.getWarningThresholdMs(),
                settings.getWarningIntervalMs()
        ).build();
    }
}
