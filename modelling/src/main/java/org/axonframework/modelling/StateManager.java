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

package org.axonframework.modelling;

import org.axonframework.common.infra.DescribableComponent;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.modelling.repository.ManagedEntity;
import org.axonframework.modelling.repository.Repository;
import org.axonframework.modelling.repository.SimpleRepository;
import org.axonframework.modelling.repository.SimpleRepositoryEntityLoader;
import org.axonframework.modelling.repository.SimpleRepositoryEntityPersister;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * The {@code StateManager} enables applications to load entities based on the type of the entity and an id, and to
 * persist them. Implementations may specify whether they load entities through
 * {@link Repository#load(Object, ProcessingContext)} or {@link Repository#loadOrCreate(Object, ProcessingContext)}.
 * <p>
 * Entities are {@link #register(Repository) registered} by their type in combination with their id. The combination of
 * {@link Repository#entityType() entity type} and {@link Repository#idType() id type} of all repositories must be
 * unique and unambiguous. This means you cannot register a repository if another conflicting repository already exists.
 * If you do, a {@link RepositoryAlreadyRegisteredException} will be thrown. Note that superclasses and subclasses of
 * each other are considered conflicting.
 *
 * @author Mitchell Herrijgers
 * @since 5.0.0
 */
public interface StateManager extends DescribableComponent {

    /**
     * Registers an {@link Repository} for use with this {@code StateManager}. The combination of
     * {@link Repository#entityType()} and {@link Repository#idType()} must be unique for all registered repositories.
     *
     * @param repository The {@link Repository} to use for loading state.
     * @param <ID>       The type of id.
     * @param <T>        The type of the entity.
     * @return This {@code StateManager} for fluent interfacing.
     * @throws RepositoryAlreadyRegisteredException if a repository with the same entity type and id type is already
     *                                              registered.
     */
    <ID, T> StateManager register(Repository<ID, T> repository);

    /**
     * Registers a load and save function for state type {@code T} with id of type {@code ID}. Creates a
     * {@link SimpleRepository} for the given type with the given load and save functions.
     *
     * @param idType     The type of the identifier.
     * @param entityType The type of the state.
     * @param loader     The function to load state.
     * @param persister  The function to persist state.
     * @param <ID>       The type of id.
     * @param <T>        The type of state.
     * @return This {@code StateManager} for fluent interfacing.
     * @throws RepositoryAlreadyRegisteredException if a repository with the same entity type and id type is already
     *                                              registered.
     */
    default <ID, T> StateManager register(Class<ID> idType,
                                          Class<T> entityType,
                                          SimpleRepositoryEntityLoader<ID, T> loader,
                                          SimpleRepositoryEntityPersister<ID, T> persister
    ) {
        return register(new SimpleRepository<>(idType, entityType, loader, persister));
    }

    /**
     * Retrieves an entity of the given {@code type} and {@code id}. The {@link CompletableFuture} will resolve to the
     * entity, or complete exceptionally if it could not be resolved.
     * <p>
     * If multiple repositories are registered for the given {@code entityType} that can handle the given {@code id}
     * (through superclass registration), the most specific repository is used.
     *
     * @param <T>     the type of state to retrieve
     * @param type    the type of state to retrieve
     * @param id      the id of the state to retrieve
     * @param context the {@link ProcessingContext context} to load the entity in
     * @param <I>     the type of the identifier of the entity
     * @return a {@link CompletableFuture} which resolves to the entity instance
     * @throws MissingRepositoryException contained in the resulting {@link CompletableFuture} whenever this
     *                                    {@code StateManager} does not control the {@link Repository} to load the
     *                                    entity from
     */
    default <I, T> CompletableFuture<T> loadEntity(
            Class<T> type,
            I id,
            ProcessingContext context
    ) {
        return loadManagedEntity(type, id, context).thenApply(ManagedEntity::entity);
    }

    /**
     * Retrieves a {@link ManagedEntity} of the given {@code type} and {@code id}. The {@link CompletableFuture} will
     * resolve to a {@link ManagedEntity}, or complete exceptionally if it could not be resolved.
     *
     * @param type    the type of state to retrieve
     * @param id      the id of the state to retrieve
     * @param context the {@link ProcessingContext context} to load the entity in
     * @param <ID>    the type of the identifier of the entity
     * @param <T>     the type of the entity
     * @return a {@link CompletableFuture} which resolves to the entity instance
     * @throws MissingRepositoryException contained in the resulting {@link CompletableFuture} whenever this
     *                                    {@code StateManager} does not control the {@link Repository} to load the
     *                                    {@link ManagedEntity} from
     */
    <ID, T> CompletableFuture<ManagedEntity<ID, T>> loadManagedEntity(
            Class<T> type,
            ID id,
            ProcessingContext context);

    /**
     * The types of entities that are registered with this {@code StateManager}.
     *
     * @return the types of entities that are registered with this {@code StateManager}.
     */
    Set<Class<?>> registeredEntities();

    /**
     * The types of identifiers that are registered with this {@code StateManager} for the given {@code entityType}.
     *
     * @param entityType The type of the entity.
     * @return the types of identifiers that are registered with this {@code StateManager} for the given
     * {@code entityType}.
     */
    Set<Class<?>> registeredIdsFor(Class<?> entityType);

    /**
     * Returns the {@link Repository} for the given {@code type}. Returns {@code null} if no repository is registered
     * for the given type and id.
     *
     * @param <ID>        The type of the identifier of the entity.
     * @param <T>        The type of the entity.
     * @param entityType The type of the entity.
     * @param idType     The type of the identifier of the entity.
     * @return The {@link Repository} for the given {@code idType} and {@code entityType}.
     */
    <ID, T> Repository<ID, T> repository(Class<T> entityType, Class<ID> idType);
}
