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

package org.axonframework.messaging.eventhandling.annotation;

import org.axonframework.messaging.LegacyHandlerInvoker;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.annotation.AnnotatedHandlerInspector;
import org.axonframework.messaging.core.annotation.AnnotationMessageTypeResolver;
import org.axonframework.messaging.core.annotation.ClasspathHandlerDefinition;
import org.axonframework.messaging.core.annotation.ClasspathParameterResolverFactory;
import org.axonframework.messaging.core.annotation.HandlerAttributes;
import org.axonframework.messaging.core.annotation.HandlerDefinition;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.core.interception.annotation.MessageHandlerInterceptorMemberChain;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventMessageHandler;
import org.axonframework.messaging.eventhandling.replay.GenericResetContext;
import org.axonframework.messaging.eventhandling.replay.ResetContext;
import org.axonframework.messaging.eventhandling.replay.ResetNotSupportedException;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.axonframework.common.BuilderUtils.assertNonNull;

/**
 * Adapter that turns any bean with {@link EventHandler} annotated methods into an {@link EventMessageHandler}.
 *
 * @author Allard Buijze
 * @see EventMessageHandler
 * @since 0.1
 */
public class AnnotationEventHandlerAdapter implements EventMessageHandler {

    private final AnnotatedHandlerInspector<Object> inspector;
    private final Class<?> listenerType;
    private final Object annotatedEventListener;
    private final MessageTypeResolver messageTypeResolver;

    /**
     * Wraps the given {@code annotatedEventListener}, allowing it to be subscribed to an Event Bus.
     *
     * @param annotatedEventListener the annotated event listener
     */
    public AnnotationEventHandlerAdapter(Object annotatedEventListener) {
        this(annotatedEventListener,
             ClasspathParameterResolverFactory.forClass(annotatedEventListener.getClass()),
             new AnnotationMessageTypeResolver());
    }

    /**
     * Wraps the given {@code annotatedEventListener}, allowing it to be subscribed to an Event Bus.
     *
     * @param annotatedEventListener the annotated event listener
     * @param messageTypeResolver    The {@link MessageTypeResolver} resolving the
     *                               {@link MessageType types} for
     *                               {@link EventMessage EventMessages}
     */
    public AnnotationEventHandlerAdapter(Object annotatedEventListener, MessageTypeResolver messageTypeResolver) {
        this(annotatedEventListener,
             ClasspathParameterResolverFactory.forClass(annotatedEventListener.getClass()),
             messageTypeResolver);
    }

    /**
     * Wraps the given {@code annotatedEventListener}, allowing it to be subscribed to an Event Bus. The given
     * {@code parameterResolverFactory} is used to resolve parameter values for handler methods.
     *
     * @param annotatedEventListener   the annotated event listener
     * @param parameterResolverFactory the strategy for resolving handler method parameter values
     * @param messageTypeResolver      The {@link MessageTypeResolver} resolving the
     *                                 {@link MessageType types} for
     *                                 {@link EventMessage EventMessages}
     */
    public AnnotationEventHandlerAdapter(Object annotatedEventListener,
                                         ParameterResolverFactory parameterResolverFactory,
                                         MessageTypeResolver messageTypeResolver) {
        this(annotatedEventListener,
             parameterResolverFactory,
             ClasspathHandlerDefinition.forClass(annotatedEventListener.getClass()),
             messageTypeResolver
        );
    }

    /**
     * Wraps the given {@code annotatedEventListener}, allowing it to be subscribed to an Event Bus. The given
     * {@code parameterResolverFactory} is used to resolve parameter values for handler methods. Handler definition is
     * used to create concrete handlers.
     *
     * @param annotatedEventListener   the annotated event listener
     * @param parameterResolverFactory the strategy for resolving handler method parameter values
     * @param handlerDefinition        the handler definition used to create concrete handlers
     * @param messageTypeResolver      The {@link MessageTypeResolver} resolving the
     *                                 {@link MessageType types} for
     *                                 {@link EventMessage EventMessages}
     */
    public AnnotationEventHandlerAdapter(Object annotatedEventListener,
                                         ParameterResolverFactory parameterResolverFactory,
                                         HandlerDefinition handlerDefinition,
                                         MessageTypeResolver messageTypeResolver) {
        assertNonNull(messageTypeResolver, "The Message Type Resolver may not be null");
        this.annotatedEventListener = annotatedEventListener;
        this.listenerType = annotatedEventListener.getClass();

        @SuppressWarnings("unchecked")
        Class<Object> cls = (Class<Object>)annotatedEventListener.getClass();

        this.inspector = AnnotatedHandlerInspector.inspectType(cls,
                                                               messageTypeResolver,
                                                               parameterResolverFactory,
                                                               handlerDefinition);
        this.messageTypeResolver = messageTypeResolver;
    }

    private static boolean supportsReplay(MessageHandlingMember<? super Object> h) {
        return h.attribute(HandlerAttributes.ALLOW_REPLAY).map(Boolean.TRUE::equals).orElse(Boolean.TRUE);
    }

    @Override
    public Object handleSync(EventMessage event, ProcessingContext context) throws Exception {
        Optional<MessageHandlingMember<? super Object>> handler =
                inspector.getHandlers(listenerType).stream()
                         .filter(h -> h.canHandle(event, context))
                         .findFirst();
        if (handler.isPresent()) {
            MessageHandlerInterceptorMemberChain<Object> interceptor = inspector.chainedInterceptor(listenerType);
            return LegacyHandlerInvoker.handleSync(interceptor, event, context, annotatedEventListener, handler.get());
        }
        return null;
    }

    @Override
    public boolean canHandle(EventMessage event, ProcessingContext context) {
        return inspector.getHandlers(listenerType).stream()
                        .anyMatch(h -> h.canHandle(event, context));
    }

    @Override
    public boolean canHandleType(Class<?> payloadType) {
        return inspector.getHandlers(listenerType).stream()
                        .filter(messageHandlingMember -> messageHandlingMember.canHandleMessageType(EventMessage.class))
                        .anyMatch(handler -> handler.canHandleType(payloadType));
    }

    @Override
    public Class<?> getTargetType() {
        return listenerType;
    }

    @Override
    public void prepareReset(ProcessingContext context) {
        prepareReset(null, context);
    }

    @Override
    public <R> void prepareReset(R resetContext, ProcessingContext context) {
        try {
            ResetContext resetMessage = asResetContext(resetContext);
            ProcessingContext messageProcessingContext = Message.addToContext(context, resetMessage);
            inspector.getHandlers(listenerType).stream()
                     .filter(h -> h.canHandle(resetMessage, messageProcessingContext))
                     .findFirst()
                     .ifPresent(messageHandlingMember -> messageHandlingMember.handle(resetMessage,
                                                                                      messageProcessingContext,
                                                                                      annotatedEventListener)
                                                                              .first()
                                                                              .asCompletableFuture()
                                                                              .join());
        } catch (Exception e) {
            throw new ResetNotSupportedException("An Error occurred while notifying handlers of the reset", e);
        }
    }

    /**
     * Returns the given {@code messageOrPayload} as a {@link ResetContext}. If {@code messageOrPayload} already
     * implements {@code ResetContext}, it is returned as-is. If it implements {@link Message}, {@code messageOrPayload}
     * will be cast to {@code Message} and current time is used to create a {@code ResetContext}. Otherwise, the given
     * {@code messageOrPayload} is wrapped into a {@link GenericResetContext} as its payload.
     *
     * @param messageOrPayload the payload to wrap or cast as {@link ResetContext}
     * @return a {@link ResetContext} containing given {@code messageOrPayload} as payload, or the
     * {@code messageOrPayload} if it already implements {@code ResetContext}.
     */
    private ResetContext asResetContext(Object messageOrPayload) {
        if (messageOrPayload instanceof ResetContext rc) {
            return rc;
        }
        if (messageOrPayload instanceof Message m) {
            return new GenericResetContext(m);
        }
        MessageType type = messageOrPayload == null
                ? new MessageType("empty.reset.context")
                : messageTypeResolver.resolveOrThrow(messageOrPayload);
        return new GenericResetContext(type, messageOrPayload);
    }

    /**
     * @implNote This implementation will consider any class that has a single handler that accepts a replay, to support
     * reset. If no handlers explicitly indicate whether replay is supported, the method returns {@code true}.
     */
    @Override
    public boolean supportsReset() {
        return inspector.getAllHandlers()
                        .values()
                        .stream()
                        .flatMap(Collection::stream)
                        .anyMatch(AnnotationEventHandlerAdapter::supportsReplay);
    }

    /**
     * Returns the set of event payload types that this adapter can handle.
     *
     * @return A set of classes representing the event types this adapter can handle.
     */
    @Override
    public Set<Class<?>> supportedEventTypes() {
        return inspector.getAllHandlers()
                        .values()
                        .stream()
                        .flatMap(Collection::stream)
                        .filter(handlingMember -> handlingMember.canHandleMessageType(EventMessage.class))
                        .map(MessageHandlingMember::payloadType)
                        .collect(Collectors.toSet());
    }
}
