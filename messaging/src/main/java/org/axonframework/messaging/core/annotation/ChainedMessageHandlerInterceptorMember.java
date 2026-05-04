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

import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.interception.annotation.MessageHandlerInterceptorMemberChain;
import org.axonframework.messaging.core.interception.annotation.NoMoreInterceptors;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

import java.util.Iterator;


/**
 * A {@link MessageHandlerInterceptorMemberChain} implementation that constructs a chain of instances of itself based on
 * a given {@code iterator} of {@link MessageHandlingMember MessageHandlingMembers}.
 *
 * @param <T> The type that declares the handlers in this chain.
 * @author Allard Buijze
 * @since 4.4.0, 5.2.0
 */
public class ChainedMessageHandlerInterceptorMember<T> implements MessageHandlerInterceptorMemberChain<T> {

    private final MessageHandlingMember<? super T> delegate;
    private final MessageHandlerInterceptorMemberChain<T> next;

    /**
     * Constructs a chained message handling interceptor, building a chain from the given {@code iterator}.
     * <p>
     * The {@code iterator} should <em>at least</em>> have a single {@link MessageHandlingMember}. If there are more
     * {@code MessageHandlingMembers} present in the given {@code iterator}, this constructor will be invoked again.
     *
     * @param iterator the {@code MessageHandlingMembers} from which to construct the chain.
     */
    public ChainedMessageHandlerInterceptorMember(Iterator<MessageHandlingMember<? super T>> iterator) {
        this.delegate = iterator.next();
        this.next = iterator.hasNext()
                ? new ChainedMessageHandlerInterceptorMember<>(iterator)
                : NoMoreInterceptors.instance();
    }

    @Override
    public MessageStream<?> handle(Message message,
                                   ProcessingContext context,
                                   T target,
                                   MessageHandlingMember<? super T> handler) {
        MessageHandlerInterceptorChain<Message> chain =
                (msg, ctx) -> next.handle(msg, ctx, target, handler);
        return InterceptorChainParameterResolverFactory.callWithInterceptorChain(
                context, chain, ctx -> doHandle(message, ctx, target, handler)
        );
    }

    private MessageStream<?> doHandle(Message message,
                                      ProcessingContext context,
                                      T target,
                                      MessageHandlingMember<? super T> handler) {
        return delegate.canHandle(message, context)
                ? delegate.handle(message, context, target)
                : next.handle(message, context, target, handler);
    }

    /**
     * @deprecated in favor of {@link #handle(Message, ProcessingContext, Object, MessageHandlingMember)}
     */
    @Override
    @Deprecated(forRemoval = true, since = "5.2.0")
    public Object handleSync(Message message,
                             ProcessingContext context,
                             T target,
                             MessageHandlingMember<? super T> handler) throws Exception {
        MessageHandlerInterceptorChain<Message> chain = (msg, ctx) -> next.handle(msg, ctx, target, handler);
        ProcessingContext contextWithChain =
                InterceptorChainParameterResolverFactory.contextWithInterceptorChain(context, chain);
        return doHandleSync(message, contextWithChain, target, handler);
    }

    private Object doHandleSync(Message message,
                                ProcessingContext context,
                                T target, MessageHandlingMember<? super T> handler)
            throws Exception {
        return delegate.canHandle(message, context)
                ? delegate.handleSync(message, context, target)
                : next.handleSync(message, context, target, handler);
    }
}
