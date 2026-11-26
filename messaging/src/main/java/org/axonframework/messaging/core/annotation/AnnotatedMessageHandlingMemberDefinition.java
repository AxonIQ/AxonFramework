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

package org.axonframework.messaging.core.annotation;

import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.axonframework.common.annotation.AnnotationUtils.findAnnotationAttributes;

/**
 * The default HandlerDefinition implementation in Axon. It implements the rules of annotated handlers used in all the
 * different types of handlers in Axon.
 * <p>
 * For this implementation to recognize a handler method, it should be (meta)annotated with {@link MessageHandler}. It
 * is recommended to meta-annotated members, and preconfigure the expected {@code messageType}. For example, and event
 * handler should define {@code @MessageHandler(messageType = EventMessage.class)}, indicating that this handler should
 * only be invoked for {@link EventMessage}s.
 * <p>
 * Use {@link HandlerEnhancerDefinition} to add extra behavior or information on top of handlers created by this
 * definition.
 *
 * @author Allard Buijze
 * @see HandlerEnhancerDefinition
 * @see CommandHandler
 * @see EventHandler
 * @since 3.0.0
 */
public class AnnotatedMessageHandlingMemberDefinition implements HandlerDefinition {

    @Override
    public <T> Optional<MessageHandlingMember<T>> createHandler(
            Class<T> declaringType,
            Method method,
            ParameterResolverFactory parameterResolverFactory,
            Function<Object, MessageStream<?>> messageStreamResolver
    ) {
        return createHandlers(declaringType, method, parameterResolverFactory, messageStreamResolver)
                .stream()
                .findFirst();
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> List<MessageHandlingMember<T>> createHandlers(
            Class<T> declaringType,
            Method method,
            ParameterResolverFactory parameterResolverFactory,
            Function<Object, MessageStream<?>> messageStreamResolver
    ) {
        List<MessageHandlingMember<T>> handlers = new ArrayList<>();
        for (AnnotatedElement occurrence : AnnotatedHandlerElement.of(method)) {
            messageHandlerAttributesOf(occurrence)
                    .map(attr -> createMember(method, occurrence, attr, parameterResolverFactory,
                                              messageStreamResolver))
                    .ifPresent(member -> handlers.add((MessageHandlingMember<T>) member));
        }
        return handlers;
    }

    private static Optional<Map<String, Object>> messageHandlerAttributesOf(AnnotatedElement element) {
        if (element instanceof AnnotatedHandlerElement occurrence) {
            return Optional.of(occurrence.messageHandlerAttributes());
        }
        return findAnnotationAttributes(element, MessageHandler.class);
    }

    @SuppressWarnings("unchecked")
    private static MessageHandlingMember<?> createMember(Method method,
                                                         AnnotatedElement occurrence,
                                                         Map<String, Object> attributes,
                                                         ParameterResolverFactory parameterResolverFactory,
                                                         Function<Object, MessageStream<?>> messageStreamResolver) {
        return new MethodInvokingMessageHandlingMember<>(
                method,
                occurrence,
                (Class<? extends Message>) attributes.getOrDefault("messageType", Message.class),
                (Class<?>) attributes.getOrDefault("payloadType", Object.class),
                parameterResolverFactory,
                messageStreamResolver
        );
    }
}
