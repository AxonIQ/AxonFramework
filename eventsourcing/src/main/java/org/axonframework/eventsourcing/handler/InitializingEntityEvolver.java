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

package org.axonframework.eventsourcing.handler;

import org.axonframework.common.annotation.Internal;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.common.infra.DescribableComponent;
import org.axonframework.eventsourcing.EntityMissingAfterFirstEventException;
import org.axonframework.eventsourcing.EntityMissingAfterLoadOrCreateException;
import org.axonframework.eventsourcing.EventSourcedEntityFactory;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.modelling.EntityEvolver;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Combines an {@link EventSourcedEntityFactory} and an {@link EntityEvolver}
 * to ensure an entity exists and apply state transitions.
 * <p>
 * If the given entity is {@code null}, a new instance is created using the
 * factory. The resulting entity is then passed to the evolver to apply the
 * given event (if any).
 * <p>
 * This effectively provides "initialize or evolve" semantics.
 *
 * @param <I> the type of the identifier of the entity to create
 * @param <E> the type of the entity to create
 * @since 5.1.0
 * @author John Hendrikx
 */
@Internal
public class InitializingEntityEvolver<I, E> implements DescribableComponent {
    private final EventSourcedEntityFactory<I, E> entityFactory;
    private final EntityEvolver<E> entityEvolver;

    /**
     * Creates a new instance.
     *
     * @param entityFactory an {@link EventSourcedEntityFactory}, cannot be {@code null}
     * @param entityEvolver an {@link EntityEvolver}, cannot be {@code null}
     */
    public InitializingEntityEvolver(EventSourcedEntityFactory<I, E> entityFactory, EntityEvolver<E> entityEvolver) {
        this.entityFactory = Objects.requireNonNull(entityFactory, "The entityFactory parameter must not be null.");
        this.entityEvolver = Objects.requireNonNull(entityEvolver, "The entityEvolver parameter must not be null.");
    }

    /**
     * Creates an empty entity {@code E}.
     *
     * @param identifier the entity's identifier, cannot be {@code null}
     * @param firstEventMessage optional first event message to initialize the entiy with
     * @param context a {@link ProcessingContext}, cannot be {@code null}
     * @return a new entity of type {@code E}
     */
    public E initialize(I identifier, @Nullable EventMessage firstEventMessage, ProcessingContext context) {
        E entity = entityFactory.create(
            Objects.requireNonNull(identifier, "The identifier parameter must not be null."),
            firstEventMessage,
            Objects.requireNonNull(context, "The context parameter must not be null.")
        );

        if (entity == null) {
            throw new EntityMissingAfterLoadOrCreateException(identifier);
        }

        return entity;
    }

    /**
     * Initializes or evolves the given entity using the given message. If the entity
     * is {@code null}, creates the entity using the given message, otherwise evolves it.
     *
     * @param identifier the entity's identifier, cannot be {@code null}
     * @param entity the current state, can be {@code null}
     * @param message an event message to initialize or evolve the entity with, cannot be {@code null}
     * @param context a {@link ProcessingContext}, cannot be {@code null}
     * @return an entity in its new state, never {@code null}
     */
    public E evolve(I identifier, @Nullable E entity, EventMessage message, ProcessingContext context) {
        return ensureInitializedThenEvolve(
            Objects.requireNonNull(identifier, "The identifier parameter must not be null."),
            entity,
            Objects.requireNonNull(message, "The message parameter must not be null."),
            Objects.requireNonNull(context, "The context parameter must not be null.")
        );
    }

    private E ensureInitializedThenEvolve(I identifier, @Nullable E entity, EventMessage message, ProcessingContext context) {
        if (entity == null) {
            entity = entityFactory.create(identifier, message, context);

            if (entity == null) {
                throw new EntityMissingAfterFirstEventException(identifier);
            }
        }

        return entityEvolver.evolve(entity, message, context);
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeProperty("entityFactory", entityFactory);
        descriptor.describeProperty("entityEvolver", entityEvolver);
    }
}
