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

package org.axonframework.modelling.entity.child;

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.CommandResultMessage;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.modelling.EntityEvolver;
import org.axonframework.modelling.entity.EntityMetamodel;

import java.util.Set;

/**
 * Interface describing a child {@link EntityMetamodel} that can be handled in the context of its parent. Handling
 * commands for this metamodel is done in the context of the parent. This metamodel resolves the child from the given
 * parent and can then invoke the right child instance to handle the command.
 *
 * @param <C> the type of the child entity
 * @param <P> the type of the parent entity
 * @author Mitchell Herrijgers
 * @since 5.0.0
 */
public interface EntityChildMetamodel<C, P> extends EntityEvolver<P> {

    /**
     * Returns the set of all {@link QualifiedName QualifiedNames} that this metamodel supports for command handlers.
     *
     * @return a set of {@link QualifiedName} instances representing the supported command names
     */
    Set<QualifiedName> supportedCommands();

    /**
     * Checks if this child can handle the given {@link CommandMessage} for the given parent entity, and a child entity
     * is available to handle it.
     *
     * @param message      the {@link CommandMessage} to check
     * @param parentEntity the parent entity instance to check against
     * @param context      the {@link ProcessingContext} for the command
     * @return {@code true} if this child can handle the command, {@code false} otherwise
     */
    boolean canHandle(CommandMessage message, P parentEntity, ProcessingContext context);

    /**
     * Handles the given {@link CommandMessage} for the given child entity, using the provided parent entity.
     *
     * @param message      the {@link CommandMessage} to handle
     * @param parentEntity the child entity instance to handle the command for
     * @param context      the {@link ProcessingContext} for the command
     * @return the result of the command handling, which may be a {@link CommandResultMessage} or an error message
     */
    MessageStream.Single<CommandResultMessage> handle(CommandMessage message,
                                                      P parentEntity,
                                                      ProcessingContext context);

    /**
     * Returns the {@link Class} of the child entity this metamodel describes.
     *
     * @return the {@link Class} of the child entity this metamodel describes
     */
    Class<C> entityType();

    /**
     * Returns the {@link EntityMetamodel} of the child entity this metamodel describes.
     *
     * @return the {@link EntityMetamodel} of the child entity this metamodel describes
     */
    EntityMetamodel<C> entityMetamodel();

    /**
     * Starts a builder for a single child entity within the given parent entity type.
     *
     * @param parentClass the class of the parent entity
     * @param metamodel   the {@link EntityMetamodel} of the child entity
     * @param <C>         the type of the child entity
     * @param <P>         the type of the parent entity
     * @return a {@link SingleEntityChildMetamodel.Builder} for the child entity.
     */
    static <C, P> SingleEntityChildMetamodel.Builder<C, P> single(
            Class<P> parentClass,
            EntityMetamodel<C> metamodel
    ) {
        return SingleEntityChildMetamodel.forEntityModel(parentClass, metamodel);
    }

    /**
     * Starts a builder for a list of child entities within the given parent entity type.
     *
     * @param parentClass the class of the parent entity
     * @param metamodel   the {@link EntityMetamodel} of the child entity
     * @param <C>         the type of the child entity
     * @param <P>         the type of the parent entity
     * @return a {@link ListEntityChildMetamodel.Builder} for the child entity
     */
    static <C, P> ListEntityChildMetamodel.Builder<C, P> list(
            Class<P> parentClass,
            EntityMetamodel<C> metamodel
    ) {
        return ListEntityChildMetamodel.forEntityModel(parentClass, metamodel);
    }

    /**
     * Starts a builder for a map of child entities within the given parent entity type.
     *
     * @param parentClass the class of the parent entity
     * @param metamodel   the {@link EntityMetamodel} of the child entity
     * @param <K>         the type of the key of the {@link java.util.Map} containing the child entities
     * @param <C>         the type of the child entity
     * @param <P>         the type of the parent entity
     * @return a {@link MapEntityChildMetamodel.Builder} for the child entity
     */
    static <K, C, P> MapEntityChildMetamodel.Builder<K, C, P> map(
            Class<P> parentClass,
            EntityMetamodel<C> metamodel
    ) {
        return MapEntityChildMetamodel.forEntityModel(parentClass, metamodel);
    }
}
