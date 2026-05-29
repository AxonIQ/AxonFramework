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

import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.common.infra.DescribableComponent;
import org.axonframework.messaging.commandhandling.CommandHandlingComponent;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.CommandResultMessage;
import org.axonframework.messaging.commandhandling.NoHandlerForCommandException;
import org.axonframework.messaging.core.DelayedMessageStream;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.modelling.EntityIdResolutionException;
import org.axonframework.modelling.EntityIdResolver;
import org.axonframework.modelling.repository.ManagedEntity;
import org.axonframework.modelling.repository.Repository;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * A {@link CommandHandlingComponent} that handles commands for an entity.
 * <p>
 * It will resolve the identifier of the entity through the provided {@link EntityIdResolver}, load it from the provided
 * {@link Repository} and delegate the handling of the command to the {@link EntityMetamodel} of the entity.
 *
 * @param <ID> The type of the identifier of the entity.
 * @param <E>  The type of the entity.
 * @author Mitchell Herrijgers
 * @since 5.0.0
 */
public class EntityCommandHandlingComponent<ID, E> implements CommandHandlingComponent, DescribableComponent {

    private final Repository<ID, E> repository;
    private final EntityMetamodel<E> metamodel;
    private final EntityIdResolver<ID> idResolver;

    /**
     * Creates a new {@link CommandHandlingComponent} that handles commands for the given entity type.
     *
     * @param repository The {@link Repository} to load the entity from.
     * @param metamodel  The {@link EntityMetamodel} to delegate the handling of the command to.
     * @param idResolver The {@link EntityIdResolver} to resolve the identifier of the entity.
     */
    public EntityCommandHandlingComponent(
            Repository<ID, E> repository,
            EntityMetamodel<E> metamodel,
            EntityIdResolver<ID> idResolver
    ) {
        this.repository = Objects.requireNonNull(repository, "The repository may not be null.");
        this.metamodel = Objects.requireNonNull(metamodel, "The metamodel may not be null.");
        this.idResolver = Objects.requireNonNull(idResolver, "The idResolver may not be null.");
    }

    @Override
    public Set<QualifiedName> supportedCommands() {
        return metamodel.supportedCommands();
    }

    @Override
    public MessageStream.Single<CommandResultMessage> handle(CommandMessage command,
                                                             ProcessingContext context) {
        try {
            ID id;
            try {
                id = idResolver.resolve(command, context);
            } catch (EntityIdResolutionException e) {
                // If there's no entity identifier to resolve, we might deal with a creational command.
                // If NoHandlerForCommandException is thrown it wasn't, so we fall back to EntityIdResolutionException.
                // Let's state we're going to create without loading first.
                context.putResource(EntityMetamodel.CREATE_WITHOUT_LOAD, true);
                return metamodel.handleCreate(command, context)
                                .onErrorContinue(
                                        throwable -> {
                                            context.removeResource(EntityMetamodel.CREATE_WITHOUT_LOAD);
                                            return throwable instanceof NoHandlerForCommandException
                                                    ? MessageStream.failed(e)
                                                    : MessageStream.failed(throwable);
                                        }
                                )
                                .first();
            }
            QualifiedName messageName = command.type().qualifiedName();

            var loadFuture = loadFromRepository(context, id, messageName);
            return DelayedMessageStream.createSingle(loadFuture.thenApply(me -> {
                try {
                    E entity = me.entity();
                    return entity != null
                            ? metamodel.handleInstance(command, entity, context).first()
                            : metamodel.handleCreate(command, context).first();
                } catch (Exception e) {
                    return MessageStream.failed(e);
                }
            }));
        } catch (Exception e) {
            return MessageStream.failed(e);
        }
    }

    /**
     * As creational command handlers do require the entity to be absent, and instance command handlers do require the
     * entity to be present, this method will load the entity from the repository with the right method. As load will
     * return a null {@link ManagedEntity#entity()} if it doesn't exist, and loadOrCreate will create an initial state
     * if it doesn't exist yet, we need to use the right method based on the {@code messageName} of the command being
     * handled.
     * <p>
     * If a command is creational, or both a creational and an instance command, we will call
     * {@link Repository#load(Object, ProcessingContext)}. If a command is an instance command, we will call
     * {@link Repository#loadOrCreate(Object, ProcessingContext)}. If it is creational, we will call
     * {@link Repository#load(Object, ProcessingContext)}.
     */
    private CompletableFuture<ManagedEntity<ID, E>> loadFromRepository(
            ProcessingContext context, ID id, QualifiedName messageName) {
        var isCreationalHandler = metamodel.supportedCreationalCommands().contains(messageName);
        var isInstanceHandler = metamodel.supportedInstanceCommands().contains(messageName);
        if (isCreationalHandler) {
            // With a creational command, we don't want to create an initial state if it doesn't exist yet.
            // As such, we call load.
            return repository.load(id, context);
        }
        if (isInstanceHandler) {
            // With an instance command, we want to load the entity if it exists, or create it if it doesn't as we always
            // need an entity to handle the command. As such, we call loadOrCreate.
            return repository.loadOrCreate(id, context);
        }
        // If the command is neither creational nor instance, we don't know what to do with it.
        throw new NoHandlerForCommandException(
                ("No handler for command [%s] in entity [%s] with id [%s]. "
                        + "Ensure that the command is either a creational or an instance command.").formatted(
                        messageName, metamodel.entityType().getName(), id));
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeProperty("repository", repository);
        descriptor.describeProperty("metamodel", metamodel);
        descriptor.describeProperty("idResolver", idResolver);
    }
}