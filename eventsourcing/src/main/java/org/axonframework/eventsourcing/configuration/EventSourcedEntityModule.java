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

package org.axonframework.eventsourcing.configuration;

import org.axonframework.common.configuration.ComponentBuilder;
import org.axonframework.common.configuration.ModuleBuilder;
import org.axonframework.eventsourcing.CriteriaResolver;
import org.axonframework.eventsourcing.EventSourcedEntityFactory;
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.eventstore.SourcingCondition;
import org.axonframework.eventsourcing.snapshot.api.SnapshotPolicy;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.modelling.EntityIdResolver;
import org.axonframework.modelling.StateManager;
import org.axonframework.modelling.configuration.EntityMetamodelConfigurationBuilder;
import org.axonframework.modelling.configuration.EntityModule;
import org.axonframework.modelling.entity.EntityCommandHandlingComponent;
import org.axonframework.modelling.entity.EntityMetamodel;
import org.axonframework.modelling.repository.Repository;

/**
 * An expansion of the {@link EntityModule}, specifically for event-sourced entities. When constructed, either
 * {@link #autodetected(Class, Class) autodetected} or {@link #declarative(Class, Class) declarative}, it provides the
 * resulting {@link Repository} with the nearest {@link StateManager} so the state can be loaded.
 *
 * <h2>Command Handling</h2>
 * An {@link EntityCommandHandlingComponent} will be registered to the {@link CommandBus} that subscribes to the
 * commands of the {@link EntityMetamodel} that describes this entity. This is only done if an {@link EntityIdResolver}
 * is provided. If no {@link EntityIdResolver} is provided, no command handling component will be registered, but the
 * entity will still be registered to the {@link StateManager} so it can be loaded in stateful command handlers.
 *
 * <h2>Annotation-based entities</h2>
 * Entities annotated with {@link EventSourcedEntity} can be built using {@link #autodetected(Class, Class)}. This will
 * automatically build all required components based on the {@link EventSourcedEntity} annotation present on the entity
 * type.
 *
 * <h2>Declarative building</h2>
 * Entities can also be built using the {@link #declarative(Class, Class)} method. This allows for a more manual
 * approach to building the event-sourced entity, where the user can provide the required components.
 * <p>
 * There are several phases of the building process of the declarative event-sourced entity module:
 * <ul>
 *     <li>{@link MessagingModelPhase} – configure the {@link EntityMetamodel} used to evolve and handle commands.</li>
 *     <li>{@link EntityFactoryPhase} – provide the {@link EventSourcedEntityFactory} to create entities.</li>
 *     <li>{@link CriteriaResolverPhase} – provide the {@link CriteriaResolver} used to source events from the
 *         {@link org.axonframework.eventsourcing.eventstore.EventStore}.</li>
 *     <li>{@link OptionalPhase} – configure optional parameters, such as {@link SnapshotPolicy} and
 *         {@link EntityIdResolver}.</li>
 * </ul>
 *
 * <h2>Module hierarchy</h2>
 * This module does not provide a {@link StateManager} by itself, but rather registers the entity to the nearest
 * {@link StateManager} in the module hierarchy. This means that you can load the event-sourced entity from the
 * nearest parent configuration that provides a {@link StateManager}, or any of that parent's children modules.
 * As such, to ensure access, this module should be registered at the right place in the module hierarchy.
 *
 * @param <ID> The type of identifier used to identify the event-sourced entity.
 * @param <E>  The type of the event-sourced entity.
 * @author Steven van Beelen
 * @author Mitchell Herrijgers
 * @author John Hendrikx
 * @since 5.0.0
 */
public interface EventSourcedEntityModule<ID, E> extends EntityModule<ID, E> {

    /**
     * Starts building an event-sourced entity with the given {@code entityType} and {@code idType}.
     *
     * @param idType     The type of identifier used to identify the event-sourced entity.
     * @param entityType The type of the event-sourced entity being built.
     * @param <ID>       The type of identifier used to identify the event-sourced entity.
     * @param <E>        The type of the event-sourced entity being built.
     * @return The {@link MessagingModelPhase} phase of this builder, for a fluent API.
     */
    static <ID, E> MessagingModelPhase<ID, E> declarative(Class<ID> idType, Class<E> entityType) {
        return new SimpleEventSourcedEntityModule<>(idType, entityType);
    }

    /**
     * Creates the module for an annotated event-sourced entity with the given {@code entityType} and {@code idType}.
     * The given {@code entityType} is expected to be annotated with {@link EventSourcedEntity}, which provides the
     * module with the necessary information to build the event-sourced entity.
     *
     * @param idType     The type of identifier used to identify the annotated event-sourced entity.
     * @param entityType The type of the annotated event-sourced entity being built.
     * @param <ID>       The type of identifier used to identify the event-sourced entity.
     * @param <E>        The type of the event-sourced entity being built.
     * @return The finished module.
     * @throws IllegalArgumentException When the given {@code entityType} is not annotated with
     *                                  {@link EventSourcedEntity}.
     */
    static <ID, E> EventSourcedEntityModule<ID, E> autodetected(Class<ID> idType, Class<E> entityType) {
        return new AnnotatedEventSourcedEntityModule<>(idType, entityType);
    }

    /**
     * Phase of the module's building process in which the user should define the messaging metamodel for the
     * event-sourced entity being built. This metamodel is used to evolve the entity and handle commands for the entity
     * (if applicable).
     *
     * @param <ID> The type of identifier used to identify the event-sourced entity.
     * @param <E>  The type of the event-sourced entity being built.
     */
    interface MessagingModelPhase<ID, E> {

        /**
         * Registers the given {@link EntityMetamodelConfigurationBuilder} of an {@link EntityMetamodel} as the
         * messaging metamodel for the event-sourced entity being built. This metamodel is used to evolve the entity and
         * handle commands for the entity (if applicable).
         *
         * @param metamodelFactory A {@link EntityMetamodelConfigurationBuilder} constructing the
         *                         {@link EntityMetamodel} for the event-sourced entity.
         * @return The {@link EntityFactoryPhase} phase of this builder, for a fluent API.
         */
        EntityFactoryPhase<ID, E> messagingModel(EntityMetamodelConfigurationBuilder<E> metamodelFactory);
    }

    /**
     * Phase of the module's building process in which a {@link ComponentBuilder} for an
     * {@link EventSourcedEntityFactory} should be provided. This factory is responsible for creating the event-sourced
     * entity of type {@code E} based on the entity's type, an identifier of type {@code I}, and optionally an
     * {@link EventMessage} if the stream is non-empty.
     *
     * @param <ID> The type of identifier used to identify the event-sourced entity.
     * @param <E>  The type of the event-sourced entity being built.
     */
    interface EntityFactoryPhase<ID, E> {

        /**
         * Registers the given {@link ComponentBuilder} of an {@link EventSourcedEntityFactory} as the factory for the
         * event-sourced entity being built. This factory is responsible for creating the event-sourced entity of type
         * {@code E} based on the entity's type, an identifier of type {@code I}, and optionally an
         * {@link EventMessage} if the stream is non-empty.
         *
         * @param entityFactory A {@link ComponentBuilder} constructing the {@link EventSourcedEntityFactory} for the
         *                      event-sourced entity.
         * @return The {@link CriteriaResolver} phase of this builder, for a fluent API.
         */
        CriteriaResolverPhase<ID, E> entityFactory(
                ComponentBuilder<EventSourcedEntityFactory<ID, E>> entityFactory
        );
    }

    /**
     * Phase of the module's building process in which a {@link ComponentBuilder} for a {@link CriteriaResolver} should
     * be provided. A {@code CriteriaResolver} receives the entity's identifier of type {@code I} and expects the
     * {@link EventCriteria} as a result. The resulting {@code EventCriteria} is used to
     * {@link org.axonframework.eventsourcing.eventstore.EventStoreTransaction#source(SourcingCondition) source} the
     * entity from the {@link org.axonframework.eventsourcing.eventstore.EventStore}.
     *
     * @param <ID> The type of identifier used to identify the event-sourced entity.
     * @param <E>  The type of the event-sourced entity being built.
     */
    interface CriteriaResolverPhase<ID, E> {

        /**
         * Registers the given {@link ComponentBuilder} of a {@link CriteriaResolver} as the criteria resolver for the
         * event-sourced entity being built. This resolver will be used for both sourcing (loading events) and
         * appending (consistency checking).
         * <p>
         * A {@code CriteriaResolver} receives the entity's identifier of type {@code I} and expects the
         * {@link EventCriteria} as a result. The resulting {@code EventCriteria} is used to
         * {@link org.axonframework.eventsourcing.eventstore.EventStoreTransaction#source(SourcingCondition) source} the
         * entity from the {@link org.axonframework.eventsourcing.eventstore.EventStore}.
         *
         * @param criteriaResolver A {@link ComponentBuilder} constructing the {@link CriteriaResolver} for the
         *                         event-sourced entity, used for both sourcing and appending.
         * @return The {@link OptionalPhase} phase of this builder, for a fluent API.
         */
        OptionalPhase<ID, E> criteriaResolver(ComponentBuilder<CriteriaResolver<ID>> criteriaResolver);

        /**
         * Registers only an append {@link CriteriaResolver}, without any sourcing criteria. This means no events
         * will be loaded from the store — only consistency checking is performed when appending.
         * <p>
         * This is used when only an {@code @AppendCriteriaBuilder} is defined, without an {@code @EventCriteriaBuilder}
         * or {@code @SourceCriteriaBuilder}. The {@link org.axonframework.eventsourcing.eventstore.ConsistencyMarker}
         * is resolved from the
         * {@link org.axonframework.eventsourcing.eventstore.EventStorageEngine#latestToken(
         * org.axonframework.messaging.core.unitofwork.ProcessingContext) latest token} in the store.
         *
         * @param appendCriteriaResolver A {@link ComponentBuilder} constructing the {@link CriteriaResolver} for
         *                               consistency checking when appending events.
         * @return The {@link EntityIdResolverPhase} phase of this builder, for a fluent API.
         */
        EntityIdResolverPhase<ID, E> appendCriteriaResolver(
                @Nonnull ComponentBuilder<CriteriaResolver<ID>> appendCriteriaResolver
        );

        /**
         * Registers separate {@link ComponentBuilder}s for sourcing and appending {@link CriteriaResolver}s.
         * This enables Dynamic Consistency Boundaries (DCB) where the criteria used for sourcing events
         * (loading state) can differ from the criteria used for consistency checking (appending events).
         * <p>
         * <b>Example - Accounting Use Case:</b>
         * <ul>
         *   <li><b>sourceCriteriaResolver</b>: Resolves to criteria that loads both CreditsIncreased AND CreditsDecreased
         *       events to calculate the balance.</li>
         *   <li><b>appendCriteriaResolver</b>: Resolves to criteria that only checks for conflicts on CreditsDecreased
         *       events, allowing concurrent credit increases.</li>
         * </ul>
         * <pre>{@code
         * EventSourcedEntityModule
         *     .declarative(String.class, Account.class)
         *     .messagingModel(...)
         *     .entityFactory(...)
         *     .criteriaResolvers(
         *         // Source resolver - load all credit events
         *         c -> (id, ctx) -> EventCriteria
         *             .havingTags("accountId", id)
         *             .andBeingOneOfTypes("CreditsIncreased", "CreditsDecreased"),
         *         // Append resolver - only check decreases
         *         c -> (id, ctx) -> EventCriteria
         *             .havingTags("accountId", id)
         *             .andBeingOneOfTypes("CreditsDecreased")
         *     )
         *     .entityIdResolver(...)
         *     .build();
         * }</pre>
         *
         * @param sourceCriteriaResolver A {@link ComponentBuilder} constructing the {@link CriteriaResolver} for
         *                               sourcing (loading) the event-sourced entity.
         * @param appendCriteriaResolver A {@link ComponentBuilder} constructing the {@link CriteriaResolver} for
         *                               consistency checking when appending events.
         * @return The {@link OptionalPhase} phase of this builder, for a fluent API.
         */
        OptionalPhase<ID, E> criteriaResolvers(
                ComponentBuilder<CriteriaResolver<ID>> sourceCriteriaResolver,
                ComponentBuilder<CriteriaResolver<ID>> appendCriteriaResolver
        );
    }

    /**
     * Phase of the module's building process in which optional parameters can be provided.
     *
     * @param <ID> The type of identifier used to identify the event-sourced entity.
     * @param <E>  The type of the event-sourced entity being built.
     */
    interface OptionalPhase<ID, E> extends ModuleBuilder<EventSourcedEntityModule<ID, E>> {

        /**
         * Registers an optional {@link ComponentBuilder} of an {@link EntityIdResolver} as the resolver for the
         * event-sourced entity being built. This resolver is responsible for resolving the identifier of the
         * event-sourced entity being built.
         * <p>
         * If no {@link EntityIdResolver} is provided, no command handling component will be registered, but the entity
         * will still be registered to the {@link StateManager} so it can be loaded in stateful command handlers.
         *
         * @param entityIdResolver A {@link ComponentBuilder} constructing the {@link EntityIdResolver} for the
         *                         event-sourced entity.
         * @return The {@link OptionalPhase} phase of this builder, for a fluent API.
         */
        OptionalPhase<ID, E> entityIdResolver(ComponentBuilder<EntityIdResolver<ID>> entityIdResolver);

        /**
         * Registers an optional {@link ComponentBuilder} of a {@link SnapshotPolicy} for the
         * event-sourced entity being built. The snapshot policy determines under which conditions
         * the entity state should be snapshotted during sourcing.
         *
         * @param snapshotPolicy A {@link ComponentBuilder} constructing the {@link SnapshotPolicy}
         *                       for the event-sourced entity.
         * @return The {@link OptionalPhase} phase of this builder, for a fluent API.
         */
        OptionalPhase<ID, E> snapshotPolicy(ComponentBuilder<SnapshotPolicy> snapshotPolicy);
    }
}
