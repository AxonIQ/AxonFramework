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

import org.axonframework.common.FutureUtils;
import org.axonframework.common.TypeReference;
import org.axonframework.common.configuration.BaseModule;
import org.axonframework.common.configuration.ComponentBuilder;
import org.axonframework.common.configuration.ComponentDefinition;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.LifecycleRegistry;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.common.lifecycle.Phase;
import org.axonframework.conversion.Converter;
import org.axonframework.conversion.GeneralConverter;
import org.axonframework.eventsourcing.CriteriaResolver;
import org.axonframework.eventsourcing.EventSourcedEntityFactory;
import org.axonframework.eventsourcing.EventSourcingRepository;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.eventsourcing.eventstore.EventStoreTransaction;
import org.axonframework.eventsourcing.eventstore.SourcingCondition;
import org.axonframework.eventsourcing.handler.InitializingEntityEvolver;
import org.axonframework.eventsourcing.handler.SimpleSourcingHandler;
import org.axonframework.eventsourcing.handler.SnapshottingSourcingHandler;
import org.axonframework.eventsourcing.handler.SourcingHandler;
import org.axonframework.eventsourcing.snapshot.api.SnapshotPolicy;
import org.axonframework.eventsourcing.snapshot.store.SnapshotStore;
import org.axonframework.eventsourcing.snapshot.store.StoreBackedSnapshotter;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.commandhandling.CommandHandlingComponent;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.modelling.EntityIdResolver;
import org.axonframework.modelling.StateManager;
import org.axonframework.modelling.configuration.EntityMetamodelConfigurationBuilder;
import org.axonframework.modelling.entity.EntityCommandHandlingComponent;
import org.axonframework.modelling.entity.EntityMetamodel;
import org.axonframework.modelling.repository.Repository;
import org.jspecify.annotations.NonNull;

import java.util.concurrent.CompletableFuture;

import static java.util.Objects.requireNonNull;

/**
 * Simple implementation of the {@link EventSourcedEntityModule}.
 *
 * @param <ID> The type of identifier used to identify the event-sourced entity that's being built.
 * @param <E>  The type of the event-sourced entity being built.
 * @author Steven van Beelen
 * @since 5.0.0
 */
class SimpleEventSourcedEntityModule<ID, E> extends BaseModule<SimpleEventSourcedEntityModule<ID, E>>
        implements
        EventSourcedEntityModule<ID, E>,
        EventSourcedEntityModule.MessagingModelPhase<ID, E>,
        EventSourcedEntityModule.EntityFactoryPhase<ID, E>,
        EventSourcedEntityModule.CriteriaResolverPhase<ID, E>,
        EventSourcedEntityModule.OptionalPhase<ID, E> {

    private final Class<ID> idType;
    private final Class<E> entityType;

    private ComponentBuilder<EventSourcedEntityFactory<ID, E>> entityFactory;
    private ComponentBuilder<CriteriaResolver<ID>> sourceCriteriaResolver;
    private ComponentBuilder<CriteriaResolver<ID>> appendCriteriaResolver;
    private boolean isDcb = false;
    private ComponentBuilder<EntityMetamodel<E>> entityModel;
    private ComponentBuilder<EntityIdResolver<ID>> entityIdResolver;
    private ComponentBuilder<SnapshotPolicy> snapshotPolicy;

    SimpleEventSourcedEntityModule(Class<ID> idType,
                                   Class<E> entityType) {
        super("SimpleEventSourcedEntityModule<%s, %s>".formatted(idType.getName(), entityType.getName()));
        this.idType = requireNonNull(idType, "The identifier type cannot be null.");
        this.entityType = requireNonNull(entityType, "The entity type cannot be null.");
    }

    @Override
    public EntityFactoryPhase<ID, E> messagingModel(
            EntityMetamodelConfigurationBuilder<E> metamodelFactory) {
        requireNonNull(metamodelFactory, "The metamodelFactory cannot be null.");
        this.entityModel = c -> metamodelFactory.build(c, EntityMetamodel.forEntityType(entityType));
        return this;
    }

    @Override
    public CriteriaResolverPhase<ID, E> entityFactory(
            ComponentBuilder<EventSourcedEntityFactory<ID, E>> entityFactory
    ) {
        this.entityFactory = requireNonNull(entityFactory, "The entity factory cannot be null.");
        return this;
    }

    @Override
    public OptionalPhase<ID, E> criteriaResolver(ComponentBuilder<CriteriaResolver<ID>> criteriaResolver) {
        requireNonNull(criteriaResolver, "The criteria resolver cannot be null.");
        this.sourceCriteriaResolver = criteriaResolver;
        this.appendCriteriaResolver = criteriaResolver;
        this.isDcb = false;
        return this;
    }

    @Override
    public OptionalPhase<ID, E> criteriaResolvers(ComponentBuilder<CriteriaResolver<ID>> sourceCriteriaResolver,
                                                   ComponentBuilder<CriteriaResolver<ID>> appendCriteriaResolver) {
        this.sourceCriteriaResolver = requireNonNull(sourceCriteriaResolver, "The source criteria resolver cannot be null.");
        this.appendCriteriaResolver = requireNonNull(appendCriteriaResolver, "The append criteria resolver cannot be null.");
        this.isDcb = true;
        return this;
    }

    @Override
    public OptionalPhase<ID, E> entityIdResolver(ComponentBuilder<EntityIdResolver<ID>> entityIdResolver) {
        this.entityIdResolver = requireNonNull(entityIdResolver, "The entity ID resolver cannot be null.");
        return this;
    }

    @Override
    public OptionalPhase<ID, E> snapshotPolicy(ComponentBuilder<SnapshotPolicy> snapshotPolicy) {
        this.snapshotPolicy = requireNonNull(snapshotPolicy, "The snapshotPolicy cannot be null.");
        return this;
    }

    @Override
    public Class<ID> idType() {
        return idType;
    }

    @Override
    public Class<E> entityType() {
        return entityType;
    }

    @Override
    public Configuration build(Configuration parent, LifecycleRegistry lifecycleRegistry) {
        validate();
        registerComponents();
        return super.build(parent, lifecycleRegistry);
    }

    private void validate() {
        requireNonNull(entityFactory, "The EntityFactory must be provided to module [%s].".formatted(name()));
        requireNonNull(sourceCriteriaResolver, "The CriteriaResolver must be provided to module [%s].".formatted(name()));
        requireNonNull(entityModel, "The EntityModel must be provided to module [%s].".formatted(name()));
    }

    private void registerComponents() {
        componentRegistry(cr -> {
            cr.registerComponent(entityFactory());
            cr.registerComponent(sourceCriteriaResolver());
            if (isDcb) {
                cr.registerComponent(appendCriteriaResolver());
            }
            cr.registerComponent(entityModel());
            cr.registerComponent(repository());

            if (entityIdResolver != null) {
                cr.registerComponent(idResolver());
                cr.registerComponent(commandHandlingComponent());
            }

            if (snapshotPolicy != null) {
                cr.registerComponent(snapshotPolicy());
            }
        });
    }

    private ComponentDefinition<EntityMetamodel<E>> entityModel() {
        TypeReference<EntityMetamodel<E>> type = new TypeReference<>() {
        };
        return ComponentDefinition.ofTypeAndName(type, entityName())
                                  .withBuilder(entityModel);
    }

    private ComponentDefinition<EntityIdResolver<ID>> idResolver() {
        TypeReference<EntityIdResolver<ID>> type = new TypeReference<>() {
        };
        return ComponentDefinition.ofTypeAndName(type, entityName())
                                  .withBuilder(entityIdResolver);
    }

    private ComponentDefinition<SnapshotPolicy> snapshotPolicy() {
        return ComponentDefinition.ofTypeAndName(SnapshotPolicy.class, entityName())
                                  .withBuilder(snapshotPolicy);
    }

    private ComponentDefinition<Repository<ID, E>> repository() {
        TypeReference<Repository<ID, E>> type = new TypeReference<>() {};

        return ComponentDefinition
                .ofTypeAndName(type, entityName())
                .withBuilder(config -> {
                    EventStore eventStore = config.getComponent(EventStore.class);
                    SnapshotPolicy resolvedSnapshotPolicy =
                            config.getOptionalComponent(SnapshotPolicy.class, entityName()).orElse(null);
                    SourcingHandler<ID, E> sourcingHandler;

                    if (isDcb) {
                        @SuppressWarnings("unchecked")
                        CriteriaResolver<ID> sourceCR =
                                config.getComponent(CriteriaResolver.class, sourceCriteriaResolverName());
                        @SuppressWarnings("unchecked")
                        CriteriaResolver<ID> appendCR =
                                config.getComponent(CriteriaResolver.class, appendCriteriaResolverName());
                        sourcingHandler = new DcbSourcingHandler<>(eventStore, sourceCR, appendCR);
                    } else {
                        @SuppressWarnings("unchecked")
                        CriteriaResolver<ID> criteriaResolver =
                                config.getComponent(CriteriaResolver.class, entityName());

                        if (resolvedSnapshotPolicy == null) {
                            sourcingHandler = new SimpleSourcingHandler<>(eventStore, criteriaResolver);
                        } else {
                            Converter converter = config.getOptionalComponent(GeneralConverter.class)
                                    .orElseThrow(() -> new IllegalStateException(
                                            "A Converter must be configured to use snapshotting."));
                            SnapshotStore snapshotStore = config.getOptionalComponent(SnapshotStore.class)
                                    .orElseThrow(() -> new IllegalStateException(
                                            "A SnapshotStore must be configured to use snapshotting."));
                            MessageType messageType = config.getOptionalComponent(MessageTypeResolver.class)
                                    .flatMap(mtr -> mtr.resolve(entityType))
                                    .orElseThrow(() -> new IllegalStateException(
                                            "A MessageTypeResolver capable of resolving " + entityType + " must be configured to use snapshotting."));

                            sourcingHandler = new SnapshottingSourcingHandler<>(
                                    eventStore,
                                    criteriaResolver,
                                    messageType,
                                    resolvedSnapshotPolicy,
                                    new StoreBackedSnapshotter<>(
                                            snapshotStore,
                                            messageType,
                                            converter,
                                            entityType
                                    )
                            );
                        }
                    }

                    @SuppressWarnings("unchecked")
                    var repository = new EventSourcingRepository<ID, E>(
                            idType,
                            entityType,
                            eventStore,
                            config.getComponent(EventSourcedEntityFactory.class, entityName()),
                            config.getComponent(EntityMetamodel.class, entityName()),
                            sourcingHandler
                    );

                    return repository;
                })
                .onStart(Phase.LOCAL_MESSAGE_HANDLER_REGISTRATIONS,
                         (config, component) -> {
                             config.getComponent(StateManager.class).register(component);
                             return FutureUtils.emptyCompletedFuture();
                         }
                );
    }

    private ComponentDefinition<CommandHandlingComponent> commandHandlingComponent() {
        //noinspection unchecked
        return ComponentDefinition
                .ofTypeAndName(CommandHandlingComponent.class, entityName())
                .withBuilder(c -> new EntityCommandHandlingComponent<ID, E>(
                        c.getComponent(Repository.class, entityName()),
                        c.getComponent(EntityMetamodel.class, entityName()),
                        c.getComponent(EntityIdResolver.class, entityName())
                ))
                .onStart(Phase.LOCAL_MESSAGE_HANDLER_REGISTRATIONS,
                         (config, component) -> {
                             config.getComponent(CommandBus.class).subscribe(component);
                             return FutureUtils.emptyCompletedFuture();
                         }
                );
    }

    private ComponentDefinition<EventSourcedEntityFactory<ID, E>> entityFactory() {
        TypeReference<EventSourcedEntityFactory<ID, E>> type = new TypeReference<>() {
        };

        return ComponentDefinition
                .ofTypeAndName(type, entityName())
                .withBuilder(entityFactory);
    }

    private String sourceCriteriaResolverName() {
        return entityName() + "#source";
    }

    private String appendCriteriaResolverName() {
        return entityName() + "#append";
    }

    private ComponentDefinition<CriteriaResolver<ID>> sourceCriteriaResolver() {
        TypeReference<CriteriaResolver<ID>> type = new TypeReference<>() {
        };
        String name = isDcb ? sourceCriteriaResolverName() : entityName();
        return ComponentDefinition
                .ofTypeAndName(type, name)
                .withBuilder(sourceCriteriaResolver);
    }

    private ComponentDefinition<CriteriaResolver<ID>> appendCriteriaResolver() {
        TypeReference<CriteriaResolver<ID>> type = new TypeReference<>() {
        };
        return ComponentDefinition
                .ofTypeAndName(type, appendCriteriaResolverName())
                .withBuilder(appendCriteriaResolver);
    }

    @Override
    public EventSourcedEntityModule<ID, E> build() {
        return this;
    }

    /**
     * A {@link SourcingHandler} that supports Dynamic Consistency Boundaries (DCB) by using separate
     * {@link CriteriaResolver}s for sourcing events and for consistency checking when appending events.
     */
    private static class DcbSourcingHandler<I, E> implements SourcingHandler<I, E> {

        private final EventStore eventStore;
        private final CriteriaResolver<I> sourceCriteriaResolver;
        private final CriteriaResolver<I> appendCriteriaResolver;

        DcbSourcingHandler(EventStore eventStore,
                           CriteriaResolver<I> sourceCriteriaResolver,
                           CriteriaResolver<I> appendCriteriaResolver) {
            this.eventStore = eventStore;
            this.sourceCriteriaResolver = sourceCriteriaResolver;
            this.appendCriteriaResolver = appendCriteriaResolver;
        }

        @Override
        public CompletableFuture<E> source(@NonNull I identifier,
                                           @NonNull InitializingEntityEvolver<I, E> evolver,
                                           @NonNull ProcessingContext pc) {
            EventCriteria sourceCriteria = sourceCriteriaResolver.resolve(identifier, pc);
            EventCriteria appendCriteria = appendCriteriaResolver.resolve(identifier, pc);
            EventStoreTransaction transaction = eventStore.transaction(pc).overrideAppendCondition(appendCriteria);
            return transaction.source(SourcingCondition.conditionFor(sourceCriteria))
                              .reduce((E) null, (entity, entry) -> evolver.evolve(identifier, entity, entry.message(), pc));
        }

        @Override
        public void describeTo(ComponentDescriptor descriptor) {
            descriptor.describeProperty("sourceCriteriaResolver", sourceCriteriaResolver);
            descriptor.describeProperty("appendCriteriaResolver", appendCriteriaResolver);
        }
    }
}
