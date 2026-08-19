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
package migration.paths.interceptors.componentspecific;

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.core.interception.HandlerInterceptorRegistry;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

class AxonApp {

    public static void main(String[] args) {
        MessagingConfigurer configurer = MessagingConfigurer.create();

        // tag::component-specific-registration[]
        configurer.componentRegistry(cr -> cr.registerDecorator(
                HandlerInterceptorRegistry.class,
                0,
                (config, name, registry) -> registry.registerCommandInterceptor(
                        (factoryConfig, componentType, componentName) -> {
                            // Only intercept OrderAggregate commands
                            if (componentType.equals(OrderAggregate.class)) {
                                return new OrderValidationInterceptor();
                            }
                            return null; // No interceptor for other components
                        }
                )
        ));
        // end::component-specific-registration[]
    }
}

class OrderAggregate {
}

class OrderValidationInterceptor implements MessageHandlerInterceptor<CommandMessage> {

    @Override
    public MessageStream<?> interceptOnHandle(CommandMessage message,
                                              ProcessingContext context,
                                              MessageHandlerInterceptorChain<CommandMessage> chain) {
        return chain.proceed(message, context);
    }
}
