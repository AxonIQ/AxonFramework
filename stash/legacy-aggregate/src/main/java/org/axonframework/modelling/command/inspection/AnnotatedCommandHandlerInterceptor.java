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

package org.axonframework.modelling.command.inspection;

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.annotation.InterceptorChainParameterResolverFactory;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;


/**
 * Annotated command handler interceptor on aggregate. Will invoke the delegate to the real interceptor method.
 *
 * @param <T> The type of entity to which the message handler will delegate the actual handling of the message
 * @author Milan Savic
 * @since 3.3
 */
public class AnnotatedCommandHandlerInterceptor<T> implements MessageHandlerInterceptor<CommandMessage> {

    private final MessageHandlingMember<T> delegate;
    private final T target;

    /**
     * Initializes annotated command handler interceptor with delegate handler and target on which handler is to be
     * invoked.
     *
     * @param delegate delegate command handler interceptor
     * @param target   on which command handler interceptor is to be invoked
     */
    public AnnotatedCommandHandlerInterceptor(MessageHandlingMember<T> delegate, T target) {
        this.delegate = delegate;
        this.target = target;
    }

    @Override
    public MessageStream<?> interceptOnHandle(
            CommandMessage message,
            ProcessingContext context,
            MessageHandlerInterceptorChain<CommandMessage> interceptorChain
    ) {
        return InterceptorChainParameterResolverFactory.callWithInterceptorChain(
                context,
                interceptorChain,
                (ct) -> delegate.canHandle(message, ct)
                    ? delegate.handle(message, ct, target).cast()
                    : interceptorChain.proceed(message, ct)
        );
    }
}
