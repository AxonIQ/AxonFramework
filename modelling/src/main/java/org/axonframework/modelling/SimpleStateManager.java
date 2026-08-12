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

import org.axonframework.common.BuilderUtils;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.modelling.repository.ManagedEntity;
import org.axonframework.modelling.repository.Repository;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Simple implementation of the {@link StateManager}.
 * <p>
 * Keeps a list of all registered {@link Repository repositories} and delegates the loading of entities to the
 * appropriate repository through the use of {@link Repository#loadOrCreate(Object, ProcessingContext)}. Completes the
 * returned {@link CompletableFuture} exceptionally with a {@link MissingRepositoryException} if no repository is found
 * for the given entity type and the provided id.
 *
 * @author Mitchell Herrijgers
 * @see StateManager
 * @since 5.0.0
 */
public class SimpleStateManager implements StateManager {

    private final String name;
    private final List<Repository<?, ?>> repositories = new CopyOnWriteArrayList<>();

    /**
     * Creates a new {@code SimpleStateManager} with the given {@code name}.
     *
     * @param name The name of the state manager, used for describing the component.
     * @return A new {@code SimpleStateManager} with the given name.
     */
    public static StateManager named(String name) {
        return new SimpleStateManager(name);
    }

    private SimpleStateManager(String name) {
        BuilderUtils.assertNonEmpty(name, "The name must be non-empty.");
        this.name = name;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <I, T> CompletableFuture<ManagedEntity<I, T>> loadManagedEntity(Class<T> entityType,
                                                                           I id,
                                                                           ProcessingContext context) {
        return repositories
                .stream()
                .filter(r -> r.entityType().isAssignableFrom(entityType))
                .filter(r -> r.idType().isAssignableFrom(id.getClass()))
                .map(r -> (Repository<I, T>) r)
                .findFirst()
                .map(repository -> repository.loadOrCreate(id, context)
                                             .thenApply(me -> {
                                                 if (me.entity() != null && !entityType.isInstance(me.entity())) {
                                                     throw new LoadedEntityNotOfExpectedTypeException(
                                                             me.entity().getClass(), entityType
                                                     );
                                                 }
                                                 return me;
                                             }))
                .orElseGet(() -> CompletableFuture.failedFuture(
                        new MissingRepositoryException(id.getClass(), entityType)
                ));
    }

    @Override
    public Set<Class<?>> registeredEntities() {
        return repositories.stream()
                           .map(Repository::entityType)
                           .collect(Collectors.toSet());
    }

    @Override
    public Set<Class<?>> registeredIdsFor(Class<?> entityType) {
        return repositories.stream()
                           .filter(r -> r.entityType().equals(entityType))
                           .map(Repository::idType)
                           .collect(Collectors.toSet());
    }

    @Override
    public <I, T> @Nullable Repository<I, T> repository(Class<T> entityType, Class<I> idType) {
        //noinspection unchecked
        return (Repository<I, T>) repositories.stream()
                                              .filter(r -> r.entityType().equals(entityType))
                                              .filter(r -> r.idType().equals(idType))
                                              .findFirst()
                                              .orElse(null);
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeProperty("name", name);
        descriptor.describeProperty("repositories", repositories);
    }

    @Override
    public <I, T> StateManager register(Repository<I, T> repository) {
        Objects.requireNonNull(repository, "The repository must not be null.");
        Optional<Repository<?, ?>> registeredRepository = repositories
                .stream()
                .filter(r -> match(r, repository))
                .findFirst();

        if (registeredRepository.isPresent()) {
            throw new RepositoryAlreadyRegisteredException(repository, registeredRepository.get());
        }

        repositories.add(repository);
        return this;
    }

    /**
     * Checks if the given repositories match based on their entity and id types. Types match if it's the same type or
     * if one type is a superclass of the other. This ensures that there are no conflicts when loading entities. For any
     * id and entity type combination, only one repository should exist.
     */
    private boolean match(Repository<?, ?> repositoryOne, Repository<?, ?> repositoryTwo) {
        return matchesBasedOnEntityType(repositoryOne, repositoryTwo) && matchesBasedOnIdType(repositoryOne,
                                                                                              repositoryTwo);
    }

    private static boolean matchesBasedOnIdType(Repository<?, ?> repositoryOne,
                                                Repository<?, ?> repositoryTwo) {
        return repositoryOne.idType().isAssignableFrom(repositoryTwo.idType())
                || repositoryTwo.idType().isAssignableFrom(repositoryOne.idType());
    }

    private static boolean matchesBasedOnEntityType(Repository<?, ?> repositoryOne,
                                                    Repository<?, ?> repositoryTwo) {
        return repositoryOne.entityType().isAssignableFrom(repositoryTwo.entityType())
                || repositoryTwo.entityType().isAssignableFrom(repositoryOne.entityType());
    }
}
