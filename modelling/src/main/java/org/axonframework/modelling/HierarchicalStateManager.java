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

import org.axonframework.common.FutureUtils;
import org.axonframework.common.configuration.Module;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.modelling.repository.ManagedEntity;
import org.axonframework.modelling.repository.Repository;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * {@link StateManager} that can load an entity from two delegates, giving preference to the child delegate and then the
 * parent. This is useful to encapsulate a set of repositories that are only relevant in a specific context, such as a
 * specific {@link Module}.
 * <p>
 * Any registrations of {@link Repository} will be done on the child {@link StateManager}.
 *
 * @author Mitchell Herrijgers
 * @since 5.0.0
 */
public class HierarchicalStateManager implements StateManager {

    private final StateManager parent;
    private final StateManager child;

    private HierarchicalStateManager(StateManager parent, StateManager child) {
        this.parent = Objects.requireNonNull(parent, "Parent StateManager may not be null");
        this.child = Objects.requireNonNull(child, "Child StateManager may not be null");
    }

    /**
     * Creates a new hierarchical {@link StateManager} that delegates to the given {@code parent} and {@code child}
     * managers, giving preference to the {@code child} manager.
     *
     * @param parent The parent {@link StateManager} to delegate if the child {@link StateManager} cannot load the
     *               entity.
     * @param child  The child {@link StateManager} to try first.
     * @return A new hierarchical {@link StateManager} that delegates to the given managers.
     */
    public static HierarchicalStateManager create(StateManager parent, StateManager child) {
        return new HierarchicalStateManager(parent, child);
    }

    @Override
    public <I, T> StateManager register(Repository<I, T> repository) {
        Objects.requireNonNull(repository, "The repository must not be null.");
        child.register(repository);
        return this;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Delegates directly to {@code StateManager#loadManagedEntity(Class, Object, ProcessingContext)} of the
     * {@link #getChild() child}, falling back to the {@link #getParent() parent} when the child completes exceptionally
     * with a {@link MissingRepositoryException}. Delegating the load itself, rather than first locating a
     * {@link Repository} through {@link #repository(Class, Class)} and invoking it here, ensures each delegate applies
     * its own resolution semantics for {@code type}. {@link SimpleStateManager}, for instance, resolves a
     * {@link Repository} registered for a supertype when asked for a subtype, whereas {@link #repository(Class, Class)}
     * only matches the exact registered type. Re-implementing that resolution here would either duplicate or diverge
     * from the delegate's own behavior; calling {@code loadManagedEntity} avoids that entirely, and still composes
     * correctly when the delegate is itself a {@code HierarchicalStateManager}.
     * <p>
     * Delegates such as {@link SimpleStateManager} complete their {@link CompletableFuture} exceptionally rather than
     * throwing {@link MissingRepositoryException} synchronously, so the parent fallback is expressed with
     * {@link CompletableFuture#exceptionallyCompose(java.util.function.Function)} rather than a
     * {@code try}/{@code catch}. Any other exception is propagated to the caller unchanged.
     */
    @Override
    public <I, T> CompletableFuture<ManagedEntity<I, T>> loadManagedEntity(Class<T> type,
                                                                           I id,
                                                                           ProcessingContext context) {
        return child.loadManagedEntity(type, id, context)
                    .exceptionallyCompose(ex -> FutureUtils.unwrap(ex) instanceof MissingRepositoryException
                            ? parent.loadManagedEntity(type, id, context)
                            : CompletableFuture.failedFuture(ex));
    }

    @Override
    public Set<Class<?>> registeredEntities() {
        HashSet<Class<?>> classes = new HashSet<>();
        classes.addAll(parent.registeredEntities());
        classes.addAll(child.registeredEntities());
        return classes;
    }

    @Override
    public Set<Class<?>> registeredIdsFor(Class<?> entityType) {
        HashSet<Class<?>> classes = new HashSet<>();
        classes.addAll(parent.registeredIdsFor(entityType));
        classes.addAll(child.registeredIdsFor(entityType));
        return classes;
    }

    @Override
    public <I, T> @Nullable Repository<I, T> repository(Class<T> entityType, Class<I> idType) {
        Repository<I, T> childRepository = child.repository(entityType, idType);
        if (childRepository != null) {
            return childRepository;
        }
        return parent.repository(entityType, idType);
    }

    /**
     * Returns the parent {@link StateManager} of this {@code HierarchicalStateManager}.
     *
     * @return The parent {@link StateManager} of this {@code HierarchicalStateManager}.
     */
    public StateManager getParent() {
        return parent;
    }

    /**
     * Returns the child {@link StateManager} of this {@code HierarchicalStateManager}.
     *
     * @return The child {@link StateManager} of this {@code HierarchicalStateManager}.
     */
    public StateManager getChild() {
        return child;
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeProperty("parent", parent);
        descriptor.describeProperty("child", child);
    }
}
