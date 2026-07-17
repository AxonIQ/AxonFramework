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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;
import static org.axonframework.common.BuilderUtils.assertNonNull;

/**
 * An {@link EntityChildMetamodel} that handles commands and events for a list of child entities.
 * <p>
 * It will use the provided {@link ChildEntityFieldDefinition} to resolve the child entities from the parent entity.
 * Once the entities are resolved, it will delegate the command- and event-handling to the child entity metamodel(s),
 * based on the {@code commandTargetResolver} and {@code eventTargetMatcher} respectively.
 *
 * @param <C> the type of the child entity
 * @param <P> the type of the parent entity
 * @author Mitchell Herrijgers
 * @since 5.0.0
 */
public class ListEntityChildMetamodel<C, P> extends AbstractEntityChildMetamodel<C, P> {

    private final ChildEntityFieldDefinition<P, List<C>> childEntityFieldDefinition;

    private ListEntityChildMetamodel(
            EntityMetamodel<C> metamodel,
            ChildEntityFieldDefinition<P, List<C>> childEntityFieldDefinition,
            CommandTargetResolver<C> commandTargetResolver,
            EventTargetMatcher<C> eventTargetMatcher
    ) {
        super(metamodel, commandTargetResolver, eventTargetMatcher);
        this.childEntityFieldDefinition =
                requireNonNull(childEntityFieldDefinition, "The childEntityFieldDefinition may not be null.");
    }

    @Override
    protected Map<Object, C> getChildEntities(P parent) {
        List<C> childEntities = childEntityFieldDefinition.getChildValue(parent);
        if (childEntities == null) {
            return Map.of();
        }
        Map<Object, C> indexedChildEntities = new LinkedHashMap<>();
        for (int i = 0; i < childEntities.size(); i++) {
            indexedChildEntities.put(i, childEntities.get(i));
        }
        return indexedChildEntities;
    }

    @Override
    protected P applyEvolvedChildEntities(P entity, Map<Object, C> evolvedChildEntities) {
        return childEntityFieldDefinition.evolveParentBasedOnChildInput(
                entity, new ArrayList<>(evolvedChildEntities.values())
        );
    }

    @Override
    public EntityMetamodel<C> entityMetamodel() {
        return metamodel;
    }

    @Override
    public String toString() {
        return "ListEntityChildModel{entityType=" + entityType().getName() + '}';
    }

    /**
     * Creates a new {@link Builder} for the given parent class and child entity metamodel. The
     * {@link ChildEntityFieldDefinition} is required to resolve the child entities from the parent entity and evolve
     * the parent entity based on the child entities. The {@link CommandTargetResolver commandTargetResolver} and
     * {@link EventTargetMatcher eventTargetMatcher} are both required, as they are used to match the child entities to
     * the command and event respectively.
     *
     * @param parentClass     The class of the parent entity.
     * @param entityMetamodel The {@link EntityMetamodel} of the child entity.
     * @param <C>             The type of the child entity.
     * @param <P>             The type of the parent entity.
     * @return A new {@link Builder} for the given parent class and child entity metamodel.
     */
    public static <C, P> Builder<C, P> forEntityModel(Class<P> parentClass,
                                                      EntityMetamodel<C> entityMetamodel
    ) {
        return new Builder<>(parentClass, entityMetamodel);
    }

    /**
     * Builder for creating a {@link ListEntityChildMetamodel} for the given parent class and child entity metamodel.
     * The builder can be used to configure the child entity metamodel and create a new instance of
     * {@link ListEntityChildMetamodel}. The {@link ChildEntityFieldDefinition} is required to resolve the child
     * entities from the parent entity and evolve the parent entity based on the child entities. The
     * {@link CommandTargetResolver commandTargetResolver} and {@link EventTargetMatcher eventTargetMatcher} are both
     * required, as they are used to match the child entities to the command and event respectively.
     *
     * @param <C> the type of the child entity
     * @param <P> the type of the parent entity
     */
    public static class Builder<C, P> extends AbstractEntityChildMetamodel.Builder<C, P, Builder<C, P>> {

        @SuppressWarnings("NotNullFieldNotInitialized") // Ensured by validate()
        private ChildEntityFieldDefinition<P, List<C>> childEntityFieldDefinition;

        @SuppressWarnings("unused") // Is used for generics
        private Builder(Class<P> parentClass, EntityMetamodel<C> metamodel) {
            super(parentClass, metamodel);
        }

        /**
         * Sets the {@link ChildEntityFieldDefinition} to use for resolving the child entities from the parent entity
         * and evolving the parent entity based on the evolved child entities.
         *
         * @param fieldDefinition the {@link ChildEntityFieldDefinition} to use for resolving the child entities from
         *                        the parent entity
         * @return builder instance for a fluent API
         */
        public Builder<C, P> childEntityFieldDefinition(
                ChildEntityFieldDefinition<P, List<C>> fieldDefinition) {
            this.childEntityFieldDefinition =
                    requireNonNull(fieldDefinition, "The childEntityFieldDefinition may not be null.");
            return this;
        }

        /**
         * Builds a new {@link ListEntityChildMetamodel} instance with the configured properties. The
         * {@link ChildEntityFieldDefinition}, {@link EventTargetMatcher}, and {@link CommandTargetResolver} are
         * required to be set before calling this method.
         *
         * @return a new {@link ListEntityChildMetamodel} instance with the configured properties
         */
        public ListEntityChildMetamodel<C, P> build() {
            this.validate();
            return new ListEntityChildMetamodel<>(
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
