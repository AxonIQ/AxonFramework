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

package org.axonframework.eventsourcing;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.test.appender.ListAppender;
import org.apache.logging.log4j.core.test.junit.LoggerContextSource;
import org.apache.logging.log4j.core.test.junit.Named;
import org.apache.logging.log4j.message.Message;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.conversion.Converter;
import org.axonframework.conversion.jackson.JacksonConverter;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.eventsourcing.eventstore.GlobalIndexPosition;
import org.axonframework.eventsourcing.handler.SnapshottingEntityLifecycleHandler;
import org.axonframework.eventsourcing.snapshot.api.Snapshot;
import org.axonframework.eventsourcing.snapshot.api.SnapshotPolicy;
import org.axonframework.eventsourcing.snapshot.store.SnapshotStore;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.axonframework.modelling.EntityEvolver;
import org.axonframework.modelling.StateManager;
import org.axonframework.modelling.repository.Repository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test suite for the {@link SnapshottingEntityLifecycleHandler}.
 *
 * @author John Hendrikx
 */
@LoggerContextSource("log4j2-list-appender.xml")
public abstract class SnapshottingEntityLifecycleHandlerTestSuite {
    private static volatile int evolveCalls;

    protected final String ACCOUNT_ID = UUID.randomUUID().toString();

    private StateManager stateManager;
    private EventStore eventStore;
    private UnitOfWorkFactory unitOfWorkFactory;
    private SnapshotStore snapshotStore;

    /**
     * Expects registration of a {@link SnapshotStore} under test and allows additional
     * registration they may be needed in the {@link AxonConfiguration} used by this test
     * suite.
     *
     * @param registry the {@link ComponentRegistry}, cannot be {@code null}
     */
    protected abstract void registerComponents(ComponentRegistry registry);

    @BeforeEach
    void beforeEach() {
        ObjectMapper objectMapper = JsonMapper.builder()
            .enable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
            .build();

        DelegatingEventConverter eventConverter = new DelegatingEventConverter(new JacksonConverter(objectMapper));
        SnapshotPolicy snapshotPolicy = SnapshotPolicy.afterEvents(5)
            .or(SnapshotPolicy.whenEventMatches(msg -> msg.type().qualifiedName().equals(new QualifiedName(AccountClosed.class))));

        AxonConfiguration configuration = EventSourcingConfigurer.create()
            .componentRegistry(cr -> cr.registerComponent(Converter.class, c -> eventConverter))
            .componentRegistry(this::registerComponents)
            .registerEntity(
                EventSourcedEntityModule.declarative(String.class, Account.class)
                    .messagingModel((c, model) -> model.entityEvolver(new SealedEntityEvolver<>(AccountEvents.class, Account::onEvent)).build())
                    .entityFactory(c -> (id, msg, ctx) -> {
                        AccountCreated ac = msg.payloadAs(AccountCreated.class, eventConverter);

                        return new Account(ac.id, ac.name, 0);
                    })
                    .criteriaResolver(c -> (id, ctx) -> EventCriteria.havingTags(Tag.of("account", id)))
                    .snapshotPolicy(c -> snapshotPolicy)
                    .build()
            )
            .build();

        configuration.start();

        this.snapshotStore = configuration.getComponent(SnapshotStore.class);
        this.stateManager = configuration.getComponent(StateManager.class);
        this.eventStore = configuration.getComponent(EventStore.class);
        this.unitOfWorkFactory = configuration.getComponent(UnitOfWorkFactory.class);
    }

    @Test
    protected void shouldSnapshotAfterFiveEvents() {
        publish(
            new AccountCreated(ACCOUNT_ID, "My Account"),
            new FundsDeposited(ACCOUNT_ID, 10050)
        );

        {
            evolveCalls = 0;

            Account account = loadAccount(ACCOUNT_ID);

            assertThat(evolveCalls).isEqualTo(2);
            assertThat(account.balance).isEqualTo(10050);
        }

        publish(
            new FundsDeposited(ACCOUNT_ID, 1000),
            new FundsWithdrawn(ACCOUNT_ID, 1),
            new FundsDeposited(ACCOUNT_ID, 2000),
            new FundsWithdrawn(ACCOUNT_ID, 2),
            new FundsWithdrawn(ACCOUNT_ID, 3),
            new FundsDeposited(ACCOUNT_ID, 3000)
        );

        {   // Expect a full evolution of the entity at first:
            evolveCalls = 0;

            Account account = loadAccount(ACCOUNT_ID);

            assertThat(evolveCalls).isEqualTo(8);
            assertThat(account.balance).isEqualTo(16044);
        }

        {
            evolveCalls = 0;

            Account account = loadAccount(ACCOUNT_ID);

            assertThat(evolveCalls).isZero();  // No evolution needed as a snapshot should have been created
            assertThat(account.balance).isEqualTo(16044);
        }

        publish(
            new FundsDeposited(ACCOUNT_ID, 1)
        );

        {
            evolveCalls = 0;

            Account account = loadAccount(ACCOUNT_ID);

            assertThat(evolveCalls).isEqualTo(1);
            assertThat(account.balance).isEqualTo(16045);
        }
    }

    @Test
    protected void shouldSnapshotExplicitly() {
        publish(
            new AccountCreated(ACCOUNT_ID, "My Account"),
            new FundsDeposited(ACCOUNT_ID, 10050)
        );

        {
            evolveCalls = 0;

            Account account = loadAccount(ACCOUNT_ID);

            assertThat(evolveCalls).isEqualTo(2);
            assertThat(account.balance).isEqualTo(10050);
        }

        publish(
            new FundsDeposited(ACCOUNT_ID, 1000),
            new AccountClosed(ACCOUNT_ID, "been bad")  // triggers immediate snapshot
        );

        {   // Expect a full evolution of the entity at first:
            evolveCalls = 0;

            Account account = loadAccount(ACCOUNT_ID);

            assertThat(evolveCalls).isEqualTo(4);
            assertThat(account.balance).isEqualTo(11050);
        }

        {
            evolveCalls = 0;

            Account account = loadAccount(ACCOUNT_ID);

            assertThat(evolveCalls).isZero();  // No evolution needed as a snapshot should have been created
            assertThat(account.balance).isEqualTo(11050);
        }
    }

    @Test
    protected void shouldIgnoreSnapshotIfVersionUnsupported(@Named("TestAppender") ListAppender appender) {
        publish(
            new AccountCreated(ACCOUNT_ID, "My Account"),
            new FundsDeposited(ACCOUNT_ID, 10050)
        );

        QualifiedName qualifiedName = new QualifiedName(Account.class);

        snapshotStore.store(
            qualifiedName,
            ACCOUNT_ID,
            new Snapshot(
                new GlobalIndexPosition(3),
                "42",  // unsupported version which leads to this snapshot being ignored
                new Account(ACCOUNT_ID, "My Account", 262144),
                Instant.now(),
                Map.of()
            )
        ).join();

        appender.clear();

        {   // Expect that a full evolution is needed, despite the snapshot
            evolveCalls = 0;

            Account account = loadAccount(ACCOUNT_ID);

            assertThat(evolveCalls).isEqualTo(2);
            assertThat(account.balance).isEqualTo(10050);  // if snapshot was not ignored, would be far higher
            assertThat(appender.getEvents())
                .extracting(LogEvent::getMessage)
                .extracting(Message::getFormattedMessage)
                .containsExactly("Unsupported snapshot version. Snapshot of " + qualifiedName + "#0.0.1 for identifier " + ACCOUNT_ID + " had unsupported version: 42");
        }

        publish(
            new FundsDeposited(ACCOUNT_ID, 1000),
            new FundsDeposited(ACCOUNT_ID, 1000),
            new FundsDeposited(ACCOUNT_ID, 1000),
            new FundsDeposited(ACCOUNT_ID, 1000)
        );

        {   // There are enough events to trigger a new snapshot (which should overwrite the bad one)
            evolveCalls = 0;

            Account account = loadAccount(ACCOUNT_ID);

            assertThat(evolveCalls).isEqualTo(6);
            assertThat(account.balance).isEqualTo(14050);
        }

        {   // New snapshot (overwriting the bad one) should be available now
            evolveCalls = 0;

            Account account = loadAccount(ACCOUNT_ID);

            assertThat(evolveCalls).isZero();
            assertThat(account.balance).isEqualTo(14050);
        }
    }

    @Test
    protected void shouldIgnoreExceptionsWhileLoadingSnapshot(@Named("TestAppender") ListAppender appender) {
        publish(
            new AccountCreated(ACCOUNT_ID, "My Account"),
            new FundsDeposited(ACCOUNT_ID, 10050)
        );

        QualifiedName qualifiedName = new QualifiedName(Account.class);

        snapshotStore.store(
            qualifiedName,
            ACCOUNT_ID,
            new Snapshot(
                new GlobalIndexPosition(3),
                "0.0.1",  // correct version
                "Junk",  // incorrect data, which leads to deserialization error
                Instant.now(),
                Map.of()
            )
        ).join();

        appender.clear();

        // Expect that a full evolution is needed, despite the snapshot
        evolveCalls = 0;

        Account account = loadAccount(ACCOUNT_ID);

        assertThat(evolveCalls).isEqualTo(2);
        assertThat(account.balance).isEqualTo(10050);

        assertThat(appender.getEvents())
            .extracting(LogEvent::getMessage)
            .extracting(Message::getFormattedMessage)
            .containsExactly("Snapshot loading failed, falling back to full reconstruction for: " + qualifiedName + "#0.0.1 (" + ACCOUNT_ID + ")");
    }

    /*
     * Verifies that the underlying snapshot store does not throw an exception (which is
     * logged as a warning) to indicate no snapshot exists; they should return null in those cases.
     */
    @Test
    protected void whenLoadingSnapshotShouldNotWarnThatNoSnapshotExists(@Named("TestAppender") ListAppender appender) {
        publish(
            new AccountCreated(ACCOUNT_ID, "My Account"),
            new FundsDeposited(ACCOUNT_ID, 10050)
        );

        appender.clear();

        // Expect a full evolution as there is no snapshot
        evolveCalls = 0;

        Account account = loadAccount(ACCOUNT_ID);

        assertThat(evolveCalls).isEqualTo(2);
        assertThat(account.balance).isEqualTo(10050);

        assertThat(appender.getEvents()).isEmpty();  // expect no log messages
    }

    /**
     * Publish the given events.
     *
     * @param events the events to publish, cannot be {@code null}
     */
    protected void publish(Object... events) {
        try {
            eventStore.publish(null, Arrays.stream(events).map(e -> new GenericEventMessage(new MessageType(e.getClass()), e)).toList()).join();
        }
        catch (Exception e) {
            throw new AssertionError("No exception expected", e);  // this gives us a proper location in the test that was running
        }
    }

    /**
     * Load the account with the given identifier.
     *
     * @param identifier the identifier, cannot be {@code null}
     * @return the account
     */
    protected Account loadAccount(String identifier) {
        try {
            Repository<String, Account> repository = stateManager.repository(Account.class, String.class);
            UnitOfWork unitOfWork = unitOfWorkFactory.create();

            return unitOfWork.executeWithResult(pc -> repository.load(identifier, pc)).join().entity();
        }
        catch (Exception e) {
            throw new AssertionError("No exception expected", e);  // this gives us a proper location in the test that was running
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
    protected sealed interface AccountEvents {}
    protected record AccountCreated(@EventTag(key = "account") String id, String name) implements AccountEvents {}
    protected record FundsWithdrawn(@EventTag(key = "account") String id, long amountWithdrawn) implements AccountEvents {}
    protected record FundsDeposited(@EventTag(key = "account") String id, long amountDeposited) implements AccountEvents {}
    protected record AccountClosed(@EventTag(key = "account") String id, String reason) implements AccountEvents {}

    protected record Account(String id, String name, long balance) {
        Account withBalance(long balance) {
            return new Account(id, name, balance);
        }

        static Account onEvent(Account input, AccountEvents events) {
            evolveCalls++;

            return switch(events) {
                case AccountCreated ac -> new Account(ac.id, ac.name, 0);
                case FundsWithdrawn fw -> input.withBalance(input.balance - fw.amountWithdrawn);
                case FundsDeposited fd -> input.withBalance(input.balance + fd.amountDeposited);
                case AccountClosed ac -> input;
            };
        }
    }

    private static class SealedEntityEvolver<T, E> implements EntityEvolver<E> {
        private final Class<T> cls;
        private final BiFunction<E, T, E> evolver;

        public SealedEntityEvolver(Class<T> cls, BiFunction<E, T, E> evolver) {
            this.cls = cls;
            this.evolver = evolver;
        }

        @Override
        public E evolve(E entity, EventMessage event, ProcessingContext context) {
            EventConverter converter = context.component(EventConverter.class);

            return evolver.apply(entity, event.payloadAs(cls, converter));
        }
    }
}
