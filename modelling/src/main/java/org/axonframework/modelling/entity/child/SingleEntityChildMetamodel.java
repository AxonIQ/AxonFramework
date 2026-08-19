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

import static org.axonframework.common.BuilderUtils.assertNonNull;

/**
 * An {@link EntityChildMetamodel} that handles commands and events for a single child entity.
 * <p>
 * It will use the provided {@link ChildEntityFieldDefinition} to resolve the child entity from the parent entity. Once
 * the entity is resolved, it will delegate the command- and event-handling to the child entity metamodel.
 * <p>
 * The commands and events will, by default, be forwarded unconditionally to the child entity. If you have multiple
 * member fields, and want to match commands and events to a specific child entity, you can configure the
 * {@link CommandTargetResolver} and {@link EventTargetMatcher} to match the child entity based on the command or
 * event.
 *
 * @param <C> the type of the child entity
 * @param <P> the type of the parent entity
 * @author Mitchell Herrijgers
 * @since 5.0.0
 */
public class SingleEntityChildMetamodel<C, P> extends AbstractEntityChildMetamodel<C, P> {

    private static final Object SINGLE_KEY = new Object();

    private final ChildEntityFieldDefinition<P, C> childEntityFieldDefinition;

    private SingleEntityChildMetamodel(
            EntityMetamodel<C> metamodel,
            ChildEntityFieldDefinition<P, C> childEntityFieldDefinition,
            CommandTargetResolver<C> commandTargetMatcher,
            EventTargetMatcher<C> eventTargetMatcher
    ) {
        super(metamodel, commandTargetMatcher, eventTargetMatcher);
        this.childEntityFieldDefinition =
                Objects.requireNonNull(childEntityFieldDefinition, "The childEntityFieldDefinition may not be null.");
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
     * @param parentClass the class of the parent entity
     * @param metamodel   the {@link EntityMetamodel} of the child entity
     * @param <C>         the type of the child entity
     * @param <P>         the type of the parent entity
     * @return a new {@link Builder} for the given parent class and child entity metamodel
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
     * @param <C> the type of the child entity
     * @param <P> the type of the parent entity
     */
    public static class Builder<C, P> extends AbstractEntityChildMetamodel.Builder<C, P, Builder<C, P>> {

        @SuppressWarnings("NotNullFieldNotInitialized") // Ensured by validate()
        private ChildEntityFieldDefinition<P, C> childEntityFieldDefinition;

        @SuppressWarnings("unused") // Uses for generics
        private Builder(Class<P> parentClass, EntityMetamodel<C> childEntityMetamodel) {
            super(parentClass, childEntityMetamodel);
            this.commandTargetResolver = CommandTargetResolver.MATCH_ANY();
            this.eventTargetMatcher = EventTargetMatcher.MATCH_ANY();
        }

        /**
         * Sets the {@link ChildEntityFieldDefinition} to use for resolving the child entity from the parent entity and
         * evolving the parent entity based on the evolved child entity.
         *
         * @param fieldDefinition the {@link ChildEntityFieldDefinition} to use for resolving the child entities from
         *                        the parent entity
         * @return this builder instance
         */
        public Builder<C, P> childEntityFieldDefinition(ChildEntityFieldDefinition<P, C> fieldDefinition) {
            assertNonNull(fieldDefinition, "The fieldDefinition may not be null.");
            this.childEntityFieldDefinition = fieldDefinition;
            return this;
        }

        /**
         * Builds a new {@link SingleEntityChildMetamodel} instance with the configured properties. The
         * {@link ChildEntityFieldDefinition} is required to be set before calling this method.
         *
         * @return a new {@link SingleEntityChildMetamodel} instance with the configured properties
         */
        public SingleEntityChildMetamodel<C, P> build() {
            this.validate();
            return new SingleEntityChildMetamodel<>(
                    metamodel, childEntityFieldDefinition, commandTargetResolver, eventTargetMatcher
            );
        }

        @Override
        protected void validate() {
            assertNonNull(childEntityFieldDefinition,
                          "The ChildEntityFieldDefinition must be set before building the metamodel.");
            super.validate();
        }
    }
}
