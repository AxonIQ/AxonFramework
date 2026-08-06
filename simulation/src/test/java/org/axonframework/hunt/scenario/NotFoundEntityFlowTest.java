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

package org.axonframework.hunt.scenario;

import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.eventsourcing.EventSourcedEntityFactory;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.axonframework.modelling.StateManager;
import org.axonframework.modelling.annotation.InjectEntity;
import org.axonframework.modelling.annotation.TargetEntityId;
import org.axonframework.modelling.repository.EntityNotFoundException;
import org.axonframework.modelling.repository.ManagedEntity;
import org.axonframework.modelling.repository.Repository;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * How the not-found flow of event-sourced entities behaves at its three public surfaces: an
 * {@link InjectEntity @InjectEntity} command-handler parameter, {@link Repository#load} followed by
 * {@link Repository#loadOrCreate} inside one unit of work, and the live evolution of a not-found entity that receives
 * its first event within the same unit of work.
 * <p>
 * The load path deliberately tolerates a missing entity: the repository catches the
 * {@link EntityNotFoundException}, still caches and subscribes a {@link ManagedEntity} holding {@code null}, and only
 * rethrows on the {@code loadOrCreate} path when the entity is still {@code null}. The cases below probe the seams of
 * that design.
 * <p>
 * One case is an <b>expected-gap</b> test: {@link LoadThenLoadOrCreateInOneUnitOfWork} pins that a
 * {@code loadOrCreate} for an identifier already loaded (and found missing) in the same unit of work throws
 * {@link EntityNotFoundException} even when the entity factory can construct the entity from its identifier alone --
 * the cached not-found entry is consulted instead of the factory, so the {@code loadOrCreate} contract ("or a newly
 * constructed entity instance based on the factoryMethod") is not honoured. The control in the same nested class shows
 * a bare {@code loadOrCreate} creating the entity. The gap case passes while the defect exists and turns red when
 * {@code loadOrCreate} starts consulting the factory for a cached null entity.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
class NotFoundEntityFlowTest {

    private static final String CONNECTOR_ENHANCER =
            "io.axoniq.framework.axonserver.connector.configuration.AxonServerConfigurationEnhancer";

    private AxonConfiguration configuration;

    @BeforeEach
    void setUp() {
        var ledgerEntity = EventSourcedEntityModule
                .declarative(String.class, Ledger.class)
                .messagingModel((c, b) -> b
                        .entityEvolver((ledger, event, context) -> {
                            // The criteria already narrow the stream to this entity's single event type. Note that
                            // QualifiedName renders a nested class as package.SimpleName, not Class#getName.
                            ledger.apply(event.payloadAs(LedgerEntryAdded.class));
                            return ledger;
                        })
                        .build())
                // Needs a first event: null for a null event, so a missing entity raises EntityNotFoundException.
                .entityFactory(c -> EventSourcedEntityFactory.fromEventMessage((id, event) -> new Ledger(id)))
                .criteriaResolver(c -> (id, ctx) -> EventCriteria.havingTags(new Tag("Ledger", id)))
                .build();

        var counterEntity = EventSourcedEntityModule
                .declarative(String.class, Counter.class)
                .messagingModel((c, b) -> b
                        .entityEvolver((counter, event, context) -> counter)
                        .build())
                // Constructible from the identifier alone: loadOrCreate on a missing id must create, never throw.
                .entityFactory(c -> EventSourcedEntityFactory.fromIdentifier(Counter::new))
                .criteriaResolver(c -> (id, ctx) -> EventCriteria.havingTags(new Tag("Counter", id)))
                .build();

        CommandHandlingModule commandModule = CommandHandlingModule
                .named("not-found-probe")
                .commandHandlers()
                .autodetectedCommandHandlingComponent(c -> handlers)
                .build();

        configuration = EventSourcingConfigurer.create()
                                               // The published connector sits on this module's test classpath; its
                                               // enhancer would otherwise wire a live Axon Server connection.
                                               .componentRegistry(cr -> cr.disableEnhancer(CONNECTOR_ENHANCER))
                                               .componentRegistry(cr -> cr.registerModule(ledgerEntity))
                                               .componentRegistry(cr -> cr.registerModule(counterEntity))
                                               .registerCommandHandlingModule(commandModule)
                                               .start();
    }

    @AfterEach
    void tearDown() {
        if (configuration != null) {
            configuration.shutdown();
        }
    }

    private final MissingLedgerHandlers handlers = new MissingLedgerHandlers();

    private static String freshId() {
        return UUID.randomUUID().toString();
    }

    @Nested
    class MissingEntityInjectionAtCommandDispatch {

        @Test
        void nullableParameterResolvesToNullAndTheCommandSucceeds() {
            // given a command for an identifier with no events
            String id = freshId();

            // when the command with a @Nullable @InjectEntity parameter is dispatched
            configuration.getComponent(CommandGateway.class)
                         .send(new NullableEntityCommand(id))
                         .getResultMessage()
                         .orTimeout(10, TimeUnit.SECONDS)
                         .join();

            // then the handler observed null and nothing failed
            assertThat(handlers.observed.get()).isEqualTo("null-injected");
        }

        @Test
        void optionalParameterResolvesToEmptyAndTheCommandSucceeds() {
            // given a command for an identifier with no events
            String id = freshId();

            // when the command with an Optional @InjectEntity parameter is dispatched
            configuration.getComponent(CommandGateway.class)
                         .send(new OptionalEntityCommand(id))
                         .getResultMessage()
                         .orTimeout(10, TimeUnit.SECONDS)
                         .join();

            // then the handler observed an empty Optional
            assertThat(handlers.observed.get()).isEqualTo("empty-injected");
        }

        @Test
        void requiredParameterSurfacesEntityNotFoundInTheCommandResult() {
            // given a command for an identifier with no events
            String id = freshId();

            // when the command with a required @InjectEntity parameter is dispatched
            Throwable failure = catchThrowable(
                    () -> configuration.getComponent(CommandGateway.class)
                                       .send(new RequiredEntityCommand(id))
                                       .getResultMessage()
                                       .orTimeout(10, TimeUnit.SECONDS)
                                       .join());

            // then the failure chain carries the original EntityNotFoundException
            assertThat(failure).isNotNull();
            Throwable cursor = failure;
            boolean found = false;
            StringBuilder chain = new StringBuilder();
            while (cursor != null) {
                chain.append(cursor.getClass().getName()).append(" -> ");
                if (cursor instanceof EntityNotFoundException) {
                    found = true;
                }
                cursor = cursor.getCause();
            }
            System.out.println("NOT-FOUND COMMAND RESULT chain=" + chain + "end");
            assertThat(found)
                    .as("the command result must surface EntityNotFoundException, chain was: " + chain)
                    .isTrue();
            // and the handler was never invoked
            assertThat(handlers.observed.get()).isNull();
        }
    }

    @Nested
    class LoadThenLoadOrCreateInOneUnitOfWork {

        @Test
        void bareLoadOrCreateCreatesTheEntityFromItsIdentifier() {
            // given an identifier with no events, for an entity constructible from its identifier
            String id = freshId();
            UnitOfWork uow = configuration.getComponent(UnitOfWorkFactory.class).create();

            // when loadOrCreate is the first touch of the identifier in the unit of work
            Counter created = uow.executeWithResult(
                    ctx -> repository().loadOrCreate(id, ctx).thenApply(ManagedEntity::entity)
            ).orTimeout(10, TimeUnit.SECONDS).join();

            // then the factory constructed the entity
            assertThat(created).isNotNull();
            assertThat(created.id).isEqualTo(id);
        }

        /**
         * The expected-gap case. A prior {@code load} of the same identifier in the same unit of work caches a
         * {@link ManagedEntity} holding {@code null}; the subsequent {@code loadOrCreate} consults that cache, never
         * reaches the entity factory, and throws {@link EntityNotFoundException} -- for an entity whose factory the
         * control above just proved can create it. Passes while the defect exists; turns red when it is fixed.
         */
        @Test
        void loadOrCreateAfterANotFoundLoadThrowsInsteadOfCreating() {
            // given the same identifier loaded (and found missing) earlier in the same unit of work
            String id = freshId();
            UnitOfWork uow = configuration.getComponent(UnitOfWorkFactory.class).create();

            Object outcome = uow.executeWithResult(ctx -> {
                Repository<String, Counter> repo = repository();
                return repo.load(id, ctx).thenCompose(loaded -> {
                    // the plain load reports the entity as missing, without throwing
                    assertThat(loaded.entity()).isNull();
                    // when loadOrCreate follows in the same unit of work
                    return repo.loadOrCreate(id, ctx)
                               .<Object>thenApply(ManagedEntity::entity)
                               .exceptionally(e -> e instanceof java.util.concurrent.CompletionException ce
                                       ? ce.getCause() : e);
                });
            }).orTimeout(10, TimeUnit.SECONDS).join();

            System.out.println("LOAD-THEN-LOADORCREATE outcome=" + outcome);
            // then the cached not-found entry wins over the factory
            assertThat(outcome)
                    .as("loadOrCreate after a not-found load consults the poisoned cache instead of the factory")
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        void loadAfterLoadOrCreateReturnsTheCreatedEntity() {
            // given loadOrCreate created the entity earlier in the same unit of work
            String id = freshId();
            UnitOfWork uow = configuration.getComponent(UnitOfWorkFactory.class).create();

            Counter viaLoad = uow.executeWithResult(ctx -> {
                Repository<String, Counter> repo = repository();
                return repo.loadOrCreate(id, ctx)
                           .thenCompose(created -> repo.load(id, ctx))
                           .thenApply(ManagedEntity::entity);
            }).orTimeout(10, TimeUnit.SECONDS).join();

            // then the inverse order is consistent: load observes the created entity
            assertThat(viaLoad).isNotNull();
            assertThat(viaLoad.id).isEqualTo(id);
        }

        private Repository<String, Counter> repository() {
            return configuration.getComponent(StateManager.class).repository(Counter.class, String.class);
        }
    }

    @Nested
    class NullEntityEvolutionAfterLiveAppend {

        @Test
        void notFoundEntityEvolvesFromItsFirstAppendedEventAndAFreshReloadAgrees() {
            // given a not-found load whose ManagedEntity (holding null) stays subscribed for live updates
            String id = freshId();
            UnitOfWork uow = configuration.getComponent(UnitOfWorkFactory.class).create();

            AtomicReference<Integer> inContextTotal = new AtomicReference<>();
            uow.executeWithResult(ctx -> {
                Repository<String, Ledger> repo =
                        configuration.getComponent(StateManager.class).repository(Ledger.class, String.class);
                return repo.load(id, ctx).thenApply(managed -> {
                    assertThat(managed.entity()).isNull();
                    // when the entity's first event is appended within the same unit of work
                    configuration.getComponent(EventStore.class)
                                 .transaction(ctx)
                                 .appendEvent(new GenericEventMessage(
                                         new MessageType(LedgerEntryAdded.class),
                                         new LedgerEntryAdded(id, 7)));
                    Ledger evolved = managed.entity();
                    inContextTotal.set(evolved == null ? null : evolved.total);
                    return null;
                });
            }).orTimeout(10, TimeUnit.SECONDS).join();

            // then the cached ManagedEntity evolved from null via the factory, in the same context
            System.out.println("LIVE EVOLUTION in-context total=" + inContextTotal.get());
            assertThat(inContextTotal.get())
                    .as("the subscribed null-holding ManagedEntity evolves when its first event is appended")
                    .isEqualTo(7);

            // and a fresh reload after the commit agrees with the in-context state
            UnitOfWork verify = configuration.getComponent(UnitOfWorkFactory.class).create();
            Ledger reloaded = verify.executeWithResult(
                    ctx -> configuration.getComponent(StateManager.class)
                                        .repository(Ledger.class, String.class)
                                        .load(id, ctx)
                                        .thenApply(ManagedEntity::entity)
            ).orTimeout(10, TimeUnit.SECONDS).join();
            System.out.println("LIVE EVOLUTION reloaded total=" + (reloaded == null ? "null" : reloaded.total));
            assertThat(reloaded).isNotNull();
            assertThat(reloaded.total).isEqualTo(7);
        }
    }

    // -- fixture types ------------------------------------------------------------------------------------------

    static final class Ledger {

        private final String id;
        private int total;

        Ledger(String id) {
            this.id = id;
        }

        void apply(LedgerEntryAdded event) {
            total += event.amount();
        }
    }

    static final class Counter {

        private final String id;

        Counter(String id) {
            this.id = id;
        }
    }

    record LedgerEntryAdded(@EventTag(key = "Ledger") String id, int amount) {
    }

    record RequiredEntityCommand(@TargetEntityId String id) {
    }

    record NullableEntityCommand(@TargetEntityId String id) {
    }

    record OptionalEntityCommand(@TargetEntityId String id) {
    }

    static class MissingLedgerHandlers {

        final AtomicReference<Object> observed = new AtomicReference<>();

        @CommandHandler
        void handle(NullableEntityCommand command, @InjectEntity @Nullable Ledger ledger) {
            observed.set(ledger == null ? "null-injected" : ledger);
        }

        @CommandHandler
        void handle(OptionalEntityCommand command, @InjectEntity Optional<Ledger> ledger) {
            observed.set(ledger.isEmpty() ? "empty-injected" : ledger.get());
        }

        @CommandHandler
        void handle(RequiredEntityCommand command, @InjectEntity Ledger ledger) {
            observed.set(ledger);
        }
    }
}
