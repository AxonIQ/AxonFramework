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

import org.jspecify.annotations.Nullable;
import org.axonframework.messaging.commandhandling.CommandHandler;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.CommandResultMessage;
import org.axonframework.messaging.commandhandling.NoHandlerForCommandException;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.common.infra.DescribableComponent;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.modelling.EntityEvolver;
import org.axonframework.modelling.entity.child.EntityChildMetamodel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Polymorphic {@link EntityMetamodel} that represents an entity that can have multiple concrete types. For example,
 * {@code Employee} and {@code Customer} could be two concrete types of {@code Person}, sharing properties and a set of
 * commands and events.
 * <p>
 * This metamodel delegates commands to the concrete type if the concrete type is registered for the command. If not, it
 * will attempt to handle the command with the super type. Concrete types thus take precedence over the super type for
 * commands.
 * <p>
 * Events are delegates to both the super type and the concrete type.
 *
 * @param <E> The type of polymorphic entity this metamodel represents.
 * @author Mitchell Herrijgers
 * @since 5.0.0
 */
public class PolymorphicEntityMetamodel<E> implements EntityMetamodel<E>, DescribableComponent {

    private final EntityMetamodel<E> superTypeMetamodel;
    private final Map<Class<? extends E>, EntityMetamodel<? extends E>> concreteMetamodels;
    private final Set<QualifiedName> supportedCommandNames = new HashSet<>();
    private final Set<QualifiedName> supportedInstanceCommandNames = new HashSet<>();
    private final Set<QualifiedName> supportedCreationalCommandNames = new HashSet<>();

    private PolymorphicEntityMetamodel(
            EntityMetamodel<E> superTypeMetamodel,
            List<EntityMetamodel<? extends E>> concreteMetamodels
    ) {
        this.superTypeMetamodel = Objects.requireNonNull(superTypeMetamodel, "The superTypeMetamodel may not be null.");
        Objects.requireNonNull(concreteMetamodels, "The concreteMetamodels may not be null.");
        this.concreteMetamodels = new HashMap<>();
        this.supportedCommandNames.addAll(superTypeMetamodel.supportedCommands());
        this.supportedInstanceCommandNames.addAll(this.superTypeMetamodel.supportedInstanceCommands());
        this.supportedCreationalCommandNames.addAll(superTypeMetamodel.supportedCreationalCommands());
        for (EntityMetamodel<? extends E> concreteMetamodel : concreteMetamodels) {
            this.concreteMetamodels.put(concreteMetamodel.entityType(), concreteMetamodel);
            this.supportedCommandNames.addAll(concreteMetamodel.supportedCommands());
            this.supportedInstanceCommandNames.addAll(concreteMetamodel.supportedInstanceCommands());
            this.supportedCreationalCommandNames.addAll(concreteMetamodel.supportedCreationalCommands());
        }
    }

    /**
     * Creates a new polymorphic {@link EntityMetamodel} for the given super type. The metamodel can then be used to add
     * concrete types to the metamodel. Any method inherited from {@link EntityMetamodelBuilder} is delegated to the
     * super type metamodel.
     *
     * @param entityType The type of the entity to create a metamodel for.
     * @param <E>        The type of the entity to create a metamodel for.
     * @return A new {@link Builder} for the given entity type.
     */
    public static <E> PolymorphicEntityMetamodelBuilder<E> forSuperType(Class<E> entityType) {
        return new Builder<>(entityType);
    }

    @Nullable
    @Override
    public E evolve(@Nullable E entity, EventMessage event, ProcessingContext context) {
        var superTypeEvolvedEntity = superTypeMetamodel.evolve(entity, event, context);
        if (superTypeEvolvedEntity == null) {
            // The entity does not exist yet (or was removed); the concrete type cannot be determined.
            return null;
        }
        return metamodelFor(superTypeEvolvedEntity).evolve(superTypeEvolvedEntity, event, context);
    }

    /**
     * Helper that “captures” the ? extends E for this particular entity instance.
     */
    @SuppressWarnings("unchecked")
    private <T extends E> EntityMetamodel<T> metamodelFor(T entity) {
        // we know at runtime the metamodel was stored under entity.getClass()
        return (EntityMetamodel<T>) concreteMetamodels.get(entity.getClass());
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
        for (EntityMetamodel<? extends E> metamodel : concreteMetamodels.values()) {
            if (metamodel.supportedCreationalCommands().contains(message.type().qualifiedName())) {
                return metamodel.handleCreate(message, context);
            }
        }
        if (superTypeMetamodel.supportedCreationalCommands().contains(message.type().qualifiedName())) {
            return superTypeMetamodel.handleCreate(message, context);
        }
        return MessageStream.failed(new NoHandlerForCommandException(message, entityType()));
    }

    @Override
    public MessageStream.Single<CommandResultMessage> handleInstance(CommandMessage message,
                                                                              E entity,
                                                                              ProcessingContext context) {
        if (isCreationalCommand(message) && !isInstanceCommand(message)) {
            return MessageStream.failed(new EntityAlreadyExistsForCreationalCommandHandlerException(message, entity));
        }
        EntityMetamodel<E> concreteMetamodel = metamodelFor(entity);
        if (concreteMetamodel.supportedInstanceCommands().contains(message.type().qualifiedName())) {
            return concreteMetamodel.handleInstance(message, entity, context);
        }
        if (superTypeMetamodel.supportedInstanceCommands().contains(message.type().qualifiedName())) {
            return superTypeMetamodel.handleInstance(message, entity, context);
        }

        //noinspection unchecked
        List<Class<E>> supportingEntityTypes = this.concreteMetamodels
                .values()
                .stream()
                .filter(metamodel -> metamodel.supportedInstanceCommands().contains(message.type().qualifiedName()))
                .map(metamodel -> (Class<E>) metamodel.entityType())
                .toList();
        return MessageStream.failed(new WrongPolymorphicEntityTypeException(message,
                                                                            entityType(),
                                                                            supportingEntityTypes,
                                                                            concreteMetamodel.entityType()));
    }

    @Override
    public Class<E> entityType() {
        return superTypeMetamodel.entityType();
    }

    private boolean isCreationalCommand(CommandMessage message) {
        return supportedCreationalCommandNames.contains(message.type().qualifiedName());
    }

    private boolean isInstanceCommand(CommandMessage message) {
        return supportedInstanceCommandNames.contains(message.type().qualifiedName());
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeProperty("entityType", entityType());
        descriptor.describeProperty("superTypeMetamodel", superTypeMetamodel);
        descriptor.describeProperty("polymorphicMetamodels", concreteMetamodels);
    }

    @Override
    public String toString() {
        return "PolymorphicEntityMetaModel{entityType=" + entityType().getName() + '}';
    }

    /**
     * Builder for a {@link PolymorphicEntityMetamodel}. This builder allows you to add concrete types to the metamodel.
     * Any method inherited from {@link EntityMetamodelBuilder} is delegated to the super type metamodel.
     *
     * @param <E> The type of the entity this metamodel represents.
     */
    private static class Builder<E> implements PolymorphicEntityMetamodelBuilder<E> {

        private final EntityMetamodelBuilder<E> superTypeBuilder;
        private final List<EntityMetamodel<? extends E>> polymorphicMetamodels = new ArrayList<>();

        private Builder(Class<E> entityType) {
            this.superTypeBuilder = ConcreteEntityMetamodel.forEntityClass(entityType);
        }

        @Override
        public Builder<E> instanceCommandHandler(QualifiedName qualifiedName,
                                                 EntityCommandHandler<E> messageHandler) {
            superTypeBuilder.instanceCommandHandler(qualifiedName, messageHandler);
            return this;
        }

        @Override
        public Builder<E> creationalCommandHandler(QualifiedName qualifiedName,
                                                   CommandHandler messageHandler) {
            superTypeBuilder.creationalCommandHandler(qualifiedName, messageHandler);
            return this;
        }

        @Override
        public Builder<E> addChild(EntityChildMetamodel<?, E> child) {
            superTypeBuilder.addChild(child);
            return this;
        }


        @Override
        public Builder<E> entityEvolver(@Nullable EntityEvolver<E> entityEvolver) {
            superTypeBuilder.entityEvolver(entityEvolver);
            return this;
        }

        @Override
        public Builder<E> addConcreteType(EntityMetamodel<? extends E> metamodel) {
            Objects.requireNonNull(metamodel, "The metamodel may not be null.");
            if (polymorphicMetamodels.stream().anyMatch(p -> p.entityType()
                                                              .equals(metamodel.entityType()))) {
                throw new IllegalArgumentException("Concrete type [%s] already registered for this metamodel.".formatted(
                        metamodel.entityType().getName()));
            }
            // Check if any existing polymorphic metamodel clashes with the creational commands of the new metamodel.
            for (EntityMetamodel<? extends E> existingMetamodel : polymorphicMetamodels) {
                if (existingMetamodel.supportedCreationalCommands().stream()
                                     .anyMatch(metamodel.supportedCreationalCommands()::contains)) {
                    throw new IllegalArgumentException(
                            "Concrete type [%s] has creational commands that clash with existing concrete type [%s]."
                                    .formatted(metamodel.entityType().getName(),
                                               existingMetamodel.entityType().getName()));
                }
            }
            polymorphicMetamodels.add(metamodel);
            return this;
        }

        @Override
        public EntityMetamodel<E> build() {
            EntityMetamodel<E> metamodel = superTypeBuilder.build();
            return new PolymorphicEntityMetamodel<>(metamodel, polymorphicMetamodels);
        }
    }
}
