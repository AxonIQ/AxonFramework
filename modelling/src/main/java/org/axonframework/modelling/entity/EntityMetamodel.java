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

import org.axonframework.common.annotation.Internal;
import org.axonframework.common.infra.DescribableComponent;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.CommandResultMessage;
import org.axonframework.messaging.core.Context;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.modelling.EntityEvolver;

import java.util.Set;

/**
 * The messaging metamodel of entity type {@code E}, containing the information needed to handle commands and events. An
 * {@link EntityMetamodel} can be created through the builder by using the {@link #forEntityType(Class)} method. The
 * metamodel can then be used to handle commands and events for an entity. If the entity already exists, the
 * {@link #handleInstance} method should be used to handle commands for the entity. If the entity is new, the
 * {@link #handleCreate} method should be used to handle commands for creating the entity.
 *
 * @param <E> The type of the entity supported by this metamodel.
 * @author Mitchell Herrijgers
 * @since 5.0.0
 */
public interface EntityMetamodel<E> extends EntityEvolver<E>, DescribableComponent {

    /**
     * A {@link org.axonframework.messaging.core.Context.ResourceKey} for a {@code boolean} signaling when
     * {@link #handleCreate(CommandMessage, ProcessingContext)} is invoked <b>without</b> trying to load the entity
     * first.
     * <p>
     * This flag might be used downstream to derive the information required to correctly store the created entity.
     *
     * @since 5.1.1
     */
    @Internal
    Context.ResourceKey<Boolean> CREATE_WITHOUT_LOAD = Context.ResourceKey.withLabel("createWithoutLoad");

    /**
     * Starts a new {@link EntityMetamodelBuilder} for the given entity type. The builder can be used to add command
     * handlers and child entities to the metamodel.
     *
     * @param entityType The type of the entity to create a metamodel for.
     * @param <E>        The type of the entity to create a metamodel for.
     * @return A new {@link EntityMetamodelBuilder} for the given entity type.
     */
    static <E> EntityMetamodelBuilder<E> forEntityType(Class<E> entityType) {
        return ConcreteEntityMetamodel.forEntityClass(entityType);
    }

    /**
     * Starts a new {@link PolymorphicEntityMetamodelBuilder} for the given entity type. The builder can be used to add
     * command handlers and child entities to the metamodel, specifically for polymorphic entities. The builder also
     * supports adding concrete entity types that extend the given entity type, through
     * {@link PolymorphicEntityMetamodelBuilder#addConcreteType(EntityMetamodel)}. The required concrete metamodel can
     * be created with {@link #forEntityType(Class)}.
     *
     * @param entityType The type of the entity to create a metamodel for.
     * @param <E>        The type of the entity to create a metamodel for.
     * @return A new {@link PolymorphicEntityMetamodelBuilder} for the given entity type.
     */
    static <E> PolymorphicEntityMetamodelBuilder<E> forPolymorphicEntityType(Class<E> entityType) {
        return PolymorphicEntityMetamodel.forSuperType(entityType);
    }

    /**
     * Returns the {@link Class} of the entity this metamodel describes.
     *
     * @return The {@link Class} of the entity this metamodel describes.
     */
    Class<E> entityType();

    /**
     * Handles the given {@link CommandMessage} as the creation of a new entity. It is up to the registered command
     * handler to create the entity.
     * <p>
     * This method is used to handle commands for new entities. If you want to handle commands for existing entities,
     * use the {@link #handleInstance} method instead. If the command handler is only known as an instance command
     * handler and this method is called, it will result in a failed message stream.
     *
     * @param message The {@link CommandMessage} to handle.
     * @param context The {@link ProcessingContext} for the command.
     * @return A stream with a message containing the result of the command handling, which may be a
     * {@link CommandResultMessage} or an error message.
     */
    MessageStream.Single<CommandResultMessage> handleCreate(
            CommandMessage message, ProcessingContext context
    );

    /**
     * Handles the given {@link CommandMessage} for the given {@code entity}. If any of its children can handle the
     * command, it will be delegated to them. Otherwise, the command will be handled by this metamodel. If the command
     * is not handled by this metamodel or any of its children, an {@link MessageStream#failed(Throwable)} will be
     * returned.
     * <p>
     * This method is used to handle commands for existing entities. If you want to handle commands for new entities,
     * use the {@link #handleCreate} method instead. If the command handler is only known as a creational command
     * handler and this method is called, it will result in a failed message stream.
     *
     * @param message The {@link CommandMessage} to handle.
     * @param entity  The entity instance to handle the command for.
     * @param context The {@link ProcessingContext} for the command.
     * @return A stream with a message containing the result of the command handling, which may be a
     * {@link CommandResultMessage} or an error message.
     */
    MessageStream.Single<CommandResultMessage> handleInstance(
            CommandMessage message, E entity, ProcessingContext context
    );

    /**
     * Returns the set of all {@link QualifiedName QualifiedNames} that this metamodel supports for creating entities.
     * These are the command types that can be used to create an entity of this type through the {@link #handleCreate}
     * method.
     *
     * @return A set of {@link QualifiedName} instances representing the supported command names.
     */
    Set<QualifiedName> supportedCreationalCommands();

    /**
     * Returns the set of all {@link QualifiedName QualifiedNames} that this metamodel supports for instance commands.
     * These are the command types that can be used on entity instances of this type through the {@link #handleInstance}
     * method.
     *
     * @return A set of {@link QualifiedName} instances representing the supported command names.
     */
    Set<QualifiedName> supportedInstanceCommands();

    /**
     * Returns the set of all {@link QualifiedName QualifiedNames} that this metamodel supports for command handlers,
     * both creational and instance commands. This is the union of the {@link #supportedCreationalCommands()} and
     * {@link #supportedInstanceCommands()} methods.
     *
     * @return A set of {@link QualifiedName} instances representing the supported command names.
     */
    Set<QualifiedName> supportedCommands();
}
