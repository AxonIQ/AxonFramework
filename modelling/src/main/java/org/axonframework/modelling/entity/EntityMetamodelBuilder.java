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

import org.axonframework.messaging.commandhandling.CommandHandler;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.modelling.EntityEvolver;
import org.axonframework.modelling.entity.child.EntityChildMetamodel;
import org.axonframework.modelling.repository.Repository;
import org.jspecify.annotations.Nullable;

/**
 * Builder for an {@link EntityMetamodel} instance, allowing the registration of command handlers, an entity evolver,
 * and child entities.
 * <p>
 * Entity models can handle two types of commands:
 * <ul>
 *     <li>Instance commands: Commands that are handled by an existing entity. These commands are registered by the
 *     {@link #instanceCommandHandler(QualifiedName, EntityCommandHandler)} method and will be invoked through
 *     {@link EntityMetamodel#handleInstance(CommandMessage, Object, ProcessingContext)}.
 *     These are comparable with static methods on an entity.</li>
 *     <li>Creational commands: Commands that are handled by a new entity. These commands are registered by the
 *     {@link #creationalCommandHandler(QualifiedName, CommandHandler)} method and will be invoked through
 *     {@link EntityMetamodel#handleCreate(CommandMessage, ProcessingContext)}.
 *     These are comparable with class instance methods on an entity.</li>
 * </ul>
 * <p>
 * You can also register the same {@link QualifiedName} for both instance and creational command handlers. In that case, the
 * instance command handler will be invoked when {@link EntityMetamodel#handleInstance(CommandMessage, Object, ProcessingContext)} is invoked, and the creational command handler will be
 * invoked when {@link EntityMetamodel#handleCreate(CommandMessage, ProcessingContext)} is invoked.
 * <p>
 * Upon a mismatch a {@link RuntimeException} will be thrown:
 * <ul>
 *     <li>If an instance command is invoked for a non-existing entity, an
 *     {@link EntityMissingForInstanceCommandHandlerException} will be thrown.</li>
 *     <li>If a creational command is invoked for an existing entity, an
 *     {@link EntityAlreadyExistsForCreationalCommandHandlerException} will be thrown.</li>
 * </ul>
 *
 * @param <E> the type of the entity modeled by this interface
 * @author Mitchell Herrijgers
 * @since 5.0.0
 */
public interface EntityMetamodelBuilder<E> {

    /**
     * Adds an {@link EntityCommandHandler} to this metamodel for the given {@link QualifiedName}. The command handler
     * will be invoked when a command with the given {@link QualifiedName} is received by the metamodel.
     * <p>
     * The entity should <b>not</b> exist for this command handler to be invoked. A non-null initial state is considered
     * to be an existing entity. As such, only register this command if the
     * {@link Repository#loadOrCreate(Object, ProcessingContext)} will always return a non-null entity for the given
     * {@code qualifiedName} when the entity is not yet created.
     * <p>
     * You can register the same {@link QualifiedName} for both instance and creational command handlers. See the
     * {@code EntityMetamodelBuilder} class documentation for more information on how this works.
     *
     * @param qualifiedName  the {@link QualifiedName} of the command this handler handles
     * @param messageHandler the {@link EntityCommandHandler} to handle the command
     * @return this builder for further configuration
     */
    EntityMetamodelBuilder<E> instanceCommandHandler(QualifiedName qualifiedName,
                                                     EntityCommandHandler<E> messageHandler);

    /**
     * Adds a {@link CommandHandler} to this metamodel for the given {@link QualifiedName} that is in charge of creation
     * of the entity. The handler is expected to create the entity.
     * <p>
     * The entity needs to not exist for this command handler to be invoked. A null initial state is considered to be a
     * non-existing entity. As such, only register this command if the
     * {@link Repository#load(Object, ProcessingContext)} will always return a null entity for the given
     * {@code qualifiedName} when the entity is not yet created.
     * <p>
     * You can register the same {@link QualifiedName} for both instance and creational command handlers. See the
     * {@code EntityMetamodelBuilder} class documentation for more information on how this works.
     * <p>
     * Note: If this metamodel is added as a child to another entity metamodel and has a creational command handler, it
     * will result in an exception as child entities cannot be created through a creational command handler.
     *
     * @param qualifiedName  the {@link QualifiedName} of the command this handler handles
     * @param messageHandler the {@link CommandHandler} to handle the command
     * @return this builder for further configuration
     */
    EntityMetamodelBuilder<E> creationalCommandHandler(QualifiedName qualifiedName,
                                                       CommandHandler messageHandler);

    /**
     * Adds a {@link EntityChildMetamodel} to this metamodel. The child metamodel will be used to handle commands for
     * the child entity. You can build a tree of entities by adding child metamodels to the parent metamodel. Children
     * command handlers take precedence over the parent command handlers. Event handlers will be invoked on both the
     * parent and child metamodels, but the child metamodels will be invoked first.
     * <p>
     * There are various types of children that can be added to an entity metamodel:
     * <ul>
     *     <li>Single instances: For a field with a single instance, use the {@link EntityChildMetamodel#single(Class, EntityMetamodel)}.</li>
     *     <li>List instances: For a {@link java.util.List list}, use the {@link EntityChildMetamodel#list(Class, EntityMetamodel)}.</li>
     * </ul>
     * <p>
     * When multiple children that can handle the same command are present, the children will be filtered based on
     * {@link EntityChildMetamodel#canHandle(CommandMessage, Object, ProcessingContext)}, and thus only invoke the child
     * with a matching entity. If no child can handle the command, an exception will be thrown. If after
     * filtering, multiple children can handle the command, an exception will be thrown.
     *
     * @param child the {@link EntityChildMetamodel} to add
     * @return this builder for further configuration
     */
    EntityMetamodelBuilder<E> addChild(EntityChildMetamodel<?, E> child);

    /**
     * Adds an {@link EntityCommandHandlerInterceptor} to this metamodel.
     * <p>
     * Registration order defines invocation order. The first registered interceptor is the outermost, invoked before
     * any interceptor registered after it, before this entity's own command handlers (both
     * {@link #instanceCommandHandler(QualifiedName, EntityCommandHandler) instance} and
     * {@link #creationalCommandHandler(QualifiedName, CommandHandler) creational}), and before any command is forwarded
     * to a child entity added through {@link #addChild(EntityChildMetamodel)}. When this metamodel is itself added as a
     * child to another entity metamodel, interceptors registered on the parent entity are invoked before this entity's
     * own interceptors.
     * <p>
     * Calling this method multiple times registers multiple interceptors. It does not override previously registered
     * interceptors.
     *
     * @param interceptor the {@link EntityCommandHandlerInterceptor} to register
     * @return this builder for further configuration
     */
    EntityMetamodelBuilder<E> commandHandlerInterceptor(EntityCommandHandlerInterceptor<E> interceptor);

    /**
     * Adds a {@link EntityEvolver} to this metamodel. This evolver will be called upon applying an event to the entity.
     * The evolver is responsible for evolving the entity state based on the event. Note that providing an evolver is
     * optional. However, if no evolver is provided, the entity state can only be changed through command handlers.
     * <p>
     * Calling this method a second time will override the previously set evolver.
     *
     * @param entityEvolver the {@link EntityEvolver} to use
     * @return this builder for further configuration
     */
    EntityMetamodelBuilder<E> entityEvolver(@Nullable EntityEvolver<E> entityEvolver);

    /**
     * Builds the {@link EntityMetamodel} instance based on the configuration of this builder. This method should be
     * called after all configuration is done.
     *
     * @return the {@link EntityMetamodel} instance based on the configuration of this builder
     */
    EntityMetamodel<E> build();
}