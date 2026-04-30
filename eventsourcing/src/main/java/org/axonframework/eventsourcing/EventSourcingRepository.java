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

import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.eventsourcing.handler.EntityLifecycleHandler;
import org.axonframework.eventsourcing.handler.InitializingEntityEvolver;
import org.axonframework.eventsourcing.handler.SimpleEntityLifecycleHandler;
import org.axonframework.messaging.core.Context.ResourceKey;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.modelling.EntityEvolver;
import org.axonframework.modelling.repository.ManagedEntity;
import org.axonframework.modelling.repository.Repository;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static java.util.Objects.requireNonNull;

/**
 * {@link Repository} implementation which manages event-sourced entities and delegates
 * lifecycle concerns (loading, initialization, snapshot handling, and live updates)
 * to an {@link EntityLifecycleHandler}.
 * <p>
 * The repository is responsible for:
 * <ul>
 *   <li>coordinating access to managed entity instances within a processing context</li>
 *   <li>caching active entities per identifier</li>
 *   <li>delegating entity reconstruction and initialization to the lifecycle handler</li>
 *   <li>attaching entities to the lifecycle handler for live update propagation</li>
 * </ul>
 *
 * The lifecycle handler encapsulates all event-stream interpretation concerns.
 *
 * @param <ID> The type of identifier used to identify the event sourced entity.
 * @param <E>  The type of the event sourced entity to load.
 * @author Allard Buijze
 * @author Steven van Beelen
 * @author John Hendrikx
 * @since 0.1
 */
public class EventSourcingRepository<ID, E> implements Repository.LifecycleManagement<ID, E> {
    private final ResourceKey<Map<ID, CompletableFuture<EventSourcedEntity<ID, E>>>> managedEntitiesKey =
            ResourceKey.withLabel("managedEntities");

    private final Class<ID> idType;
    private final Class<E> entityType;
    private final EntityLifecycleHandler<ID, E> lifecycleHandler;

    /**
     * Initialize the repository to load events from the given {@code eventStore} using the given {@code entityEvolver} to
     * apply state transitions to the entity based on the events received, and given {@code criteriaResolver} to resolve
     * the {@link EventCriteria} of the given identifier type used to source an entity.
     * <p>
     * This constructor implicitly builds a default lifecycle handler that:
     * <ul>
     *   <li>sources events from the given event store</li>
     *   <li>uses the criteria resolver to locate the event stream</li>
     *   <li>applies the provided entity evolver for state transitions</li>
     *   <li>initializes entities when no event stream exists</li>
     * </ul>
     *
     * @param idType           the type of the identifier for the event sourced entity this repository serves
     * @param entityType       the type of the event sourced entity this repository serves
     * @param eventStore       the event store to load events from
     * @param entityFactory    a factory method to create new instances of the entity based on the entity's type and a
     *                         provided identifier
     * @param criteriaResolver converts the given identifier to an {@link EventCriteria} used to load a matching event
     *                         stream
     * @param entityEvolver    the function used to evolve the state of loaded entities based on events
     * @deprecated use {@link EventSourcingRepository#EventSourcingRepository(Class, Class, EntityLifecycleHandler)}
     */
    @Deprecated
    public EventSourcingRepository(Class<ID> idType,
                                   Class<E> entityType,
                                   EventStore eventStore,
                                   EventSourcedEntityFactory<ID, E> entityFactory,
                                   CriteriaResolver<ID> criteriaResolver,
                                   EntityEvolver<E> entityEvolver
    ) {
        this(
            requireNonNull(idType, "The id type must not be null."),
            requireNonNull(entityType, "The entity type must not be null."),
            new SimpleEntityLifecycleHandler<>(
                requireNonNull(eventStore, "The event store must not be null."),
                requireNonNull(criteriaResolver, "The criteria resolver must not be null."),
                new InitializingEntityEvolver<>(
                    requireNonNull(entityFactory, "The entity factory must not be null."),
                    requireNonNull(entityEvolver, "The entity evolver must not be null.")
                )
            )
        );
    }

    /**
     * Initialize the repository to load events from the given {@code eventStore} using the given {@code entityEvolver} to
     * apply state transitions to the entity based on the events received, and given {@code sourcingHandler} to process
     * the event stream.
     *
     * @param idType           the type of the identifier for the event sourced entity this repository serves
     * @param entityType       the type of the event sourced entity this repository serves
     * @param lifecycleHandler the {@link EntityLifecycleHandler} used to create and manage entities
     */
    public EventSourcingRepository(
        Class<ID> idType,
        Class<E> entityType,
        EntityLifecycleHandler<ID, E> lifecycleHandler
    ) {
        this.idType = requireNonNull(idType, "The id type must not be null.");
        this.entityType = requireNonNull(entityType, "The entity type must not be null.");
        this.lifecycleHandler = requireNonNull(lifecycleHandler, "The lifecycleHandler parameter must not be null.");
    }

    @Override
    public ManagedEntity<ID, E> attach(ManagedEntity<ID, E> entity,
                                       ProcessingContext processingContext) {
        var managedEntities = processingContext.computeResourceIfAbsent(managedEntitiesKey, ConcurrentHashMap::new);

        return managedEntities.computeIfAbsent(
                entity.identifier(),
                id -> {
                    EventSourcedEntity<ID, E> sourcedEntity = EventSourcedEntity.mapToEventSourcedEntity(entity);
                    updateActiveEntity(sourcedEntity, processingContext);
                    return CompletableFuture.completedFuture(sourcedEntity);
                }
        ).resultNow();
    }

    @Override
    public Class<E> entityType() {
        return entityType;
    }

    @Override
    public Class<ID> idType() {
        return idType;
    }

    @Override
    public CompletableFuture<ManagedEntity<ID, E>> load(ID identifier,
                                                        ProcessingContext context) {
        return doLoad(identifier, false, context);
    }

    @Override
    public CompletableFuture<ManagedEntity<ID, E>> loadOrCreate(ID identifier,
                                                                ProcessingContext context) {
        return doLoad(identifier, true, context);
    }

    private CompletableFuture<ManagedEntity<ID, E>> doLoad(ID identifier,
                                                           boolean create,
                                                           ProcessingContext context) {
        var managedEntities = context.computeResourceIfAbsent(managedEntitiesKey, ConcurrentHashMap::new);

        return managedEntities.computeIfAbsent(
                identifier,
                id -> lifecycleHandler.source(identifier, context)
                        .thenApply(entity -> create && entity == null ? lifecycleHandler.initialize(identifier, null, context) : entity)
                        .thenApply(e -> new EventSourcedEntity<>(identifier, e))
                        .whenComplete((entity, exception) -> updateActiveEntity(entity, context, exception))
        ).thenApply(Function.identity());
    }

    @Override
    public ManagedEntity<ID, E> persist(ID identifier,
                                        E entity,
                                        ProcessingContext processingContext) {
        var managedEntities = processingContext.computeResourceIfAbsent(managedEntitiesKey, ConcurrentHashMap::new);

        return managedEntities.computeIfAbsent(identifier, id -> {
            EventSourcedEntity<ID, E> sourcedEntity = new EventSourcedEntity<>(identifier, entity);
            updateActiveEntity(sourcedEntity, processingContext);
            return CompletableFuture.completedFuture(sourcedEntity);
        }).resultNow();
    }

    private void updateActiveEntity(EventSourcedEntity<ID, E> entity, ProcessingContext processingContext,
                                    Throwable exception) {
        if (exception == null) {
            updateActiveEntity(entity, processingContext);
        }
    }

    /**
     * Registers the given entity with the lifecycle handler so it receives live updates
     * from the event stream during its lifetime.
     *
     * @param entity            a {@link ManagedEntity} to update
     * @param processingContext the {@link ProcessingContext} required
     */
    private void updateActiveEntity(ManagedEntity<ID, E> entity, ProcessingContext processingContext) {
        lifecycleHandler.subscribe(entity, processingContext);
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeProperty("idType", idType);
        descriptor.describeProperty("entityType", entityType);
        descriptor.describeProperty("entityLifecycleHandler", lifecycleHandler);
    }

    /**
     * Private implementation of the {@link ManagedEntity} supporting event sourcing.
     *
     * @param <ID> The type of identifier of the event sourced entity.
     * @param <M>  The type of entity managed by this event sourced entity.
     */
    private static class EventSourcedEntity<ID, M> implements ManagedEntity<ID, M> {

        private final ID identifier;
        private final AtomicReference<M> currentState;

        private EventSourcedEntity(ID identifier, @Nullable M currentState) {
            this.identifier = identifier;
            this.currentState = new AtomicReference<>(currentState);
        }

        private static <ID, T> EventSourcedEntity<ID, T> mapToEventSourcedEntity(ManagedEntity<ID, T> entity) {
            return entity instanceof EventSourcingRepository.EventSourcedEntity<ID, T> eventSourcedEntity
                    ? eventSourcedEntity
                    : new EventSourcedEntity<>(entity.identifier(), entity.entity());
        }

        @Override
        public ID identifier() {
            return identifier;
        }

        @Override
        public M entity() {
            return currentState.get();
        }

        @Override
        public M applyStateChange(UnaryOperator<M> change) {
            return currentState.updateAndGet(change);
        }
    }
}
