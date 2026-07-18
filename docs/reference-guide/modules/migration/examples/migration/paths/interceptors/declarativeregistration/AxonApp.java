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
package migration.paths.interceptors.declarativeregistration;

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.MessageDispatchInterceptor;
import org.axonframework.messaging.core.MessageDispatchInterceptorChain;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;

class AxonApp {

    public static void main(String[] args) {
        // tag::declarative-registration[]
        MessagingConfigurer configurer = MessagingConfigurer.create();

        // Register handler interceptor for commands
        configurer.registerCommandHandlerInterceptor(
            config -> new MyCommandHandlerInterceptor()
        );

        // Register dispatch interceptor for commands
        configurer.registerCommandDispatchInterceptor(
            config -> new MyCommandDispatchInterceptor()
        );
        // end::declarative-registration[]
    }
}

class MyCommandHandlerInterceptor implements MessageHandlerInterceptor<CommandMessage> {

    @Override
    public MessageStream<?> interceptOnHandle(CommandMessage message,
                                              ProcessingContext context,
                                              MessageHandlerInterceptorChain<CommandMessage> chain) {
        return chain.proceed(message, context);
    }
}

class MyCommandDispatchInterceptor implements MessageDispatchInterceptor<CommandMessage> {

    @Override
    public MessageStream<?> interceptOnDispatch(CommandMessage message,
                                                @Nullable ProcessingContext context,
                                                MessageDispatchInterceptorChain<CommandMessage> chain) {
        return chain.proceed(message, context);
    }
}
