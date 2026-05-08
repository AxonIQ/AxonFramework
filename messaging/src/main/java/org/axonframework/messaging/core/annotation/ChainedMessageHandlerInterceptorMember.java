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
 * @since 4.4.0
 */
public class ChainedMessageHandlerInterceptorMember<T> implements MessageHandlerInterceptorMemberChain<T> {

    private final MessageHandlingMember<? super T> delegate;
    private final MessageHandlerInterceptorMemberChain<T> next;

    /**
     * Constructs a chained message handling interceptor for the given {@code handlerType}, constructing a chain from
     * the given {@code iterator}.
     * <p>
     * The {@code iterator} should <em>at least</em>> have a single {@link MessageHandlingMember}. If there are more
     * {@code MessageHandlingMembers} present in the given {@code iterator}, this constructor will be invoked again.
     *
     * @param handlerType The type for which to construct a message handler interceptor chain.
     * @param iterator    The {@code MessageHandlingMembers} from which to construct the chain.
     */
    public ChainedMessageHandlerInterceptorMember(Class<?> handlerType,
                                                  Iterator<MessageHandlingMember<? super T>> iterator) {
        this.delegate = iterator.next();
        this.next = iterator.hasNext()
                ? new ChainedMessageHandlerInterceptorMember<>(handlerType, iterator)
                : NoMoreInterceptors.instance();
    }

    @Override
    public MessageStream<?> handle(Message message,
                                   ProcessingContext context,
                                   T target,
                                   MessageHandlingMember<? super T> handler) {

        // TODO #3485 - Implement this accordingly for annotated interception.
        //  Or, fully replace this for MessageHandlingComponent decoration instead.
        return doHandle(message, context, target, handler);
        /*
        Why is this called using an interceptor chain? Do handle effectively does the same!
        Rewrite this code...

        return InterceptorChainParameterResolverFactory.callWithInterceptorChain(
                context,
                (ctx) -> next.handle(message, ctx, target, handler),
                (ctx) -> doHandle(message, ctx, target, handler)
        );
         */
    }

    private MessageStream<?> doHandle(Message message,
                                      ProcessingContext context,
                                      T target,
                                      MessageHandlingMember<? super T> handler) {
        return delegate.canHandle(message, context)
                ? delegate.handle(message, context, target)
                : next.handle(message, context, target, handler);
    }

    @Override
    public Object handleSync(Message message,
                             ProcessingContext context,
                             T target,
                             MessageHandlingMember<? super T> handler) throws Exception {
        // TODO #3485 - Implement this accordingly for annotated interception.
        //  Or, fully replace this for MessageHandlingComponent decoration instead.
        return doHandleSync(message, context, target, handler);
        /*
        return InterceptorChainParameterResolverFactory.callWithInterceptorChainSync(
                (ctx) -> next.handleSync(message, ctx, target, handler),
                () -> doHandleSync(message, context, target, handler)
        );
         */
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
