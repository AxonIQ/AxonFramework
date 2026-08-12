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

package org.axonframework.modelling.repository;

import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.core.Context.ResourceKey;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import static java.util.Objects.requireNonNull;

/**
 * Simple implementation of an {@link Repository} that can load and persist entities of a given type. When an entity is
 * loaded, it is stored in the {@link ProcessingContext} to ensure that the same entity is returned when it is loaded
 * again in the same context.
 * <p>
 * When an entity is successfully loaded, it's scheduled to automatically be persisted when the
 * {@link ProcessingContext} is committed.
 * <p>
 * Both the {@link #load(Object, ProcessingContext)} and {@link #loadOrCreate(Object, ProcessingContext)} use the
 * {@link SimpleRepositoryEntityLoader} to load the entity. If you wish to create an entity when it does not exist, you
 * can return a value when you have found none.
 *
 * @param <ID> The type of the identifier of the entity.
 * @param <E>  The type of the entity.
 * @author Mitchell Herrijgers
 * @since 5.0.0
 */
public class SimpleRepository<ID, E> implements Repository.LifecycleManagement<ID, E> {

    private final ResourceKey<Map<ID, CompletableFuture<ManagedEntity<ID, E>>>> managedEntitiesKey =
            ResourceKey.withLabel("SimpleRepository.ManagedEntities");

    private final Class<ID> idType;
    private final Class<E> entityType;
    private final SimpleRepositoryEntityLoader<ID, E> loader;
    private final SimpleRepositoryEntityPersister<ID, E> persister;

    /**
     * Constructs a new simple {@link Repository} for entities of type {@code entityType} with identifiers of type
     * {@code idType}. The given {@code loader} is used to load entities and the given {@code persister} is used to
     * persist entities.
     *
     * @param idType     The type of the identifier of the entity.
     * @param entityType The type of the entity.
     * @param loader     The component capable of loading entities.
     * @param persister  The component capable of persisting entities.
     */
    public SimpleRepository(
            Class<ID> idType,
            Class<E> entityType,
            SimpleRepositoryEntityLoader<ID, E> loader,
            SimpleRepositoryEntityPersister<ID, E> persister
    ) {
        this.idType = requireNonNull(idType, "The entityIdClass may not be null");
        this.entityType = requireNonNull(entityType, "The entityClass may not be null");
        this.loader = requireNonNull(loader, "The loader may not be null");
        this.persister = requireNonNull(persister, "The persister may not be null");
    }

    @Override
    public ManagedEntity<ID, E> attach(ManagedEntity<ID, E> entity,
                                       ProcessingContext context) {
        var managedEntities = context.computeResourceIfAbsent(managedEntitiesKey, ConcurrentHashMap::new);

        return managedEntities.computeIfAbsent(
                entity.identifier(),
                id -> {
                    SimpleEntity<ID, E> simpleEntity = SimpleEntity.mapToSimpleEntity(entity);
                    attachEntitySaveToContext(id, simpleEntity, context);
                    return CompletableFuture.completedFuture(simpleEntity);
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
    public CompletableFuture<ManagedEntity<ID, E>> load(ID id, ProcessingContext context) {
        var managedEntities = context.computeResourceIfAbsent(managedEntitiesKey, ConcurrentHashMap::new);

        return managedEntities.computeIfAbsent(
                id,
                identifier -> loader
                        .load(identifier, context)
                        .thenApply((entity) -> {
                            SimpleEntity<ID, E> managedEntity = new SimpleEntity<>(id, entity);
                            attachEntitySaveToContext(id, managedEntity, context);
                            return managedEntity;
                        })
        );
    }

    private void attachEntitySaveToContext(ID id, ManagedEntity<ID, E> managedEntity, ProcessingContext context) {
        context.onPrepareCommit(uow -> persister.persist(id, managedEntity.entity(), uow));
    }

    @Override
    public CompletableFuture<ManagedEntity<ID, E>> loadOrCreate(ID identifier,
                                                                ProcessingContext processingContext) {
        return load(identifier, processingContext);
    }

    @Override
    public ManagedEntity<ID, E> persist(ID id, E entity, ProcessingContext context) {
        var managedEntities = context.computeResourceIfAbsent(managedEntitiesKey, ConcurrentHashMap::new);
        if (managedEntities.containsKey(id)) {
            ManagedEntity<ID, E> managedEntity = managedEntities.get(id).join();
            managedEntity.applyStateChange(oldState -> entity);
            return managedEntity;
        } else {
            var managedEntity = new SimpleEntity<>(id, entity);
            attachEntitySaveToContext(id, managedEntity, context);
            managedEntities.put(id, CompletableFuture.completedFuture(managedEntity));
            return managedEntity;
        }
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeProperty("idClass", idType);
        descriptor.describeProperty("entityClass", entityType);
        descriptor.describeProperty("loader", loader);
        descriptor.describeProperty("persister", persister);
    }

    /**
     * Simple implementation of a {@link ManagedEntity} that uses an {@link AtomicReference} to store the state of the
     * entity.
     *
     * @param <I> The type of the identifier of the entity.
     * @param <T> The type of the entity.
     */
    private static class SimpleEntity<I, T> implements ManagedEntity<I, T> {

        private final I id;
        private final AtomicReference<T> state;

        private SimpleEntity(I id, T state) {
            this.id = id;
            this.state = new AtomicReference<>(state);
        }

        private static <ID, T> SimpleEntity<ID, T> mapToSimpleEntity(ManagedEntity<ID, T> entity) {
            return entity instanceof SimpleEntity<ID, T> simpleEntity
                    ? simpleEntity
                    : new SimpleEntity<>(entity.identifier(), entity.entity());
        }

        @Override
        public I identifier() {
            return id;
        }

        @Override
        public T entity() {
            return state.get();
        }

        @Nullable
        @Override
        public T applyStateChange(UnaryOperator<@Nullable T> change) {
            return state.updateAndGet(change);
        }
    }
}
