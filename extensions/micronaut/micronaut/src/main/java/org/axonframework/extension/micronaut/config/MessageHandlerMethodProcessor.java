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

package org.axonframework.extension.micronaut.config;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.processor.ExecutableMethodProcessor;
import io.micronaut.inject.BeanDefinition;
import jakarta.inject.Singleton;
import jakarta.validation.constraints.NotNull;
import org.axonframework.common.annotation.Internal;
import org.axonframework.messaging.core.annotation.MessageHandler;

import io.micronaut.inject.ExecutableMethod;

/**
 * A {@link ExecutableMethodProcessor} implementation that detects beans with Axon Message handlers and registers an
 * {@link MessageHandlerConfigurer} to have these handlers registered in the Axon {@link Configuration}.
 *
 * @author Daniel Karapishchenko
 * @since 5.1.0
 */

@Context
@Internal
public class MessageHandlerMethodProcessor implements ExecutableMethodProcessor<MessageHandler> {

    private MessageHandlerConfigurer messageHandlerConfigurer;

    public MessageHandlerMethodProcessor(@NotNull MessageHandlerConfigurer messageHandlerConfigurer) {
        this.messageHandlerConfigurer = messageHandlerConfigurer;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(BeanDefinition<?> beanDefinition, ExecutableMethod<?, ?> method) {

        if (!beanDefinition.isProxy() || !beanDefinition.isSingleton() || beanDefinition.isAbstract()) {
            return;
        }

        var declaredMessageType = method.getAnnotation(MessageHandler.class).get("messageType", Class.class);

        if (declaredMessageType.isEmpty()) {
            return;
        }

        for (MessageHandlerConfigurer.Type type : MessageHandlerConfigurer.Type.values()) {
            if (type.getMessageType().isAssignableFrom(declaredMessageType.get())) {
                messageHandlerConfigurer.register(type, beanDefinition);
                return;
            }
        }
    }
}
