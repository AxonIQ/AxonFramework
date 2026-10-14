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

import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;

/**
 * Interface to interact with a MessageHandlingMember instance through a chain of interceptors, which were used to build
 * up this chain. Unlike regular handlers, interceptors have the ability to act on messages on their way to the regular
 * handler, and have the ability to block these messages.
 *
 * @param <T> The type that declares the handlers in this chain
 * @author Allard Buijze
 * @since 4.4.0
 */
public interface MessageHandlerInterceptorMemberChain<T> {

    /**
     * Invokes the interceptors in this chain for the given {@code message}, ultimately invoking the given
     * {@code handler} unless an interceptor short-circuits the chain.
     *
     * @param message the message to intercept and, ultimately, handle
     * @param context the context under which the {@code message} is processed
     * @param target  the instance declaring the interceptors and the {@code handler}, or {@code null} when there is no
     *                instance to invoke them on, in which case this chain must only contain {@code static} members
     * @param handler the handler to invoke once every interceptor has proceeded the chain
     * @return a stream containing the result of handling the {@code message}
     */
    MessageStream<?> handle(Message message,
                            ProcessingContext context,
                            @Nullable T target,
                            MessageHandlingMember<? super T> handler);
}
