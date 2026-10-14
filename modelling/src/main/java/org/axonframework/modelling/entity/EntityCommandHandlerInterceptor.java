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
 * Interceptor for {@link CommandMessage CommandMessages} dispatched to an entity through its {@link EntityMetamodel}.
 * <p>
 * Unlike the generic {@link org.axonframework.messaging.core.MessageHandlerInterceptor}, this interceptor is aware of
 * the entity instance the command targets, allowing it to base its decision (proceed, short-circuit, or otherwise
 * transform the outcome) on the entity's current state. This is comparable to an {@link EntityCommandHandler}, which
 * for the same reason also receives the entity instance directly.
 * <p>
 * Interceptors are registered on an {@link EntityMetamodelBuilder} through
 * {@link EntityMetamodelBuilder#commandHandlerInterceptor(EntityCommandHandlerInterceptor)}. Registration order defines
 * invocation order: the first registered interceptor is the outermost, invoked before any interceptor registered after
 * it, before this entity's own command handlers, and before any command is forwarded to a child entity. When this
 * entity is itself a child of another entity, interceptors registered on the parent entity are invoked before this
 * entity's own interceptors.
 * <p>
 * The
 * {@link org.axonframework.messaging.commandhandling.interception.annotation.CommandHandlerInterceptor
 * CommandHandlerInterceptor} annotation is the annotation-based counterpart of this interface. Methods annotated as
 * such on an entity class are detected and registered through the declarative configuration API.
 *
 * @param <E> The type of the entity modeled by this interface.
 * @author Steven van Beelen
 * @see EntityMetamodelBuilder#commandHandlerInterceptor(EntityCommandHandlerInterceptor)
 * @see EntityCommandHandler
 * @since 5.3.2
 */
public interface EntityCommandHandlerInterceptor<E> {

    /**
     * Intercepts the given {@code command} before it reaches this entity's command handlers, or before it is forwarded
     * to a child entity.
     * <p>
     * This method is responsible for continuation. To proceed with handling, invoke
     * {@link EntityCommandHandlerInterceptorChain#proceed(CommandMessage, Object, ProcessingContext)} on the given
     * {@code chain}. Not invoking {@code chain} short-circuits the dispatch process, and the {@link MessageStream}
     * returned by this method becomes the result of handling the {@code command}.
     *
     * @param command the {@link CommandMessage} being intercepted
     * @param entity  the entity instance the {@code command} targets, or {@code null} when the {@code command} is a
     *                creational command for which no entity instance exists yet
     * @param context the {@link ProcessingContext} the {@code command} is processed in
     * @param chain   the chain to proceed dispatching the {@code command}
     * @return the {@link MessageStream} resulting from either proceeding the {@code chain}, or a short-circuited result
     * produced by this interceptor
     */
    MessageStream<?> interceptOnHandle(CommandMessage command,
                                       @Nullable E entity,
                                       ProcessingContext context,
                                       EntityCommandHandlerInterceptorChain<E> chain);
}
