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
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;

import java.util.function.Function;

/**
 * Resolves the value returned by a message handler into the {@link MessageStream} that carries it to the dispatcher.
 * <p>
 * The {@code messageType} the handler is subscribed for is given alongside the {@code result}, as it determines how many
 * {@link Message Messages} the handler may produce. Handlers of
 * {@link org.axonframework.messaging.commandhandling.CommandMessage CommandMessages} produce exactly one result, so a
 * returned collection is carried as the payload of a single {@code Message}. Handlers of other message types may
 * produce several results, so a returned collection is spread over one {@code Message} per element.
 * <p>
 * This interface refines the {@link Function} that
 * {@link HandlerDefinition#createHandler(Class, java.lang.reflect.Method, ParameterResolverFactory, Function)} has
 * accepted since 5.0.0, so that it can be passed wherever that {@code Function} is expected. A
 * {@code HandlerDefinition} that forwards the {@code Function} it receives keeps the message type intact; one that
 * replaces it with a {@code Function} of its own falls back to {@link #apply(Object)}, which cannot know the message
 * type and therefore resolves as if the handler may produce several results.
 *
 * @author Mitchell Herrijgers
 * @see MessageStreamResolverUtils
 * @since 5.3.0
 */
@FunctionalInterface
public interface MessageStreamResolver extends Function<Object, MessageStream<?>> {

    /**
     * Resolves the given {@code result} of a handler subscribed for the given {@code messageType} into a
     * {@link MessageStream}.
     *
     * @param result      The value returned by
     *                    {@link MessageHandlingMember#handle(Message, ProcessingContext, Object)}, possibly
     *                    {@code null}.
     * @param messageType The type of {@link Message} the handler returning the {@code result} is subscribed for.
     * @return A {@code MessageStream} carrying the given {@code result}.
     */
    MessageStream<?> resolve(@Nullable Object result, Class<? extends Message> messageType);

    /**
     * Resolves the given {@code result} without knowing which type of {@link Message} the handler is subscribed for,
     * and thus how many {@code Messages} it may produce.
     * <p>
     * Exists to satisfy the {@link Function} contract for callers predating
     * {@link #resolve(Object, Class)}. Resolves as if the handler may produce several results, matching the behaviour
     * of those callers. Prefer {@code resolve(Object, Class)} whenever the message type is known.
     *
     * @param result The value returned by {@link MessageHandlingMember#handle(Message, ProcessingContext, Object)},
     *               possibly {@code null}.
     * @return A {@code MessageStream} carrying the given {@code result}.
     */
    @Override
    default MessageStream<?> apply(@Nullable Object result) {
        return resolve(result, Message.class);
    }
}
