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

import org.axonframework.common.Priority;
import org.axonframework.messaging.core.Context.ResourceKey;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.ResourceOverridingProcessingContext;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.util.concurrent.Callable;
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

    private static final ThreadLocal<MessageHandlerInterceptorChain<?>> CURRENT = new ThreadLocal<>();
    private static final ResourceKey<MessageHandlerInterceptorChain<?>> INTERCEPTOR_CHAIN_KEY =
            ResourceKey.withLabel("InterceptorChain");

    /**
     * Invoke the given {@code action} with the given {@code interceptorChain} being available for parameter injection.
     * Because this parameter is not bound to a message, it is important to invoke handlers using this method.
     *
     * @param interceptorChain The InterceptorChain to consider for injection as parameter
     * @param action           The action to invoke
     * @param <R>              The type of response expected from the invocation
     * @return The response from the invocation of given {@code action}
     * @throws Exception any exception that occurs while invoking given {@code action}
     */
    public static <R> R callWithInterceptorChainSync(MessageHandlerInterceptorChain<?> interceptorChain,
                                                     Callable<R> action) throws Exception {
        MessageHandlerInterceptorChain<?> previous = CURRENT.get();
        CURRENT.set(interceptorChain);
        try {
            return action.call();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

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
        ProcessingContext newProcessingContext = new ResourceOverridingProcessingContext<>(processingContext,
                                                                                           INTERCEPTOR_CHAIN_KEY,
                                                                                           interceptorChain);
        return action.apply(newProcessingContext);
    }

    /**
     * Returns the current interceptor chain registered for injection as a parameter. Will return the instance passed in
     * {@link #callWithInterceptorChainSync(MessageHandlerInterceptorChain, Callable)}. When invoked outside the scope
     * of that method, this will return {@code null}.
     *
     * @return the InterceptorChain instance passed in
     * {@link #callWithInterceptorChainSync(MessageHandlerInterceptorChain, Callable)}
     */
    public static MessageHandlerInterceptorChain<?> currentInterceptorChain() {
        return CURRENT.get();
    }

    public static <M extends Message> MessageHandlerInterceptorChain<M> currentInterceptorChain(
            ProcessingContext processingContext
    ) {
        //noinspection unchecked
        return (MessageHandlerInterceptorChain<M>) processingContext.getResource(INTERCEPTOR_CHAIN_KEY);
    }

    @Override
    public CompletableFuture<MessageHandlerInterceptorChain<?>> resolveParameterValue(ProcessingContext context) {
        // TODO #3485 - The MessageHandlerInterceptorChain should be registered as a resource to the ProcessingContext
        //  and retrieved from the given context here upon resolution i.o. using a thread local.
        MessageHandlerInterceptorChain<?> interceptorChain =
                (context == null)  ? null : context.getResource(INTERCEPTOR_CHAIN_KEY);
        if (interceptorChain == null) {
            interceptorChain = CURRENT.get();
        }
        if (interceptorChain != null) {
            return CompletableFuture.completedFuture(interceptorChain);
        }
        return CompletableFuture.failedFuture(new IllegalStateException("InterceptorChain should have been injected"));
    }

    @Override
    public boolean matches(ProcessingContext context) {
        return CURRENT.get() != null
                || (context != null && context.containsResource(INTERCEPTOR_CHAIN_KEY));
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
