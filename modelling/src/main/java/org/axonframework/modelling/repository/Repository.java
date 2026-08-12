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

import org.axonframework.common.infra.DescribableComponent;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

import java.util.concurrent.CompletableFuture;

/**
 * The {@link Repository} provides an abstraction for the storage of entities.
 * <p>
 * When interacting with the {@code Repository} the framework expects an active {@link ProcessingContext}. If there is
 * no active {@code UnitOfWork} an {@link IllegalStateException} is thrown.
 *
 * @param <E>  The type of entity this repository stores.
 * @param <ID> The type of identifier for entities in this repository.
 * @author Allard Buijze
 * @implNote Implementations of this interface must implement {@link Repository.LifecycleManagement} instead.
 * @since 0.1
 */
public sealed interface Repository<ID, E>
        extends DescribableComponent
        permits Repository.LifecycleManagement {

    /**
     * The type of entity stored in this repository.
     *
     * @return The type of entity stored in this repository.
     */
    Class<E> entityType();

    /**
     * The type of the identifier used to identify entities in this repository.
     *
     * @return The type of the identifier used to identify entities in this repository.
     */
    Class<ID> idType();

    /**
     * Load the entity with the given unique identifier. No version checks are done when loading an entity, meaning that
     * concurrent access will not be checked for.
     * <p>
     * The returned {@link CompletableFuture} always resolves to a {@link ManagedEntity} for the given
     * {@code identifier}. The {@link ManagedEntity#entity() entity} managed by it may be {@code null} when none
     * could be found. Implementations without an autonomous construction step (e.g. one driven entirely by a
     * user-supplied loader) remain free to let the future complete exceptionally instead, if that loader does so.
     *
     * @param identifier        the identifier of the entity to load
     * @param processingContext the processing context in which to manage the lifecycle of the entity
     * @return a {@link CompletableFuture} resolving to the {@link ManagedEntity} with the given identifier
     */
    CompletableFuture<ManagedEntity<ID, E>> load(ID identifier,
                                                 ProcessingContext processingContext);

    /**
     * Loads an entity from the repository, constructing a new instance when none exists yet.
     * <p>
     * The returned {@link CompletableFuture} always resolves to a {@link ManagedEntity} for the given
     * {@code identifier}. The {@link ManagedEntity#entity() entity} managed by it may be {@code null} when none
     * could be found. Implementations without an autonomous construction step (e.g. one driven entirely by a
     * user-supplied loader) remain free to let the future complete exceptionally instead, if that loader does so.
     *
     * @param identifier        the identifier of the entity to load
     * @param processingContext the processing context in which to manage the lifecycle of the entity
     * @return a {@link CompletableFuture} resolving to the {@link ManagedEntity} with the given identifier, backed by
     * a newly constructed entity instance when none existed yet
     */
    CompletableFuture<ManagedEntity<ID, E>> loadOrCreate(ID identifier,
                                                         ProcessingContext processingContext);

    /**
     * Persists the given {@code entity} in this repository
     *
     * @param identifier        The identifier of the entity.
     * @param entity            The current state of the entity to store.
     * @param processingContext The {@link ProcessingContext} in which the entity is active.
     * @return a {@link ManagedEntity} wrapping the entity managed in the {@link ProcessingContext}.
     */
    ManagedEntity<ID, E> persist(ID identifier,
                                 E entity,
                                 ProcessingContext processingContext);

    /**
     * Specialization of the {@link Repository} interface that <em>must</em> be implemented by all implementations of
     * the {@code Repository}. It exposes some methods that are required to perform lifecycle management operations that
     * are not typically required outside of repository implementation.
     * <p>
     * More specifically, these methods are meant for implementations of a {@code Repository} wrapping another to be
     * able to properly have lifecycle operations registered with downstream {@code Repository} implementations.
     *
     * @param <E>  The type of entity this repository stores.
     * @param <ID> The type of identifier for entities in this repository.
     */
    non-sealed interface LifecycleManagement<ID, E> extends Repository<ID, E> {

        /**
         * Ensures that the given {@code entity} has its lifecycle managed in the given {@code processingContext}. This
         * ensures that when the {@code processingContext} commits, any changes detected in the entity state are
         * persisted in this repository's underlying storage, if present.
         * <p>
         * If a managed entity for this identifier was already present in the {@link ProcessingContext}, the new
         * instance will replace it.
         * <p>
         * Repositories may wrap entities. In that case, the returned instance may not be exactly the same (`==`
         * comparison) as the instance provided. It is always recommended to use the returned instance.
         *
         * @param entity            The entity to have its lifecycle attached to the given processing context.
         * @param processingContext The processing context to link the lifecycle with.
         * @return The instance of the entity whose lifecycle is managed by this repository.
         */
        ManagedEntity<ID, E> attach(ManagedEntity<ID, E> entity, ProcessingContext processingContext);
    }
}
