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
import org.axonframework.messaging.core.DelayedMessageStream;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

/**
 * Implementation of a {@link MessageHandlingMember} that is used to invoke message handler methods on the target type.
 *
 * @param <T> the target type.
 * @author Allard Buijze
 * @since 3.0.0
 */
public class MethodInvokingMessageHandlingMember<T> implements MessageHandlingMember<T> {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final Class<?> payloadType;
    private final int parameterCount;
    private final ParameterResolver<?>[] parameterResolvers;
    private final Function<Object, MessageStream<?>> returnTypeConverter;
    private final Method method;
    private final Class<? extends Message> messageType;
    private final HandlerAttributes attributes;

    /**
     * Initializes a new instance that will invoke the given {@code executable} (method) on a target to handle a message
     * of the given {@code messageType}.
     *
     * @param method                   the method to invoke on a target
     * @param messageType              the type of message that is expected by the target method
     * @param explicitPayloadType      the expected message payload type
     * @param parameterResolverFactory factory used to resolve method parameters
     * @param returnTypeConverter      the return type converter to apply to the handling result
     */
    public MethodInvokingMessageHandlingMember(Method method,
                                               Class<? extends Message> messageType,
                                               Class<?> explicitPayloadType,
                                               ParameterResolverFactory parameterResolverFactory,
                                               Function<Object, MessageStream<?>> returnTypeConverter) {
        this.messageType = messageType;
        this.method = ReflectionUtils.ensureAccessible(method);
        this.returnTypeConverter = returnTypeConverter;
        Parameter[] parameters = method.getParameters();
        this.parameterCount = method.getParameterCount();
        parameterResolvers = new ParameterResolver[parameterCount];
        Class<?> supportedPayloadType = explicitPayloadType;
        for (int i = 0; i < parameterCount; i++) {
            parameterResolvers[i] = parameterResolverFactory.createInstance(method, parameters, i);
            if (parameterResolvers[i] == null) {
                throw new UnsupportedHandlerException(
                        "Unable to resolve parameter " + i + " (" + parameters[i].getType().getSimpleName() +
                                ") in handler " + method.toGenericString() + ".", method);
            }
            if (supportedPayloadType.isAssignableFrom(parameterResolvers[i].supportedPayloadType())) {
                supportedPayloadType = parameterResolvers[i].supportedPayloadType();
            } else if (!parameterResolvers[i].supportedPayloadType().isAssignableFrom(supportedPayloadType)) {
                throw new UnsupportedHandlerException(String.format(
                        "The method %s seems to have parameters that put conflicting requirements on the payload type" +
                                " applicable on that method: %s vs %s", method.toGenericString(),
                        supportedPayloadType, parameterResolvers[i].supportedPayloadType()), method);
            }
        }
        this.payloadType = supportedPayloadType;
        this.attributes = new AnnotatedHandlerAttributes(method);
    }

    @Override
    public Class<?> payloadType() {
        return payloadType;
    }

    @Override
    public boolean canHandle(Message message, ProcessingContext context) {
        ProcessingContext contextWithMessage = Message.addToContext(context, message);
        return typeMatches(message)
                && payloadType.isAssignableFrom(message.payloadType())
                && parametersMatch(message, contextWithMessage);
    }

    @Override
    public boolean canHandleType(Class<?> payloadType) {
        return this.payloadType.isAssignableFrom(payloadType);
    }

    @Override
    public boolean canHandleMessageType(Class<? extends Message> messageType) {
        return this.messageType.isAssignableFrom(messageType);
    }

    /**
     * Checks if this member can handle the type of the given {@code message}. This method does not check if the
     * parameter resolvers of this member are compatible with the given message. Use
     * {@link #parametersMatch(Message, ProcessingContext)} for that.
     *
     * @param message the message to check for
     * @return {@code true} if this member can handle the message type. {@code false} otherwise
     */
    protected boolean typeMatches(Message message) {
        return messageType.isInstance(message);
    }

    /**
     * Checks if the parameter resolvers of this member are compatible with the given {@code message}.
     *
     * @param message the message to check for
     * @param processingContext the {@link ProcessingContext} to use
     * @return {@code true} if the parameter resolvers can handle this message. {@code false} otherwise
     */
    protected boolean parametersMatch(Message message, ProcessingContext processingContext) {
        for (ParameterResolver<?> resolver : parameterResolvers) {
            if (!resolver.matches(processingContext)) {
                logger.debug("Parameter Resolver [{}] did not match message [{}] for payload type [{}].",
                             resolver.getClass(), message, message.payloadType());
                return false;
            }
        }
        return true;
    }

    @Override
    public Object handleSync(Message message,
                             ProcessingContext context,
                             @Nullable T target) throws Exception {
        try {
            MessageStream.Entry<?> resultEntry = handle(message, context, target).first()
                                                                                 .asCompletableFuture()
                                                                                 .get();
            return resultEntry != null ? resultEntry.message().payload() : null;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception ex) {
                throw ex;
            } else {
                throw e;
            }
        }
    }

    @Override
    public MessageStream<?> handle(Message message,
                                   ProcessingContext context,
                                   @Nullable T target) {
        ProcessingContext contextWithMessage = Message.addToContext(context, message);
        CompletableFuture<Object[]> parametersFuture = resolveParameterValues(contextWithMessage);

        CompletableFuture<MessageStream<?>> invocationFuture = parametersFuture.handle((params, throwable) -> {
            if (throwable != null) {
                logger.warn("Method [{}] failed handling message with identifier [{}].", method, message.identifier());
                return MessageStream.failed(throwable);
            }
            try {
                Object result = method.invoke(target, params);
                return returnTypeConverter.apply(result);
            } catch (IllegalAccessException | InvocationTargetException e) {
                logger.warn("Method [{}] failed handling message with identifier [{}].", method, message.identifier());
                if (e.getCause() instanceof Exception) {
                    return MessageStream.failed(e.getCause());
                } else if (e.getCause() instanceof Error) {
                    return MessageStream.failed(e.getCause());
                }
                return MessageStream.failed(new MessageHandlerInvocationException(
                        String.format("Error handling an object of type [%s]", messageType), e
                ));
            }
        });

        // Return a delayed MessageStream that will delegate to the result when available
        @SuppressWarnings("unchecked")
        CompletableFuture<MessageStream<Message>> castedFuture =
                (CompletableFuture<MessageStream<Message>>) (CompletableFuture<?>) invocationFuture;
        return DelayedMessageStream.create(castedFuture);
    }

    /**
     * Resolves all parameter values asynchronously by calling
     * {@link ParameterResolver#resolveParameterValue(ProcessingContext)} on each resolver. All futures are composed
     * using {@link CompletableFuture#allOf(CompletableFuture[])}.
     * <p>
     * This method is non-blocking. The returned {@link CompletableFuture} completes when all parameter resolvers have
     * completed their async resolution.
     *
     * @param context The processing context to resolve parameters from.
     * @return A {@link CompletableFuture} that completes with an array of resolved parameter values.
     */
    private CompletableFuture<Object[]> resolveParameterValues(ProcessingContext context) {
        CompletableFuture<?>[] futures = Arrays.stream(parameterResolvers)
                                               .map(resolver -> tryResolveParameterValue(resolver, context))
                                               .toArray(CompletableFuture[]::new);

        return CompletableFuture.allOf(futures)
                                .thenApply(v -> Arrays.stream(futures)
                                                      .map(CompletableFuture::resultNow)
                                                      .toArray());
    }

    private CompletableFuture<?> tryResolveParameterValue(ParameterResolver<?> parameterResolver,
                                                          ProcessingContext context) {
        try {
            return parameterResolver.resolveParameterValue(context);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public <R> Optional<R> attribute(String attributeKey) {
        return Optional.ofNullable(attributes.get(attributeKey));
    }

    @SuppressWarnings("unchecked")
    @Override
    public <H> Optional<H> unwrap(Class<H> handlerType) {
        if (handlerType.isInstance(this)) {
            return (Optional<H>) Optional.of(this);
        }
        if (handlerType.isInstance(method)) {
            return (Optional<H>) Optional.of(method);
        }
        return Optional.empty();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + " " + method.toGenericString();
    }

    @Override
    public int hashCode() {
        return Objects.hash(method);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        MethodInvokingMessageHandlingMember<?> that = (MethodInvokingMessageHandlingMember<?>) o;
        return method.equals(that.method);
    }
}
