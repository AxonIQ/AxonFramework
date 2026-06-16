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

package org.axonframework.messaging.core.interception.annotation;

import org.axonframework.common.Priority;
import org.axonframework.messaging.core.Context.ResourceKey;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.annotation.ParameterResolver;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Parameter resolver factory that adds support for resolving current {@link MessageHandlerInterceptorChain}. This can
 * function only if there is a {@link ProcessingContext}.
 *
 * @author Milan Savic
 * @since 3.3.0
 */
@Priority(Priority.FIRST)
public class InterceptorChainParameterResolverFactory
        implements ParameterResolverFactory, ParameterResolver<MessageHandlerInterceptorChain<?>> {

    private static final ResourceKey<MessageHandlerInterceptorChain<?>> INTERCEPTOR_CHAIN_KEY =
            ResourceKey.withLabel("InterceptorChain");

    /**
     * Invoke the given {@code action} with the given {@code interceptorChain} being available for parameter injection.
     * Because this parameter is not bound to a message, it is important to invoke handlers using this method.
     *
     * @param <M> the message type
     * @param processingContext the {@link ProcessingContext} to use
     * @param interceptorChain  the InterceptorChain to consider for injection as parameter
     * @param action            the action to invoke
     * @return the response from the invocation of given {@code action}
     */
    public static <M extends Message> MessageStream<?> callWithInterceptorChain(
            ProcessingContext processingContext,
            MessageHandlerInterceptorChain<M> interceptorChain,
            Function<ProcessingContext, MessageStream<?>> action
    ) {
        return action.apply(processingContext.withResource(INTERCEPTOR_CHAIN_KEY, interceptorChain));
    }

    /**
     * Creates a new {@link ProcessingContext} that carries the given {@code interceptorChain} as a resource, making it
     * available for parameter injection via {@link #currentInterceptorChain(ProcessingContext)}.
     * <p>
     * Use this in synchronous code paths where the chain must be injected without going through the async
     * {@link #callWithInterceptorChain(ProcessingContext, MessageHandlerInterceptorChain, java.util.function.Function)}
     * wrapper.
     *
     * @param processingContext the base context to extend
     * @param interceptorChain  the interceptor chain to register
     * @param <M>               the message type
     * @return a new context with the chain registered as a resource
     * @deprecated use {@link #callWithInterceptorChain(ProcessingContext, MessageHandlerInterceptorChain, Function)} instead.
     */
    @Deprecated(since = "5.2.0", forRemoval = true)
    public static <M extends Message> ProcessingContext contextWithInterceptorChain(
            ProcessingContext processingContext,
            MessageHandlerInterceptorChain<M> interceptorChain
    ) {
        return processingContext.withResource(INTERCEPTOR_CHAIN_KEY, interceptorChain);
    }

    /**
     * Returns the current interceptor chain registered in the given {@code processingContext}, or {@code null} if none
     * is present. The chain is stored via
     * {@link #callWithInterceptorChain(ProcessingContext, MessageHandlerInterceptorChain, Function)} and is available
     * for the duration of that call.
     *
     * @param processingContext the processing context to retrieve the interceptor chain from
     * @param <M>               the message type
     * @return the interceptor chain registered in the context, or {@code null} if not present
     */
    @Nullable
    public static <M extends Message> MessageHandlerInterceptorChain<M> currentInterceptorChain(
            ProcessingContext processingContext
    ) {
        //noinspection unchecked
        return (MessageHandlerInterceptorChain<M>) processingContext.getResource(INTERCEPTOR_CHAIN_KEY);
    }

    @Override
    public CompletableFuture<MessageHandlerInterceptorChain<?>> resolveParameterValue(ProcessingContext context) {
        MessageHandlerInterceptorChain<?> interceptorChain = context.getResource(INTERCEPTOR_CHAIN_KEY);
        if (interceptorChain != null) {
            return CompletableFuture.completedFuture(interceptorChain);
        }
        return CompletableFuture.failedFuture(new IllegalStateException("InterceptorChain should have been injected"));
    }

    @Override
    public boolean matches(ProcessingContext context) {
        return context.containsResource(INTERCEPTOR_CHAIN_KEY);
    }

    @Nullable
    @Override
    public ParameterResolver<MessageHandlerInterceptorChain<?>> createInstance(Executable executable,
                                                                               Parameter[] parameters,
                                                                               int parameterIndex) {
        if (MessageHandlerInterceptorChain.class.equals(parameters[parameterIndex].getType())) {
            return this;
        }
        return null;
    }
}
