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

package org.axonframework.messaging.eventhandling.configuration;

import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.common.configuration.DecoratorOrder;
import org.axonframework.messaging.core.MessageDispatchInterceptor;
import org.axonframework.messaging.core.configuration.MessagingConfigurationDefaults;
import org.axonframework.messaging.core.interception.DispatchInterceptorRegistry;
import org.axonframework.messaging.eventhandling.EventBus;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.InterceptingEventBus;
import org.axonframework.messaging.eventhandling.SimpleEventBus;

import java.util.List;

/**
 * A {@link ConfigurationEnhancer} registering the default components for event publishing and event bus.
 * <p>
 * Will only register the following components <b>if</b> there is no component registered for the given class yet:
 * <ul>
 *     <li>Registers a {@link SimpleEventBus} for class {@link EventBus}</li>
 * </ul>
 * <p>
 * Furthermore, this enhancer will decorate the:
 * <ul>
 *     <li>The {@link EventBus} in a {@link InterceptingEventBus} <b>if</b> there are any
 *     {@link MessageDispatchInterceptor MessageDispatchInterceptors} present in the {@link DispatchInterceptorRegistry}.</li>
 * </ul>
 *
 * @author Mateusz Nowak
 * @author Steven van Beelen
 * @since 5.0.0
 */
public class EventBusConfigurationDefaults implements ConfigurationEnhancer {

    /**
     * The order of {@code this} enhancer compared to others, equal to 100 positions before
     * {@link MessagingConfigurationDefaults} (thus, {@link MessagingConfigurationDefaults#ENHANCER_ORDER} - 100).
     */
    public static final int ENHANCER_ORDER = MessagingConfigurationDefaults.ENHANCER_ORDER;

    @Override
    public int order() {
        return ENHANCER_ORDER;
    }

    @Override
    public void enhance(ComponentRegistry registry) {
        registerComponents(registry);
        registerDecorators(registry);
    }

    private static void registerComponents(ComponentRegistry registry) {
        registry.registerIfNotPresent(EventBus.class, EventBusConfigurationDefaults::defaultEventBus);
    }

    private static EventBus defaultEventBus(Configuration config) {
        return new SimpleEventBus();
    }

    private static void registerDecorators(ComponentRegistry registry) {
        registry.registerDecorator(
                EventBus.class,
                DecoratorOrder.INTERCEPTING,
                (config, name, delegate) -> {
                    List<MessageDispatchInterceptor<? super EventMessage>> dispatchInterceptors =
                            config.getComponent(DispatchInterceptorRegistry.class)
                                  .eventInterceptors(config, EventBus.class, name);

                    return dispatchInterceptors.isEmpty()
                            ? delegate
                            : new InterceptingEventBus(delegate, dispatchInterceptors);
                }
        );
    }
}
