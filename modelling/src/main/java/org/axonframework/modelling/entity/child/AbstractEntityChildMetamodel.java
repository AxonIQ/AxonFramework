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

package org.axonframework.modelling.entity.child;

import org.axonframework.common.annotation.Internal;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.CommandResultMessage;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.modelling.entity.ChildEntityNotFoundException;
import org.axonframework.modelling.entity.EntityMetamodel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;
import static org.axonframework.common.BuilderUtils.assertNonNull;

/**
 * Abstract {@link EntityChildMetamodel} that implements common functionality for most implementations.
 * <p>
 * It defines how to handle commands and events for a child entity. The implementor is responsible for defining how to
 * resolve the child entities from the parent, keyed by an implementation-specific identity
 * ({@link #getChildEntities(Object)}), and how to apply the evolved child entities to the parent
 * ({@link #applyEvolvedChildEntities(Object, Map)}).
 * <p>
 * The keys returned by {@link #getChildEntities(Object)} are opaque to this class; they only exist so that
 * {@link #evolve(Object, EventMessage, ProcessingContext)} can preserve the association between a child entity and its
 * identity when a child is evolved or removed, regardless of whether the parent stores its children in a {@code List}
 * (keyed by index), as a single field (keyed by a constant), or in a {@code Map} (keyed by the map's own keys).
 *
 * @param <C> the type of the child entity
 * @param <P> the type of the parent entity
 * @author Mitchell Herrijgers
 * @author Steven van Beelen
 * @since 5.0.0
 */
@Internal
public abstract class AbstractEntityChildMetamodel<C, P> implements EntityChildMetamodel<C, P> {

    protected final EntityMetamodel<C> metamodel;
    protected final CommandTargetResolver<C> commandTargetResolver;
    protected final EventTargetMatcher<C> eventTargetMatcher;

    protected AbstractEntityChildMetamodel(
            EntityMetamodel<C> metamodel,
            CommandTargetResolver<C> commandTargetResolver,
            EventTargetMatcher<C> eventTargetMatcher
    ) {
        this.metamodel = requireNonNull(metamodel, "The metamodel may not be null.");
        this.commandTargetResolver =
                requireNonNull(commandTargetResolver, "The commandTargetResolver may not be null.");
        this.eventTargetMatcher = requireNonNull(eventTargetMatcher, "The eventTargetMatcher may not be null.");
    }

    @Override
    public Set<QualifiedName> supportedCommands() {
        return metamodel.supportedCommands();
    }

    @Override
    public boolean canHandle(CommandMessage message,
                             P parentEntity,
                             ProcessingContext context) {
        if (!supportedCommands().contains(message.type().qualifiedName())) {
            return false;
        }
        List<C> childEntities = new ArrayList<>(getChildEntities(parentEntity).values());
        if (childEntities.isEmpty()) {
            return false;
        }
        return commandTargetResolver.getTargetChildEntity(childEntities, message, context) != null;
    }

    @Override
    public MessageStream.Single<CommandResultMessage> handle(CommandMessage message,
                                                             P parentEntity,
                                                             ProcessingContext context) {
        List<C> childEntities = new ArrayList<>(getChildEntities(parentEntity).values());
        C targetChildEntity = commandTargetResolver.getTargetChildEntity(childEntities, message, context);
        if (targetChildEntity == null) {
            return MessageStream.failed(new ChildEntityNotFoundException(message, parentEntity));
        }
        return metamodel.handleInstance(message, targetChildEntity, context);
    }

    @Override
    public P evolve(P entity, EventMessage event, ProcessingContext context) {
        Map<Object, C> children = getChildEntities(entity);
        boolean evolvedAnyChild = false;
        Map<Object, C> evolvedChildren = new LinkedHashMap<>();
        for (Map.Entry<Object, C> childEntry : children.entrySet()) {
            C child = childEntry.getValue();
            if (eventTargetMatcher.matches(child, event, context)) {
                evolvedAnyChild = true;
                C evolvedChild = metamodel.evolve(child, event, context);
                if (evolvedChild != null) {
                    evolvedChildren.put(childEntry.getKey(), evolvedChild);
                }
            } else {
                evolvedChildren.put(childEntry.getKey(), child);
            }
        }
        if (!evolvedAnyChild) {
            return entity;
        }
        return applyEvolvedChildEntities(entity, evolvedChildren);
    }

    /**
     * Resolves the child entities of the given {@code parent}, keyed by an implementation-specific identity.
     * <p>
     * The returned {@link Map} must preserve iteration order (e.g. {@link LinkedHashMap}), as that order determines the
     * order in which candidates are offered to the {@link CommandTargetResolver} and {@link EventTargetMatcher}.
     *
     * @param parent the parent entity to resolve the child entities from
     * @return the child entities of the given {@code parent}, keyed by an implementation-specific identity
     */
    protected abstract Map<Object, C> getChildEntities(P parent);

    /**
     * Applies the evolved child entities, keyed by the same implementation-specific identity returned by
     * {@link #getChildEntities(Object)}, to the given {@code entity}. A key that was present in
     * {@link #getChildEntities(Object)} but is absent from {@code evolvedChildEntities} indicates that the
     * corresponding child entity was evolved to {@code null} and should be removed.
     *
     * @param entity               the parent entity to apply the evolved child entities to
     * @param evolvedChildEntities the evolved child entities, keyed by the same identity as
     *                             {@link #getChildEntities(Object)}
     * @return the evolved parent entity
     */
    protected abstract P applyEvolvedChildEntities(P entity, Map<Object, C> evolvedChildEntities);

    @Override
    public Class<C> entityType() {
        return metamodel.entityType();
    }

    protected abstract static class Builder<C, P, R extends Builder<C, P, R>> {

        protected final EntityMetamodel<C> metamodel;
        @SuppressWarnings("NotNullFieldNotInitialized") // Ensured by validate()
        protected CommandTargetResolver<C> commandTargetResolver;
        @SuppressWarnings("NotNullFieldNotInitialized") // Ensured by validate()
        protected EventTargetMatcher<C> eventTargetMatcher;

        @SuppressWarnings("unused") // Is used for generics
        protected Builder(Class<P> parentClass, EntityMetamodel<C> metamodel) {
            requireNonNull(parentClass, "The parentClass may not be null.");
            this.metamodel = requireNonNull(metamodel, "The metamodel may not be null.");
        }

        /**
         * Sets the {@link CommandTargetResolver} to use for resolving the child entity to handle the command. This
         * should return one child entity, or {@code null} if no child entity should handle the command.
         *
         * @param commandTargetResolver the {@link CommandTargetResolver} to use for resolving the child entity to
         *                              handle the command
         * @return this builder instance
         */
        @SuppressWarnings("unchecked")
        public R commandTargetResolver(CommandTargetResolver<C> commandTargetResolver) {
            this.commandTargetResolver = requireNonNull(commandTargetResolver,
                                                        "The commandTargetResolver may not be null.");
            return (R) this;
        }

        protected void validate() {
            assertNonNull(commandTargetResolver,
                          "The commandTargetResolver must be set before building the metamodel.");
            assertNonNull(eventTargetMatcher,
                          "The eventTargetMatcher must be set before building the metamodel.");
        }

        /**
         * Sets the {@link EventTargetMatcher} to determine whether a child entity should handle the given
         * {@link EventMessage}. This should return {@code true} if the child entity should handle the event, or
         * {@code false} if it should not.
         *
         * @param eventTargetMatcher the {@link EventTargetMatcher} to use for matching the child entities to the event
         * @return this builder instance
         */
        @SuppressWarnings("unchecked")
        public R eventTargetMatcher(EventTargetMatcher<C> eventTargetMatcher) {
            this.eventTargetMatcher = requireNonNull(eventTargetMatcher, "The eventTargetMatcher may not be null.");
            return (R) this;
        }
    }
}
