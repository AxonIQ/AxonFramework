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

package org.axonframework.integrationtests.testsuite.giftcard;

import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.eventsourcing.annotation.AppendCriteriaBuilder;
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.eventsourcing.annotation.reflection.InjectEntityId;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.modelling.annotation.TargetEntityId;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies how an event-sourced entity's own state behaves when it appends events <em>during</em> a single
 * command-handler method invocation, contrasting a mutable and an immutable entity.
 * <p>
 * The scenario mirrors a command handler that appends more than one event in a row, reading its own state in between.
 * Two distinct behaviors are demonstrated:
 * <ul>
 *     <li>The persisted, re-sourced state is always correct (the appended events evolve the managed entity), regardless
 *     of whether the entity is mutable or immutable. This confirms the managed entity holds the evolved instance even
 *     for immutable entities, where each event-sourcing handler returns a brand-new instance.</li>
 *     <li>The state observed through {@code this} <em>within the same method</em>, after an append, only reflects the
 *     appended event for a mutable entity. A mutable event-sourcing handler mutates the very object {@code this} points
 *     to, so the change is visible immediately. An immutable event-sourcing handler returns a new instance that
 *     replaces the managed reference, but the local {@code this} of the executing method remains the pre-append
 *     snapshot, so intra-method reads do not observe it.</li>
 * </ul>
 *
 * @author Mateusz Nowak
 */
class EntityStateOnAppendDuringCommandIT {

    // Captures what count the entity observes on `this` AFTER the first append, still inside the same handler method.
    private static final List<Integer> MUTABLE_INTRA_METHOD_READS = new CopyOnWriteArrayList<>();
    private static final List<Integer> IMMUTABLE_INTRA_METHOD_READS = new CopyOnWriteArrayList<>();
    private static final List<Class<?>> MUTABLE_APPEND_CRITERIA_COMMANDS = new CopyOnWriteArrayList<>();
    private static final List<EventCriteria> MUTABLE_SOURCING_CRITERIA = new CopyOnWriteArrayList<>();

    // FQCN of the AxonServer enhancer, disabled to keep the in-memory defaults in place.
    private static final String AXON_SERVER_ENHANCER_FQCN =
            "io.axoniq.framework.axonserver.connector.configuration.AxonServerConfigurationEnhancer";

    private AxonConfiguration configuration;
    private CommandGateway commandGateway;

    @AfterEach
    void tearDown() {
        MUTABLE_INTRA_METHOD_READS.clear();
        IMMUTABLE_INTRA_METHOD_READS.clear();
        MUTABLE_APPEND_CRITERIA_COMMANDS.clear();
        MUTABLE_SOURCING_CRITERIA.clear();
        if (configuration != null) {
            configuration.shutdown();
        }
    }

    @SuppressWarnings("SameParameterValue")
    private void startAppWith(Class<?> idType, Class<?> entityType) {
        EventSourcedEntityModule<?, ?> entityModule = EventSourcedEntityModule.autodetected(idType, entityType);
        configuration = EventSourcingConfigurer.create()
                                               .componentRegistry(cr -> cr.registerModule(entityModule))
                                               .componentRegistry(cr -> cr.disableEnhancer(AXON_SERVER_ENHANCER_FQCN))
                                               .start();
        commandGateway = configuration.getComponent(CommandGateway.class);
    }

    @Nested
    class MutableEntity {

        @BeforeEach
        void setUp() {
            startAppWith(String.class, MutableCounter.class);
        }

        @Test
        void mutableEntitySeesItsOwnAppendedEventWithinTheSameHandlerMethod() {
            // given a created counter
            commandGateway.send(new CreateCounter("m-1")).getResultMessage().join();

            // when a single handler appends two Incremented events, reading `this` in between
            commandGateway.send(new IncrementTwiceInOneHandler("m-1")).getResultMessage().join();

            // then the intra-method read observed the first append (mutation of `this`)
            assertThat(MUTABLE_INTRA_METHOD_READS).containsExactly(1);
            // and the re-sourced state reflects both appends
            Integer count = commandGateway.send(new GetCount("m-1"), Integer.class).join();
            assertThat(count).isEqualTo(2);
        }

        @Test
        void oneAppendCriteriaBuilderAppliesSeparatelyToEveryRootEntityHandler() {
            // given
            commandGateway.send(new CreateCounter("m-2")).getResultMessage().join();
            MUTABLE_APPEND_CRITERIA_COMMANDS.clear();
            MUTABLE_SOURCING_CRITERIA.clear();

            // when
            commandGateway.send(new IncrementTwiceInOneHandler("m-2")).getResultMessage().join();
            commandGateway.send(new IncrementOnceInOneHandler("m-2")).getResultMessage().join();

            // then
            assertThat(MUTABLE_APPEND_CRITERIA_COMMANDS)
                    .containsExactly(IncrementTwiceInOneHandler.class, IncrementOnceInOneHandler.class);
            assertThat(MUTABLE_SOURCING_CRITERIA)
                    .allSatisfy(criteria -> assertThat(criteria.flatten())
                            .containsExactlyElementsOf(EventCriteria.havingTags("Counter", "m-2").flatten()));
        }
    }

    @Nested
    class ImmutableEntity {

        @BeforeEach
        void setUp() {
            startAppWith(String.class, ImmutableCounter.class);
        }

        @Test
        void immutableEntityDoesNotSeeItsOwnAppendedEventWithinTheSameHandlerMethodButStateIsStillPersisted() {
            // given a created counter
            commandGateway.send(new CreateCounter("i-1")).getResultMessage().join();

            // when a single handler appends two Incremented events, reading `this` in between
            commandGateway.send(new IncrementTwiceInOneHandler("i-1")).getResultMessage().join();

            // then the intra-method read did NOT observe the first append: `this` stays the pre-append snapshot
            assertThat(IMMUTABLE_INTRA_METHOD_READS).containsExactly(0);
            // but the managed entity still evolved: the re-sourced state reflects both appends
            Integer count = commandGateway.send(new GetCount("i-1"), Integer.class).join();
            assertThat(count).isEqualTo(2);
        }
    }

    // --- Commands ---

    record CreateCounter(@TargetEntityId String counterId) {

    }

    record IncrementTwiceInOneHandler(@TargetEntityId String counterId) {

    }

    record IncrementOnceInOneHandler(@TargetEntityId String counterId) {

    }

    record GetCount(@TargetEntityId String counterId) {

    }

    // --- Events ---

    record CounterCreated(@EventTag(key = "Counter") String counterId) {

    }

    record Incremented(@EventTag(key = "Counter") String counterId) {

    }

    // --- Mutable entity: void event-sourcing handlers mutate `this` in place ---

    @SuppressWarnings("unused")
    @EventSourcedEntity(tagKey = "Counter")
    static class MutableCounter {

        private final String counterId;
        private int count;

        @EntityCreator
        MutableCounter(@InjectEntityId String counterId) {
            this.counterId = counterId;
        }

        @CommandHandler
        static void handle(CreateCounter command, EventAppender appender) {
            appender.append(new CounterCreated(command.counterId()));
        }

        @CommandHandler
        void handle(IncrementTwiceInOneHandler command, EventAppender appender) {
            appender.append(new Incremented(counterId));
            MUTABLE_INTRA_METHOD_READS.add(count);
            appender.append(new Incremented(counterId));
        }

        @CommandHandler
        void handle(IncrementOnceInOneHandler command, EventAppender appender) {
            appender.append(new Incremented(counterId));
        }

        @CommandHandler
        int handle(GetCount command) {
            return count;
        }

        @AppendCriteriaBuilder
        static EventCriteria appendCriteria(CommandMessage command, EventCriteria sourcingCriteria) {
            MUTABLE_APPEND_CRITERIA_COMMANDS.add(command.payloadType());
            MUTABLE_SOURCING_CRITERIA.add(sourcingCriteria);
            return sourcingCriteria;
        }

        @EventSourcingHandler
        void on(Incremented event) {
            this.count++;
        }
    }

    // --- Immutable entity: event-sourcing handlers return a new instance ---

    @SuppressWarnings("unused")
    @EventSourcedEntity(tagKey = "Counter")
    record ImmutableCounter(String counterId, int count) {

        @EntityCreator
        ImmutableCounter(CounterCreated event) {
            this(event.counterId(), 0);
        }

        @CommandHandler
        static void handle(CreateCounter command, EventAppender appender) {
            appender.append(new CounterCreated(command.counterId()));
        }

        @CommandHandler
        void handle(IncrementTwiceInOneHandler command, EventAppender appender) {
            appender.append(new Incremented(counterId));
            IMMUTABLE_INTRA_METHOD_READS.add(count);
            appender.append(new Incremented(counterId));
        }

        @CommandHandler
        int handle(GetCount command) {
            return count;
        }

        @EventSourcingHandler
        ImmutableCounter on(Incremented event) {
            return new ImmutableCounter(counterId, count + 1);
        }
    }
}
