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

import org.axonframework.common.ReflectionUtils;
import org.axonframework.common.StringUtils;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

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

    /**
     * {@inheritDoc}
     * <p>
     * An event handler whose payload parameter is a sealed type, and that declares neither an
     * {@link HandlerAttributes#EVENT_NAME event name} nor a {@link HandlerAttributes#EVENT_LOCAL_NAME local name},
     * yields one handler per concrete subtype of that sealed type, each with the subtype as its payload type. Every
     * occurrence of a repeated handler annotation is expanded on its own. An enum parameter is not expanded.
     * Event handler interceptors are not expanded, as they match payloads by type when invoked and subscribe to no
     * name.
     *
     * @throws UnsupportedHandlerException when the sealed type permits a subtype that is neither sealed, final nor an
     *                                     enum
     */
    @SuppressWarnings("unchecked")
    @Override
    public <T> List<MessageHandlingMember<T>> createHandlers(
            Class<T> declaringType,
            Method method,
            ParameterResolverFactory parameterResolverFactory,
            Function<Object, MessageStream<?>> messageStreamResolver
    ) {
        List<MessageHandlingMember<T>> handlers = new ArrayList<>();
        for (AnnotatedElement occurrence : HandlerAnnotationOccurrence.of(method)) {
            messageHandlerAttributesOf(occurrence).ifPresent(attr -> {
                MessageHandlingMember<?> member = createMember(method, occurrence, attr, null,
                                                               parameterResolverFactory, messageStreamResolver);
                List<Class<?>> subtypes = sealedEventSubtypesOf(method, attr, member);
                if (subtypes.isEmpty()) {
                    handlers.add((MessageHandlingMember<T>) member);
                }
                for (Class<?> subtype : subtypes) {
                    handlers.add((MessageHandlingMember<T>) createMember(method, occurrence, attr, subtype,
                                                                         parameterResolverFactory,
                                                                         messageStreamResolver));
                }
            });
        }
        return handlers;
    }

    private static List<Class<?>> sealedEventSubtypesOf(Method method,
                                                        Map<String, Object> attributes,
                                                        MessageHandlingMember<?> member) {
        Class<?> payloadType = member.payloadType();
        boolean expandable = EventMessage.class.isAssignableFrom(
                (Class<?>) attributes.getOrDefault("messageType", Message.class))
                && attributes.getOrDefault("payloadType", Object.class) == Object.class
                && payloadType.isSealed()
                && !payloadType.isEnum()
                && member.attribute(HandlerAttributes.EVENT_NAME).isPresent()
                && Stream.of(HandlerAttributes.EVENT_NAME, HandlerAttributes.EVENT_LOCAL_NAME)
                         .map(member::<String>attribute)
                         .noneMatch(value -> value.filter(StringUtils::nonEmpty).isPresent());
        if (!expandable) {
            return List.of();
        }
        try {
            return ReflectionUtils.collectSealedLeaves(payloadType);
        } catch (IllegalArgumentException e) {
            throw new UnsupportedHandlerException(
                    "The event handler " + method.toGenericString() + " cannot handle the sealed type "
                            + payloadType.getName() + ": " + e.getMessage(),
                    method
            );
        }
    }

    private static Optional<Map<String, Object>> messageHandlerAttributesOf(AnnotatedElement element) {
        if (element instanceof HandlerAnnotationOccurrence occurrence) {
            return Optional.of(occurrence.messageHandlerAttributes());
        }
        return findAnnotationAttributes(element, MessageHandler.class);
    }

    @SuppressWarnings("unchecked")
    private static MessageHandlingMember<?> createMember(Method method,
                                                         AnnotatedElement occurrence,
                                                         Map<String, Object> attributes,
                                                         @Nullable Class<?> payloadType,
                                                         ParameterResolverFactory parameterResolverFactory,
                                                         Function<Object, MessageStream<?>> messageStreamResolver) {
        return new MethodInvokingMessageHandlingMember<>(
                method,
                occurrence,
                (Class<? extends Message>) attributes.getOrDefault("messageType", Message.class),
                payloadType != null ? payloadType : (Class<?>) attributes.getOrDefault("payloadType", Object.class),
                parameterResolverFactory,
                messageStreamResolver
        );
    }
}
