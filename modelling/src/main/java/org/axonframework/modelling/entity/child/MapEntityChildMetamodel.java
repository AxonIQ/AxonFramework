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

import java.util.LinkedHashMap;
import java.util.Map;

import static java.util.Objects.requireNonNull;
import static org.axonframework.common.BuilderUtils.assertNonNull;

/**
 * An {@link EntityChildMetamodel} that handles commands and events for child entities stored in a {@link Map}.
 * <p>
 * It will use the provided {@link ChildEntityFieldDefinition} to resolve the child entities from the parent entity.
 * Once the entities are resolved, it will delegate the command- and event-handling to the child entity metamodel(s),
 * based on the {@code commandTargetResolver} and {@code eventTargetMatcher} respectively.
 * <p>
 * The identity of a child entity is the {@link Map}'s own key. This means the key of a child entity is preserved across
 * events: a child that is evolved keeps its original key, and a child that is evolved to {@code null} is removed from
 * the map under that same key.
 *
 * @param <K> the type of the key of the {@link Map} containing the child entities
 * @param <C> the type of the child entity
 * @param <P> the type of the parent entity
 * @author Steven van Beelen
 * @since 5.3.0
 */
public class MapEntityChildMetamodel<K, C, P> extends AbstractEntityChildMetamodel<C, P> {

    private final ChildEntityFieldDefinition<P, Map<K, C>> childEntityFieldDefinition;

    private MapEntityChildMetamodel(
            EntityMetamodel<C> metamodel,
            ChildEntityFieldDefinition<P, Map<K, C>> childEntityFieldDefinition,
            CommandTargetResolver<C> commandTargetResolver,
            EventTargetMatcher<C> eventTargetMatcher
    ) {
        super(metamodel, commandTargetResolver, eventTargetMatcher);
        this.childEntityFieldDefinition =
                requireNonNull(childEntityFieldDefinition, "The childEntityFieldDefinition may not be null.");
    }

    /**
     * Creates a new {@link Builder} for the given parent class and child entity metamodel.
     * <p>
     * The {@link ChildEntityFieldDefinition} is required to resolve the child entities from the parent entity and
     * evolve the parent entity based on the child entities. The {@link CommandTargetResolver} and
     * {@link EventTargetMatcher} are both required, as they are used to match the child entities to the command and
     * event respectively.
     *
     * @param parentClass     the class of the parent entity
     * @param entityMetamodel the {@link EntityMetamodel} of the child entity
     * @param <K>             the type of the key of the {@link Map} containing the child entities
     * @param <C>             the type of the child entity
     * @param <P>             the type of the parent entity
     * @return a new {@link Builder} for the given parent class and child entity metamodel
     */
    public static <K, C, P> Builder<K, C, P> forEntityModel(Class<P> parentClass, EntityMetamodel<C> entityMetamodel) {
        return new Builder<>(parentClass, entityMetamodel);
    }

    @Override
    protected Map<Object, C> getChildEntities(P parent) {
        Map<K, C> childEntities = childEntityFieldDefinition.getChildValue(parent);
        return childEntities == null ? Map.of() : new LinkedHashMap<>(childEntities);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected P applyEvolvedChildEntities(P entity, Map<Object, C> evolvedChildEntities) {
        return childEntityFieldDefinition.evolveParentBasedOnChildInput(entity, (Map<K, C>) evolvedChildEntities);
    }

    @Override
    public EntityMetamodel<C> entityMetamodel() {
        return metamodel;
    }

    @Override
    public String toString() {
        return "MapEntityChildModel{entityType=" + entityType().getName() + '}';
    }

    /**
     * Builder for creating a {@link MapEntityChildMetamodel} for the given parent class and child entity metamodel. The
     * builder can be used to configure the child entity metamodel and create a new instance of
     * {@link MapEntityChildMetamodel}. The {@link ChildEntityFieldDefinition} is required to resolve the child entities
     * from the parent entity and evolve the parent entity based on the child entities. The
     * {@link CommandTargetResolver commandTargetResolver} and {@link EventTargetMatcher eventTargetMatcher} are both
     * required, as they are used to match the child entities to the command and event respectively.
     *
     * @param <K> the type of the key of the {@link Map} containing the child entities
     * @param <C> the type of the child entity
     * @param <P> the type of the parent entity
     */
    public static class Builder<K, C, P> extends AbstractEntityChildMetamodel.Builder<C, P, Builder<K, C, P>> {

        @SuppressWarnings("NotNullFieldNotInitialized")
        private ChildEntityFieldDefinition<P, Map<K, C>> childEntityFieldDefinition;

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
         * @return this builder instance for a fluent API
         */
        public Builder<K, C, P> childEntityFieldDefinition(ChildEntityFieldDefinition<P, Map<K, C>> fieldDefinition) {
            assertNonNull(fieldDefinition, "The childEntityFieldDefinition may not be null.");
            this.childEntityFieldDefinition = fieldDefinition;
            return this;
        }

        /**
         * Builds a new {@link MapEntityChildMetamodel} instance with the configured properties. The
         * {@link ChildEntityFieldDefinition}, {@link EventTargetMatcher}, and {@link CommandTargetResolver} are
         * required to be set before calling this method.
         *
         * @return a new {@link MapEntityChildMetamodel} instance with the configured properties
         */
        public MapEntityChildMetamodel<K, C, P> build() {
            this.validate();
            return new MapEntityChildMetamodel<>(
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
