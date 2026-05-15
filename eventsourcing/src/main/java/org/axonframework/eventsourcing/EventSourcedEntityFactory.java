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

package org.axonframework.eventsourcing;

import org.axonframework.messaging.commandhandling.CommandHandler;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.modelling.entity.EntityCommandHandler;
import org.axonframework.modelling.entity.EntityMetamodelBuilder;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Defines how an {@link EventSourcingRepository} should construct an entity of type {@code E}.
 * <p>
 * When sourcing an entity, the state is initially {@code null}. The first event will initialize the entity through
 * {@link #create(Object, EventMessage, ProcessingContext)}.
 * <p>
 * If no events are found during sourcing, the repository will return {@code null} for the entity if
 * {@link EventSourcingRepository#load(Object, ProcessingContext)} was used. However, if
 * {@link EventSourcingRepository#loadOrCreate(Object, ProcessingContext)} was used, the repository will return an empty
 * entity through calling {@link #create(Object, EventMessage, ProcessingContext)} with a {@code null}
 * {@code firstEventMessage}. This may return null in case the entity should be created with the first event message
 * only. For example, when it is immutable.
 * <p>
 * Depending on the type of entity, the factory should be created differently. The following types of entities are
 * supported:
 * <ul>
 *     <li>
 *         Mutable entities: these entities are created with a no-argument constructor. All command handlers of the
 *         entity should be {@link EntityMetamodelBuilder#instanceCommandHandler(QualifiedName, EntityCommandHandler) instance
 *         command handlers}. Use {@link #fromNoArgument(Supplier)} to create a factory for this type of entity.
 *     </li>
 *     <li>
 *         Mutable entities with non-null identifier: these entities are created with a constructor that takes the
 *         identifier as parameter. All command handlers of the entity should be
 *         {@link EntityMetamodelBuilder#instanceCommandHandler(QualifiedName, EntityCommandHandler) instance command handlers}.
 *         Use {@link #fromIdentifier(Function)} to create a factory for this type of entity.
 *     </li>
 *     <li>
 *         Immutable entities, or entities with non-nullable parameters: these entities are created with a constructor that takes
 *         the identifier and the event message as parameters. The entity should have a combination of
 *         {@link EntityMetamodelBuilder#creationalCommandHandler(QualifiedName, CommandHandler) creational command handlers} to
 *         create the entity if no events exist for it, and
 *         {@link EntityMetamodelBuilder#instanceCommandHandler(QualifiedName, EntityCommandHandler) instance command handlers} to
 *         handle commands when it does exist. If a command could potentially handle both cases, it would need to be
 *         registered as both a creational and instance command handler.
 *         Use {@link #fromEventMessage(BiFunction)} to create a factory for this type of entity.
 *      </li>
 * </ul>
 * <p>
 * Implementations should be thread-safe.
 *
 * @param <ID> The type of the identifier of the entity to create.
 * @param <E>  The type of the entity to create.
 * @author Mitchell Herrijgers
 * @since 5.0.0
 */
@FunctionalInterface
public interface EventSourcedEntityFactory<ID, E> {

    /**
     * Creates an entity of type {@code E} with the given identifier. The identifier is guaranteed to be non-null. The
     * supplied {@code firstEventMessage} is the first event message that is present in the stream of the entity. If no
     * event messages are present, this method will be called with a {@code null} {@code firstEventMessage} to get an
     * initial state when calling {@link EventSourcingRepository#loadOrCreate(Object, ProcessingContext)}. Using
     * {@link EventSourcingRepository#load(Object, ProcessingContext)} would never call this method with a {@code null}
     * {@code firstEventMessage}.
     * <p>
     * Invocations with a non-null {@code firstEventMessage} must always return a non-null entity, while invocations
     * with a null {@code firstEventMessage} may return null.
     * <p>
     * Whether to return {@code null} from a {@code null} {@code firstEventMessage} invocation depends on the type of
     * command handler which should be invoked when the entity does not exist. If this is a
     * {@link EntityMetamodelBuilder#creationalCommandHandler(QualifiedName, CommandHandler) creational command
     * handler}, this should return {@code null}. If this is a
     * {@link EntityMetamodelBuilder#instanceCommandHandler(QualifiedName, EntityCommandHandler) instance
     * command handler}, this should return the non-null initial state of the entity. For example, using the no-argument
     * constructor of the entity, or a constructor that takes the identifier as a parameter.
     * <p>
     * It is recommended to use {@link #fromNoArgument(Supplier)}, {@link #fromIdentifier(Function)} or
     * {@link #fromEventMessage(BiFunction)} to create a factory that creates the entity based on the constructor of the
     * entity. This will ensure that the right factory is created.
     *
     * @param id                The identifier of the entity to create. This is guaranteed to be non-null.
     * @param firstEventMessage The first event message that is present in the stream of the entity. This may be
     *                          {@code null} if no event messages are present.
     * @param context           The processing context.
     * @return The entity to create. This may be {@code null} if no entity should be created.
     */
    @Nullable
    E create(ID id, @Nullable EventMessage firstEventMessage, ProcessingContext context);

    /**
     * Creates a factory for an entity of type {@code E} using a specified no-argument constructor.
     * <p>
     * Should be used when your entity is mutable, and you want to create it with a no-argument constructor. All command
     * handlers of your entity should be
     * {@link EntityMetamodelBuilder#instanceCommandHandler(QualifiedName, EntityCommandHandler) instance
     * command handler}. If you would like the identifier to be passed to the constructor, use
     * {@link #fromIdentifier(Function)} instead.
     *
     * @param creator A {@link Supplier} that creates the entity. This should be a no-argument constructor.
     * @param <ID>    The type of the identifier of the entity.
     * @param <E>     The type of the entity.
     * @return A factory that creates the entity using the no-argument constructor.
     */
    static <ID, E> EventSourcedEntityFactory<ID, E> fromNoArgument(Supplier<E> creator) {
        Objects.requireNonNull(creator, "The creator must not be null.");
        return (id, evt, context) -> creator.get();
    }

    /**
     * Creates a factory for an entity of type {@code E} using a specified constructor with the identifier as
     * parameter.
     * <p>
     * Should be used when your entity is mutable, and you want to create it with a constructor that takes the
     * identifier as parameter. All command handlers of your entity should be
     * {@link EntityMetamodelBuilder#instanceCommandHandler(QualifiedName, EntityCommandHandler) instance
     * command handler}.
     *
     * @param creator A {@link Function} that creates the entity. This should be a constructor with the identifier as
     *                parameter.
     * @param <ID>    The type of the identifier of the entity.
     * @param <E>     The type of the entity.
     * @return A factory that creates the entity using the constructor with the identifier as parameter.
     */
    static <ID, E> EventSourcedEntityFactory<ID, E> fromIdentifier(Function<ID, E> creator) {
        Objects.requireNonNull(creator, "The creator must not be null.");
        return (id, evt, context) -> creator.apply(id);
    }

    /**
     * Creates a factory for an entity of type {@code E} using a specified constructor with the identifier and the event
     * message as parameters.
     * <p>
     * Should be used if your entity is immutable, and/or you want to create it with a constructor that takes the
     * identifier and the event message as parameters to set non-nullable parameters on it. Your entity should have a
     * combination of
     * {@link EntityMetamodelBuilder#creationalCommandHandler(QualifiedName, CommandHandler) creational command
     * handlers} to create the entity if no events exist for it, and
     * {@link EntityMetamodelBuilder#instanceCommandHandler(QualifiedName, EntityCommandHandler) instance
     * command handlers} to handle commands when it does exist. If a command could potentially handle both cases, it
     * would need to be registered as both a creational and instance command handler.
     *
     * @param creator A {@link BiFunction} that creates the entity. This should be a constructor with the identifier and
     *                the event as parameters.
     * @param <ID>    The type of the identifier of the entity.
     * @param <E>     The type of the entity.
     * @return A factory that creates the entity using the constructor with the identifier and the event message as
     * parameters.
     */
    static <ID, E> EventSourcedEntityFactory<ID, E> fromEventMessage(
            BiFunction<ID, EventMessage, E> creator) {
        Objects.requireNonNull(creator, "The creator must not be null.");
        return (id, evt, context) -> evt == null
                ? null
                : creator.apply(id, evt);
    }
}
