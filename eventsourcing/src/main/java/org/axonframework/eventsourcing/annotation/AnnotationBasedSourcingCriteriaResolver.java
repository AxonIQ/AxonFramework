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

package org.axonframework.eventsourcing.annotation;

import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.common.infra.DescribableComponent;
import org.axonframework.eventsourcing.CriteriaResolver;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventstreaming.EventCriteria;

import static java.util.Objects.requireNonNull;

/**
 * Annotation-based {@link CriteriaResolver} implementation which resolves the {@link EventCriteria} used to
 * {@link org.axonframework.eventsourcing.eventstore.EventStoreTransaction#source(org.axonframework.eventsourcing.eventstore.SourcingCondition)
 * source} an {@link EventSourcedEntity}, based on the given {@code id}.
 * <p>
 * In order of precedence:
 * <ol>
 *     <li>A static method annotated {@link SourcingCriteriaBuilder} matching the identifier's type.</li>
 *     <li>A static method annotated {@link EventCriteriaBuilder} matching the identifier's type.</li>
 *     <li>A tag-based fallback using {@link EventSourcedEntity#tagKey()} (or the entity's simple name) as the tag key,
 *     and the identifier's {@link Object#toString()} as the tag value.</li>
 * </ol>
 * This resolver is the default source of sourcing criteria when using the {@link EventSourcedEntity} annotation, but
 * specifying a custom {@link CriteriaResolver} (or a custom {@link CriteriaResolverDefinition}) overrides it.
 *
 * @param <E>  The type of the entity to create.
 * @param <ID> The type of the identifier of the entity to create.
 * @author Mateusz Nowak
 * @see AnnotationBasedAppendCriteriaResolver
 * @see EventSourcedEntity
 * @since 5.3.0
 */
public class AnnotationBasedSourcingCriteriaResolver<E, ID> implements CriteriaResolver<ID>, DescribableComponent {

    private final Class<E> entityType;
    private final Class<ID> idType;
    private final AnnotationBasedCriteriaBuilders<E, ID> builders;

    /**
     * Initializes the resolver for the given {@code entityType}. The entity type should be annotated with
     * {@link EventSourcedEntity}, or this resolver will throw an {@link IllegalArgumentException}.
     *
     * @param entityType    The entity type to resolve sourcing criteria for.
     * @param idType        The identifier type to resolve sourcing criteria for.
     * @param configuration The configuration to use for resolving the criteria.
     */
    public AnnotationBasedSourcingCriteriaResolver(Class<E> entityType, Class<ID> idType, Configuration configuration) {
        this.entityType = requireNonNull(entityType, "The entity type cannot be null.");
        this.idType = requireNonNull(idType, "The id type cannot be null.");
        this.builders = new AnnotationBasedCriteriaBuilders<>(entityType, idType, configuration);
    }

    @Override
    public EventCriteria resolve(ID id, ProcessingContext context) {
        return builders.resolveSourcing(id, context);
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeProperty("idType", idType.getName());
        descriptor.describeProperty("entityType", entityType.getName());
        descriptor.describeProperty("sourcingCriteriaBuilders", builders.sourcingBuilders());
        descriptor.describeProperty("sharedCriteriaBuilders", builders.sharedBuilders());
    }
}
