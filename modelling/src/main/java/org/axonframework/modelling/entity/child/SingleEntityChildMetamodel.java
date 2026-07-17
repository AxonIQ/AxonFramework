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

import org.axonframework.modelling.entity.EntityMetamodel;

import java.util.Map;
import java.util.Objects;

/**
 * An {@link EntityChildMetamodel} that handles commands and events for a single child entity. It will use the provided
 * {@link ChildEntityFieldDefinition} to resolve the child entity from the parent entity. Once the entity is resolved,
 * it will delegate the command- and event-handling to the child entity metamodel.
 * <p>
 * The commands and events will, by default, be forwarded unconditionally to the child entity. If you have multiple
 * member fields, and want to match commands and events to a specific child entity, you can configure the
 * {@link CommandTargetResolver} and {@link EventTargetMatcher} to match the child entity based on the command or
 * event.
 *
 * @param <C> The type of the child entity.
 * @param <P> The type of the parent entity.
 * @author Mitchell Herrijgers
 * @since 5.0.0
 */
public class SingleEntityChildMetamodel<C, P> extends AbstractEntityChildMetamodel<C, P> {

    private static final Object SINGLE_KEY = new Object();

    private final ChildEntityFieldDefinition<P, C> childEntityFieldDefinition;

    private SingleEntityChildMetamodel(EntityMetamodel<C> metamodel,
                                       ChildEntityFieldDefinition<P, C> childEntityFieldDefinition,
                                       CommandTargetResolver<C> commandTargetMatcher,
                                       EventTargetMatcher<C> eventTargetMatcher
    ) {
        super(
                metamodel,
                commandTargetMatcher,
                eventTargetMatcher
        );
        this.childEntityFieldDefinition = Objects.requireNonNull(
                childEntityFieldDefinition,
                "The childEntityFieldDefinition may not be null."
        );
    }

    @Override
    protected Map<Object, C> getChildEntities(P parent) {
        C childEntity = childEntityFieldDefinition.getChildValue(parent);
        return childEntity != null ? Map.of(SINGLE_KEY, childEntity) : Map.of();
    }

    @Override
    protected P applyEvolvedChildEntities(P entity, Map<Object, C> evolvedChildEntities) {
        if (evolvedChildEntities.isEmpty()) {
            return childEntityFieldDefinition.evolveParentBasedOnChildInput(entity, null);
        }
        return childEntityFieldDefinition.evolveParentBasedOnChildInput(
                entity, evolvedChildEntities.values().iterator().next()
        );
    }

    @Override
    public EntityMetamodel<C> entityMetamodel() {
        return metamodel;
    }

    @Override
    public String toString() {
        return "SingleEntityChildMetaModel{entityType=" + entityType().getName() + '}';
    }

    /**
     * Creates a new {@link Builder} for the given parent class and child entity metamodel. The
     * {@link ChildEntityFieldDefinition} is required to resolve the child entity from the parent entity and evolve the
     * parent entity based on the child entities.
     *
     * @param parentClass The class of the parent entity.
     * @param metamodel   The {@link EntityMetamodel} of the child entity.
     * @param <C>         The type of the child entity.
     * @param <P>         The type of the parent entity.
     * @return A new {@link Builder} for the given parent class and child entity metamodel.
     */
    public static <C, P> Builder<C, P> forEntityModel(Class<P> parentClass,
                                                      EntityMetamodel<C> metamodel) {
        return new Builder<>(parentClass, metamodel);
    }


    /**
     * Builder for creating a {@link SingleEntityChildMetamodel} for the given parent class and child entity metamodel.
     * The {@link ChildEntityFieldDefinition} is required to resolve the child entities from the parent entity and
     * evolve the parent entity based on the child entities.
     * <p>
     * The {@link CommandTargetResolver} and {@link EventTargetMatcher} are defaulted to
     * {@link CommandTargetResolver#MATCH_ANY()} and {@link EventTargetMatcher#MATCH_ANY()} respectively, meaning that
     * the child entity will always match all commands and all events. If you have multiple member fields, and want to
     * match commands and events to a specific child entity, you can configure the {@link CommandTargetResolver} and
     * {@link EventTargetMatcher} to match the child entity based on the command or event.
     *
     * @param <C> The type of the child entity.
     * @param <P> The type of the parent entity.
     */
    public static class Builder<C, P> extends AbstractEntityChildMetamodel.Builder<C, P, Builder<C, P>> {

        private ChildEntityFieldDefinition<P, C> childEntityFieldDefinition;

        @SuppressWarnings("unused") // Uses for generics
        private Builder(Class<P> parentClass,
                        EntityMetamodel<C> childEntityMetamodel
        ) {
            super(parentClass, childEntityMetamodel);
            this.commandTargetResolver = CommandTargetResolver.MATCH_ANY();
            this.eventTargetMatcher = EventTargetMatcher.MATCH_ANY();
        }

        /**
         * Sets the {@link ChildEntityFieldDefinition} to use for resolving the child entity from the parent entity and
         * evolving the parent entity based on the evolved child entity.
         *
         * @param fieldDefinition The {@link ChildEntityFieldDefinition} to use for resolving the child entities from
         *                        the parent entity
         * @return This builder instance.
         */
        public Builder<C, P> childEntityFieldDefinition(ChildEntityFieldDefinition<P, C> fieldDefinition) {
            this.childEntityFieldDefinition = Objects.requireNonNull(fieldDefinition,
                                                                     "The fieldDefinition may not be null.");
            return this;
        }

        /**
         * Builds a new {@link SingleEntityChildMetamodel} instance with the configured properties. The
         * {@link ChildEntityFieldDefinition} is required to be set before calling this method.
         *
         * @return A new {@link SingleEntityChildMetamodel} instance with the configured properties.
         */
        public SingleEntityChildMetamodel<C, P> build() {
            this.validate();
            return new SingleEntityChildMetamodel<>(metamodel,
                                                    childEntityFieldDefinition,
                                                    commandTargetResolver,
                                                    eventTargetMatcher
            );
        }
    }
}
