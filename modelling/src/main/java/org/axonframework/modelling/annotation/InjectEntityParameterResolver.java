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

package org.axonframework.modelling.annotation;

import org.axonframework.common.configuration.Configuration;
import org.axonframework.messaging.core.GenericMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.annotation.ParameterResolver;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.modelling.EntityIdResolutionException;
import org.axonframework.modelling.EntityIdResolver;
import org.axonframework.modelling.StateManager;
import org.axonframework.modelling.repository.EntityNotFoundException;
import org.axonframework.modelling.repository.ManagedEntity;
import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElseGet;

/**
 * A {@link ParameterResolver} implementation that loads an entity from the {@link StateManager} of the given
 * {@link Configuration}.
 * <p>
 * The entity is loaded based on the identifier resolved from the message using the given {@link EntityIdResolver}. Can
 * either load the {@link org.axonframework.modelling.repository.ManagedEntity} or just the entity itself.
 * <p>
 * For the entity itself, the {@link MissingEntityStrategy} determines what happens when it could not be found. It
 * either propagates an {@link EntityNotFoundException}, resolves to {@code null}, or resolves to an {@link Optional},
 * depending on whether the {@code MissingEntityStrategy} equals {@link MissingEntityStrategy#FAIL},
 * {@link MissingEntityStrategy#RESOLVE_NULL}, or {@link MissingEntityStrategy#RESOLVE_OPTIONAL} respectively.
 * <p>
 * A {@link org.axonframework.modelling.repository.ManagedEntity}-typed parameter is unaffected by this. It is always
 * passed through exactly as the {@link StateManager} resolved it leaving the interpretation entirely up to the
 * handler.
 *
 * @author Mitchell Herrijgers
 * @author Steven van Beelen
 * @since 5.0.0
 */
class InjectEntityParameterResolver implements ParameterResolver<Object> {

    private final Configuration configuration;
    private final Class<?> type;
    private final EntityIdResolver<?> identifierResolver;
    private final boolean managedEntity;
    private final MissingEntityStrategy missingEntityStrategy;

    /**
     * Instantiate a {@link ParameterResolver} that loads an entity of {@code type} using the given
     * {@code stateManager}, resolving the needed id using the {@code identifierResolver}.
     * <p>
     * This constructor depends on the {@link Configuration} instead of the {@link StateManager} to prevent circular
     * dependencies during creation of message handlers. For example, if the repository uses an annotation-based event
     * state applier, it would construct methods, which would then require the {@link StateManager} to be created during
     * the construction of the parameter resolvers. This would lead to a circular dependency.
     *
     * @param configuration         the {@link Configuration} from which a {@link StateManager} can be retrieved to load
     *                              the entity
     * @param type                  the type of the entity to load
     * @param identifierResolver    the {@link EntityIdResolver} to resolve the id of the entity
     * @param managedEntity         whether the parameter is a
     *                              {@link org.axonframework.modelling.repository.ManagedEntity} instead of the entity
     *                              itself
     * @param missingEntityStrategy how to resolve the parameter when the entity could not be found
     */
    public InjectEntityParameterResolver(
            Configuration configuration,
            Class<?> type,
            EntityIdResolver<?> identifierResolver,
            boolean managedEntity,
            MissingEntityStrategy missingEntityStrategy
    ) {
        this.configuration = requireNonNull(configuration, "The Configuration is required");
        this.type = requireNonNull(type, "The type is required");
        this.identifierResolver = requireNonNull(identifierResolver, "The ModelIdentifierResolver is required");
        this.managedEntity = managedEntity;
        this.missingEntityStrategy = requireNonNull(missingEntityStrategy, "The MissingEntityStrategy is required");
    }

    @Override
    public CompletableFuture<Object> resolveParameterValue(ProcessingContext context) {
        Message message = requireNonNullElseGet(Message.fromContext(context), GenericMessage::emptyMessage);

        try {
            Object resolvedId = identifierResolver.resolve(message, context);
            StateManager stateManager = configuration.getComponent(StateManager.class);

            return managedEntity
                    ? resolveManagedEntity(stateManager, resolvedId, context)
                    : resolveEntity(stateManager, resolvedId, context);
        } catch (EntityIdResolutionException e) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Unable to inject entity parameter of type [%s] because [%s] was unable to resolve an entity id from [%s]"
                            .formatted(type, identifierResolver, message),
                    e
            ));
        }
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<Object> resolveManagedEntity(StateManager stateManager, Object resolvedId,
                                                           ProcessingContext context) {
        // Safe cast: widening from CompletableFuture<T> to CompletableFuture<Object>
        // Double cast through wildcard avoids unchecked cast warnings
        return (CompletableFuture<Object>) (CompletableFuture<?>) stateManager.loadManagedEntity(
                type, resolvedId, context
        );
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<Object> resolveEntity(StateManager stateManager,
                                                    Object entityId,
                                                    ProcessingContext context) {
        CompletableFuture<Object> entityFuture =
                (CompletableFuture<Object>) stateManager.loadEntity(type, entityId, context);

        return switch (missingEntityStrategy) {
            case FAIL -> entityFuture.thenApply(value -> requireEntityFound(value, entityId));
            case RESOLVE_NULL -> entityFuture.exceptionally(e -> resolveOnEntityNotFound(e, null));
            case RESOLVE_OPTIONAL -> entityFuture.handle((value, e) -> e != null
                    ? resolveOnEntityNotFound(e, Optional.empty())
                    : Optional.ofNullable(value));
        };
    }

    private Object requireEntityFound(@Nullable Object entity, Object entityId) {
        if (entity == null) {
            throw new EntityNotFoundException(entityId);
        }
        return entity;
    }

    @Nullable
    private static Object resolveOnEntityNotFound(Throwable e, @Nullable Object valueIfMissing) {
        Throwable cause = e instanceof CompletionException ce ? ce.getCause() : e;
        if (cause instanceof EntityNotFoundException) {
            return valueIfMissing;
        }
        if (e instanceof RuntimeException re) {
            throw re;
        }
        throw new CompletionException(e);
    }

    @Override
    public boolean matches(ProcessingContext context) {
        return Message.fromContext(context) != null;
    }

    /**
     * Determines how {@link #resolveEntity(StateManager, Object, ProcessingContext)} reacts when the entity to inject
     * cannot be found.
     * <p>
     * This strategy only applies to resolving the entity itself. A {@link ManagedEntity}-typed parameter is always
     * passed through unconditionally by {@link #resolveManagedEntity(StateManager, Object, ProcessingContext)},
     * regardless of this strategy.
     */
    enum MissingEntityStrategy {

        /**
         * Propagate the {@link EntityNotFoundException} raised while resolving the entity, failing the message being
         * handled.
         */
        FAIL,

        /**
         * Resolve the parameter to {@code null}.
         */
        RESOLVE_NULL,

        /**
         * Resolve the parameter to {@link Optional#empty()}, or to {@link Optional#of(Object)} of the loaded value when
         * the entity is found.
         */
        RESOLVE_OPTIONAL
    }
}
