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

package org.axonframework.messaging;

import org.axonframework.common.FutureUtils;
import org.axonframework.common.annotation.Internal;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;
import org.axonframework.messaging.core.interception.annotation.MessageHandlerInterceptorMemberChain;
import org.axonframework.messaging.core.unitofwork.LegacyMessageSupportingContext;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;

/**
 * Bridges the asynchronous message handling API onto the synchronous contract the stashed legacy components are built
 * on.
 * <p>
 * Components in this module predate the {@link MessageStream}-based handler API and expect a handler invocation to
 * return its result directly. This utility performs that adaptation in a single place, by invoking the asynchronous
 * {@code handle} method and blocking on the resulting stream, so no synchronous handler method has to exist on the
 * framework's own interfaces.
 * <p>
 * Blocking happens through {@link FutureUtils#joinAndUnwrap(java.util.concurrent.CompletableFuture)}, which applies a
 * safety-net timeout and rethrows the original failure rather than a wrapping
 * {@link java.util.concurrent.CompletionException}. Preserving the original exception type matters here, because the
 * surrounding legacy code catches specific business exceptions thrown from handler methods.
 * <p>
 * Every call site of this class is a blocking point that disappears once the surrounding legacy API becomes
 * async-native.
 *
 * @author Axon Framework
 * @since 5.4.0
 */
@Internal
public final class LegacyHandlerInvoker {

    private LegacyHandlerInvoker() {
        // Utility class, not meant to be instantiated.
    }

    /**
     * Invokes the given {@code handler} for the given {@code message} on the given {@code target}, blocking until the
     * result is available.
     *
     * @param handler the handling member to invoke
     * @param message the message to handle
     * @param context the context in which the message is being handled
     * @param target  the instance to invoke the handler on
     * @param <T>     the type declaring the handler
     * @return the handling result, or {@code null} if the handler produced no result
     * @throws Exception any exception thrown by the handler
     */
    public static <T> Object handleSync(MessageHandlingMember<? super T> handler,
                                        Message message,
                                        ProcessingContext context,
                                        @Nullable T target) throws Exception {
        return awaitResult(handler.handle(message, context, target));
    }

    /**
     * Passes the given {@code message} through the given interceptor {@code chain} to the given {@code handler} on the
     * given {@code target}, blocking until the result is available.
     *
     * @param chain   the interceptor chain to pass the message through
     * @param message the message to handle
     * @param context the context in which the message is being handled
     * @param target  the instance to invoke the interceptors and handler on
     * @param handler the handler to invoke once all interceptors have received the message
     * @param <T>     the type declaring the handler
     * @return the handling result, or {@code null} if the invocation produced no result
     * @throws Exception any exception thrown by the handler or any of the interceptors
     */
    public static <T> Object handleSync(MessageHandlerInterceptorMemberChain<T> chain,
                                        Message message,
                                        ProcessingContext context,
                                        T target,
                                        MessageHandlingMember<? super T> handler) throws Exception {
        return awaitResult(chain.handle(message, context, target, handler));
    }

    /**
     * Passes the given {@code message} through the given interceptor {@code chain} to the given {@code handler} on the
     * given {@code target}, blocking until the result is available.
     * <p>
     * Intended for callers that have no {@link ProcessingContext} at hand; a
     * {@link LegacyMessageSupportingContext} wrapping the {@code message} is used instead.
     *
     * @param chain   the interceptor chain to pass the message through
     * @param message the message to handle
     * @param target  the instance to invoke the interceptors and handler on
     * @param handler the handler to invoke once all interceptors have received the message
     * @param <T>     the type declaring the handler
     * @return the handling result, or {@code null} if the invocation produced no result
     * @throws Exception any exception thrown by the handler or any of the interceptors
     */
    public static <T> Object handleSync(MessageHandlerInterceptorMemberChain<T> chain,
                                        Message message,
                                        T target,
                                        MessageHandlingMember<? super T> handler) throws Exception {
        return handleSync(chain, message, new LegacyMessageSupportingContext(message), target, handler);
    }

    /**
     * Blocks until the first entry of the given {@code stream} is available and returns its payload.
     *
     * @param stream the stream to await the first entry of
     * @return the payload of the stream's first entry, or {@code null} if the stream completed empty
     * @throws Exception any exception the stream completed exceptionally with
     */
    public static @Nullable Object awaitResult(MessageStream<?> stream) throws Exception {
        MessageStream.Entry<?> entry = FutureUtils.joinAndUnwrap(stream.first().asCompletableFuture());
        return entry != null ? entry.message().payload() : null;
    }
}
