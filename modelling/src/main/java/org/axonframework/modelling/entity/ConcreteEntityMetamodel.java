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
import org.axonframework.messaging.commandhandling.CommandHandler;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.CommandResultMessage;
import org.axonframework.messaging.commandhandling.DuplicateCommandHandlerSubscriptionException;
import org.axonframework.messaging.commandhandling.NoHandlerForCommandException;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.modelling.EntityEvolver;
import org.axonframework.modelling.entity.child.ChildAmbiguityException;
import org.axonframework.modelling.entity.child.EntityChildMetamodel;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Implementation of the {@link EntityMetamodel} interface that enables the definition of command handlers and child
 * entities for a given entity type {@code E}. Optionally, an {@link EntityEvolver} can be provided to evolve the entity
 * state based on events. If no {@link EntityEvolver} is provided, state can exclusively be changed through command
 * handlers.
 * <p>
 * During the handling of commands, handlers defined in child entities take precedence over the parent entity's command
 * handlers. Event handlers are invoked on both the parent and child models, with child models being invoked first.
 *
 * @param <E> The type of the entity this metamodel describes.
 * @author Mitchell Herrijgers
 * @since 5.0.0
 */
public class ConcreteEntityMetamodel<E> implements DescribableComponent, EntityMetamodel<E> {

    private final Class<E> entityType;
    private final List<EntityChildMetamodel<?, E>> children = new LinkedList<>();
    private final Map<QualifiedName, EntityCommandHandler<E>> instanceCommandHandlers = new HashMap<>();
    private final Map<QualifiedName, CommandHandler> creationalCommandHandlers = new HashMap<>();
    private final @Nullable EntityEvolver<E> entityEvolver;
    private final Set<QualifiedName> supportedCommandNames = new HashSet<>();
    private final Set<QualifiedName> supportedInstanceCommandNames = new HashSet<>();
    private final Set<QualifiedName> supportedCreationalCommandNames = new HashSet<>();

    private ConcreteEntityMetamodel(Class<E> entityType,
                                    Map<QualifiedName, EntityCommandHandler<E>> instanceCommandHandlers,
                                    Map<QualifiedName, CommandHandler> creationalCommandHandlers,
                                    List<EntityChildMetamodel<?, E>> children,
                                    @Nullable EntityEvolver<E> entityEvolver) {
        this.entityType = requireNonNull(entityType, "The entityType may not be null.");
        this.entityEvolver = entityEvolver;
        this.instanceCommandHandlers.putAll(requireNonNull(instanceCommandHandlers,
                                                           "The instanceCommandHandlers may not be null."));
        this.creationalCommandHandlers.putAll(requireNonNull(creationalCommandHandlers,
                                                             "The creationalCommandHandlers may not be null."));

        this.children.addAll(requireNonNull(children, "The children may not be null."));

        // To prevent constantly creating new sets, we create specific sets for the command names
        supportedCreationalCommandNames.addAll(creationalCommandHandlers.keySet());
        supportedInstanceCommandNames.addAll(instanceCommandHandlers.keySet());
        children.forEach(child -> supportedInstanceCommandNames.addAll(child.supportedCommands()));

        supportedCommandNames.addAll(supportedCreationalCommandNames);
        supportedCommandNames.addAll(supportedInstanceCommandNames);
    }

    /**
     * Creates a {@link Builder builder} for the specified entity type. This builder provides a fluent API for defining
     * and constructing an {@link EntityMetamodel} for the given entity class, allowing the registration of command
     * handlers, child entities, and an optional entity evolver.
     *
     * @param <E>        The type of the entity for which the metamodel is being built.
     * @param entityType The {@code Class} object representing the entity type.
     * @return A {@link Builder} instance configured for the specified entity type.
     */
    public static <E> EntityMetamodelBuilder<E> forEntityClass(Class<E> entityType) {
        requireNonNull(entityType, "The entityType may not be null.");
        return new Builder<>(entityType);
    }

    @Override
    public Set<QualifiedName> supportedCommands() {
        return Collections.unmodifiableSet(supportedCommandNames);
    }

    @Override
    public Set<QualifiedName> supportedCreationalCommands() {
        return Collections.unmodifiableSet(supportedCreationalCommandNames);
    }

    @Override
    public Set<QualifiedName> supportedInstanceCommands() {
        return Collections.unmodifiableSet(supportedInstanceCommandNames);
    }

    @Override
    public MessageStream.Single<CommandResultMessage> handleCreate(CommandMessage message,
                                                                   ProcessingContext context) {
        if (isInstanceCommand(message) && !isCreationalCommand(message)) {
            return MessageStream.failed(new EntityMissingForInstanceCommandHandlerException(message));
        }
        try {
            CommandHandler commandHandler = creationalCommandHandlers.get(message.type().qualifiedName());
            if (commandHandler != null) {
                return commandHandler.handle(message, context);
            }
        } catch (Exception e) {
            return MessageStream.failed(e);
        }

        return MessageStream.failed(new NoHandlerForCommandException(message, entityType));
    }

    @Override
    public MessageStream.Single<CommandResultMessage> handleInstance(
            CommandMessage message,
            E entity,
            ProcessingContext context
    ) {
        if (isCreationalCommand(message) && !isInstanceCommand(message)) {
            return MessageStream.failed(new EntityAlreadyExistsForCreationalCommandHandlerException(message, entity));
        }
        try {
            var childrenWithCommandHandlers = children.stream()
                                                      .filter(childEntity -> childEntity
                                                              .supportedCommands()
                                                              .contains(message.type().qualifiedName()))
                                                      .toList();
            if (!childrenWithCommandHandlers.isEmpty()) {
                return handleForChildren(childrenWithCommandHandlers, message, entity, context);
            }

            EntityCommandHandler<E> commandHandler = instanceCommandHandlers.get(message.type().qualifiedName());
            if (commandHandler != null) {
                return commandHandler.handle(message, entity, context);
            }
        } catch (Exception e) {
            return MessageStream.failed(e);
        }

        return MessageStream.failed(new NoHandlerForCommandException(message, entityType));
    }

    @Nullable
    @Override
    public E evolve(E entity, EventMessage event, ProcessingContext context) {
        var currentEntity = entity;
        for (EntityChildMetamodel<?, E> child : children) {
            currentEntity = child.evolve(currentEntity, event, context);
        }
        if (entityEvolver == null) {
            return currentEntity;
        }
        return entityEvolver.evolve(currentEntity, event, context);
    }

    /**
     * Helper method that determines on which child to handle a certain instance command. If only one child can handle
     * the command, it will be used. If multiple children declare the command, we try to find the one that can handle it
     * based on runtime instances (via
     * {@link EntityChildMetamodel#canHandle(CommandMessage, Object, ProcessingContext)}. If multiple children can
     * handle the command, an exception is thrown.
     */
    private MessageStream.Single<CommandResultMessage> handleForChildren(
            List<EntityChildMetamodel<?, E>> childrenWithCommandHandler,
            CommandMessage message,
            E entity,
            ProcessingContext context
    ) {
        if (childrenWithCommandHandler.size() == 1) {
            return childrenWithCommandHandler.getFirst().handle(message, entity, context);
        }

        // There are multiple children that can handle the command. We need to find the ONE that can handle it.
        var matchingChildren = childrenWithCommandHandler
                .stream()
                .filter(childEntity -> childEntity.canHandle(message, entity, context))
                .toList();
        if (matchingChildren.size() == 1) {
            return matchingChildren.getFirst().handle(message, entity, context);
        }
        if (matchingChildren.size() > 1) {
            return MessageStream.failed(new ChildAmbiguityException(
                    "Multiple matching child entity members found for command of type [%s]. Matching candidates are: [%s]".formatted(
                            message, matchingChildren
                    )));
        }
        return MessageStream.failed(new ChildEntityNotFoundException(message, entity));
    }

    @Override
    public Class<E> entityType() {
        return entityType;
    }

    private boolean isCreationalCommand(CommandMessage message) {
        return creationalCommandHandlers.containsKey(message.type().qualifiedName());
    }

    private boolean isInstanceCommand(CommandMessage message) {
        return instanceCommandHandlers.containsKey(message.type().qualifiedName());
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeProperty("entityType", entityType);
        descriptor.describeProperty("commandHandlers", instanceCommandHandlers);
        descriptor.describeProperty("supportedCommandNames", supportedCommandNames);
        descriptor.describeProperty("supportedInstanceCommandNames", supportedInstanceCommandNames);
        descriptor.describeProperty("supportedCreationalCommandNames", supportedCreationalCommandNames);
        descriptor.describeProperty("entityEvolver", entityEvolver);
        descriptor.describeProperty("children", children);
    }

    @Override
    public String toString() {
        return "ConcreteEntityMessageMetamodel{entityType=" + entityType.getName() + '}';
    }

    /**
     * Builder class for constructing an {@link EntityMetamodel} for a specific entity type. This class provides a
     * fluent API to configure the metamodel by specifying command handlers, child entities, and the entity evolver.
     *
     * @param <E> The type of the entity for which the metamodel is being constructed.
     */
    private static class Builder<E> implements EntityMetamodelBuilder<E> {

        private final Class<E> entityType;
        private final Map<QualifiedName, EntityCommandHandler<E>> commandHandlers = new HashMap<>();
        private final Map<QualifiedName, CommandHandler> creationalCommandHandlers = new HashMap<>();
        private final List<EntityChildMetamodel<?, E>> children = new ArrayList<>();
        private @Nullable EntityEvolver<E> entityEvolver;

        private Builder(Class<E> entityType) {
            this.entityType = entityType;
        }

        @Override
        public Builder<E> instanceCommandHandler(QualifiedName qualifiedName,
                                                 EntityCommandHandler<E> messageHandler) {
            requireNonNull(qualifiedName, "The qualifiedName may not be null.");
            requireNonNull(messageHandler, "The messageHandler may not be null.");
            if (commandHandlers.containsKey(qualifiedName)) {
                throw new DuplicateCommandHandlerSubscriptionException(
                        "Duplicate subscription for command [%s] detected. Registration of handler [%s] conflicts with previously registered handler [%s].".formatted(
                                qualifiedName,
                                commandHandlers.get(qualifiedName),
                                messageHandler)
                );
            }
            commandHandlers.put(qualifiedName, messageHandler);
            return this;
        }

        @Override
        public EntityMetamodelBuilder<E> creationalCommandHandler(QualifiedName qualifiedName,
                                                                  CommandHandler messageHandler) {
            requireNonNull(qualifiedName, "The qualifiedName may not be null.");
            requireNonNull(messageHandler, "The messageHandler may not be null.");
            if (creationalCommandHandlers.containsKey(qualifiedName)) {
                throw new DuplicateCommandHandlerSubscriptionException(
                        "Duplicate subscription for command [%s] detected. Registration of handler [%s] conflicts with previously registered handler [%s].".formatted(
                                qualifiedName,
                                creationalCommandHandlers.get(qualifiedName),
                                messageHandler)
                );
            }
            creationalCommandHandlers.put(qualifiedName, messageHandler);
            return this;
        }

        @Override
        public Builder<E> addChild(EntityChildMetamodel<?, E> child) {
            requireNonNull(child, "The child may not be null.");
            if (!child.entityMetamodel().supportedCreationalCommands().isEmpty()) {
                throw new IllegalArgumentException(
                        "Child entities should not have any creational command handlers."
                );
            }
            children.add(child);
            return this;
        }

        @Override
        public EntityMetamodelBuilder<E> entityEvolver(@Nullable EntityEvolver<E> entityEvolver) {
            this.entityEvolver = entityEvolver;
            return this;
        }

        public EntityMetamodel<E> build() {
            return new ConcreteEntityMetamodel<>(entityType,
                                                 commandHandlers,
                                                 creationalCommandHandlers,
                                                 children,
                                                 entityEvolver);
        }
    }
}
