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

package org.axonframework.modelling.entity;

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;

/**
 * Chain allowing an {@link EntityCommandHandlerInterceptor} to proceed dispatching a {@link CommandMessage} to an
 * entity, either to the next interceptor in the chain, or to the entity's own command handlers.
 * <p>
 * Unlike the generic {@link org.axonframework.messaging.core.MessageHandlerInterceptorChain}, this chain threads the
 * entity instance through {@link #proceed(CommandMessage, Object, ProcessingContext)} rather than capturing it by
 * closure.
 *
 * @param <E> the type of the entity modeled by this interface
 * @author Steven van Beelen
 * @see EntityCommandHandlerInterceptor
 * @since 5.3.2
 */
@FunctionalInterface
public interface EntityCommandHandlerInterceptorChain<E> {

    /**
     * Signals this interceptor chain to continue dispatching the {@code command}.
     *
     * @param command the command to pass down the chain
     * @param entity  the entity instance the {@code command} targets, or {@code null} when the {@code command} is a
     *                creational command for which no entity instance exists yet
     * @param context the {@link ProcessingContext} the {@code command} is processed in
     * @return a {@link MessageStream} containing the result of processing the given {@code command}
     */
    MessageStream<?> proceed(CommandMessage command, @Nullable E entity, ProcessingContext context);
}
