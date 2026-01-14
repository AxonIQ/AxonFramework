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

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.common.configuration.DecoratorDefinition;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageDispatchInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.interception.DispatchInterceptorRegistry;
import org.axonframework.messaging.core.interception.HandlerInterceptorRegistry;
import org.axonframework.messaging.queryhandling.QueryMessage;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.Optional;

/**
 * Interceptor autoconfiguration class for Axon Framework application. Discovers {@link MessageHandlerInterceptor}s and
 * {@link MessageDispatchInterceptor} and registers them with the respective buses and gateways.
 * <p>
 * Note: This class use a hack approach! Because some gateways/buses (or custom interceptors provided by the framework
 * user) need an axonConfiguration to initialize, the usual way of registering interceptors in the
 * ConfigurerModule.onInitialize method does not work for them. This is due to a circular reference caused e.g. by
 * JpaJavaxEventStoreAutoConfiguration. So we register them by injecting gateway/bus components in to the
 * InitializingBean function and register the interceptors there.
 *
 * @author Christian Thiel
 * @since 4.11.0
 */
@AutoConfiguration
@AutoConfigureAfter(AxonAutoConfiguration.class)
public class InterceptorAutoConfiguration {

    /**
     * Bean creation method for a {@link DecoratorDefinition} that registers {@link Message}-specific
     * {@link MessageDispatchInterceptor MessageDispatchInterceptors} with the {@link DispatchInterceptorRegistry}.
     *
     * @param commandInterceptors {@link CommandMessage}-specific and generic {@link Message} dispatch interceptors to
     *                            register for commands.
     * @param eventInterceptors   {@link EventMessage}-specific and generic {@link Message} dispatch interceptors to
     *                            register for events.
     * @param queryInterceptors   {@link QueryMessage}-specific and generic {@link Message} dispatch interceptors to
     *                            register for queries.
     * @return A bean creation method for a {@link DecoratorDefinition} that registers {@link Message}-specific
     * {@link MessageDispatchInterceptor MessageDispatchInterceptors} with the {@link DispatchInterceptorRegistry}.
     */
    @Bean
    @ConditionalOnBean(MessageDispatchInterceptor.class)
    public DecoratorDefinition<DispatchInterceptorRegistry, DispatchInterceptorRegistry> dispatchInterceptorEnhancer(
            Optional<List<MessageDispatchInterceptor<? super CommandMessage>>> commandInterceptors,
            Optional<List<MessageDispatchInterceptor<? super EventMessage>>> eventInterceptors,
            Optional<List<MessageDispatchInterceptor<? super QueryMessage>>> queryInterceptors
    ) {
        return DecoratorDefinition.forType(DispatchInterceptorRegistry.class)
                                  .with((config, name, delegate) -> registerDispatchInterceptors(
                                          delegate,
                                          commandInterceptors,
                                          eventInterceptors,
                                          queryInterceptors
                                  ));
    }

    private static DispatchInterceptorRegistry registerDispatchInterceptors(
            DispatchInterceptorRegistry registry,
            Optional<List<MessageDispatchInterceptor<? super CommandMessage>>> commandInterceptors,
            Optional<List<MessageDispatchInterceptor<? super EventMessage>>> eventInterceptors,
            Optional<List<MessageDispatchInterceptor<? super QueryMessage>>> queryInterceptors
    ) {
        if (commandInterceptors.isPresent()) {
            for (MessageDispatchInterceptor<? super CommandMessage> interceptor : commandInterceptors.get()) {
                registry = registry.registerCommandInterceptor(c -> interceptor);
            }
        }
        if (eventInterceptors.isPresent()) {
            for (MessageDispatchInterceptor<? super EventMessage> interceptor : eventInterceptors.get()) {
                registry = registry.registerEventInterceptor(c -> interceptor);
            }
        }
        if (queryInterceptors.isPresent()) {
            for (MessageDispatchInterceptor<? super QueryMessage> interceptor : queryInterceptors.get()) {
                registry = registry.registerQueryInterceptor(c -> interceptor);
            }
        }
        return registry;
    }

    /**
     * Bean creation method for a {@link DecoratorDefinition} that registers {@link Message}-specific
     * {@link MessageHandlerInterceptor MessageHandlerInterceptors} with the {@link HandlerInterceptorRegistry}.
     *
     * @param interceptors        Generic {@link Message} handler interceptors to register.
     * @param commandInterceptors {@link CommandMessage}-specific handler interceptors to register.
     * @param eventInterceptors   {@link EventMessage}-specific handler interceptors to register.
     * @param queryInterceptors   {@link QueryMessage}-specific handler interceptors to register.
     * @return A bean creation method for a {@link DecoratorDefinition} that registers {@link Message}-specific
     * {@link MessageHandlerInterceptor MessageHandlerInterceptors} with the {@link DispatchInterceptorRegistry}.
     */
    @Bean
    @ConditionalOnBean(MessageHandlerInterceptor.class)
    public DecoratorDefinition<HandlerInterceptorRegistry, HandlerInterceptorRegistry> handlerInterceptorEnhancer(
            Optional<List<MessageHandlerInterceptor<Message>>> interceptors,
            Optional<List<MessageHandlerInterceptor<CommandMessage>>> commandInterceptors,
            Optional<List<MessageHandlerInterceptor<EventMessage>>> eventInterceptors,
            Optional<List<MessageHandlerInterceptor<QueryMessage>>> queryInterceptors
    ) {
        return DecoratorDefinition.forType(HandlerInterceptorRegistry.class)
                                  .with((config, name, delegate) -> registerHandlerInterceptors(
                                          delegate,
                                          interceptors,
                                          commandInterceptors,
                                          eventInterceptors,
                                          queryInterceptors
                                  ));
    }

    private static HandlerInterceptorRegistry registerHandlerInterceptors(
            HandlerInterceptorRegistry registry,
            Optional<List<MessageHandlerInterceptor<Message>>> interceptors,
            Optional<List<MessageHandlerInterceptor<CommandMessage>>> commandInterceptors,
            Optional<List<MessageHandlerInterceptor<EventMessage>>> eventInterceptors,
            Optional<List<MessageHandlerInterceptor<QueryMessage>>> queryInterceptors
    ) {
        if (interceptors.isPresent()) {
            for (MessageHandlerInterceptor<Message> interceptor : interceptors.get()) {
                registry = registry.registerInterceptor(c -> interceptor);
            }
        }
        if (commandInterceptors.isPresent()) {
            for (MessageHandlerInterceptor<CommandMessage> interceptor : commandInterceptors.get()) {
                registry = registry.registerCommandInterceptor(c -> interceptor);
            }
        }
        if (eventInterceptors.isPresent()) {
            for (MessageHandlerInterceptor<EventMessage> interceptor : eventInterceptors.get()) {
                registry = registry.registerEventInterceptor(c -> interceptor);
            }
        }
        if (queryInterceptors.isPresent()) {
            for (MessageHandlerInterceptor<QueryMessage> interceptor : queryInterceptors.get()) {
                registry = registry.registerQueryInterceptor(c -> interceptor);
            }
        }
        return registry;
    }
}
