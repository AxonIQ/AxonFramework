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

package org.axonframework.messaging.commandhandling.interception;

import org.axonframework.common.annotation.Internal;
import org.axonframework.messaging.commandhandling.CommandHandler;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.CommandResultMessage;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

/**
 * A {@link MessageHandlerInterceptorChain} that intercepts {@link CommandMessage CommandMessages} for
 * {@link CommandHandler CommandHandlers}.
 *
 * @author Allard Buijze
 * @author Simon Zambrovski
 * @author Steven van Beelen
 * @since 5.0.0
 */
@Internal
public class CommandMessageHandlerInterceptorChain implements MessageHandlerInterceptorChain<CommandMessage> {

    private final CommandHandler interceptingHandler;

    /**
     * Constructs a new {@code CommandMessageHandlerInterceptorChain} with a list of {@code interceptors} and an
     * {@code commandHandler}.
     *
     * @param interceptors   The list of handler interceptors that are part of this chain.
     * @param commandHandler The command handler to be invoked at the end of the interceptor chain.
     */
    public CommandMessageHandlerInterceptorChain(List<MessageHandlerInterceptor<? super CommandMessage>> interceptors,
                                                 CommandHandler commandHandler) {
        Iterator<MessageHandlerInterceptor<? super CommandMessage>> interceptorIterator =
                new LinkedList<>(interceptors).descendingIterator();
        CommandHandler handler = Objects.requireNonNull(commandHandler, "The Command Handler may not be null.");
        while (interceptorIterator.hasNext()) {
            handler = new InterceptingHandler(interceptorIterator.next(), handler);
        }
        this.interceptingHandler = handler;
    }

    @Override
    public MessageStream<?> proceed(CommandMessage command, ProcessingContext context) {
        try {
            return interceptingHandler.handle(command, context);
        } catch (Exception e) {
            return MessageStream.failed(e);
        }
    }

    private record InterceptingHandler(
            MessageHandlerInterceptor<? super CommandMessage> interceptor,
            CommandHandler next
    ) implements CommandHandler, MessageHandlerInterceptorChain<CommandMessage> {

        @Override
        public MessageStream.Single<CommandResultMessage> handle(CommandMessage command,
                                                                 ProcessingContext context) {
            //noinspection unchecked,rawtypes
            return interceptor.interceptOnHandle(command, context, (MessageHandlerInterceptorChain) this)
                              .first();
        }

        @Override
        public MessageStream<?> proceed(CommandMessage command,
                                        ProcessingContext context) {
            return next.handle(command, context);
        }
    }
}
