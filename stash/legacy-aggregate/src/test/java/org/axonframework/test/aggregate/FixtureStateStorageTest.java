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

package org.axonframework.test.aggregate;

import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.unitofwork.CurrentUnitOfWork;
import org.axonframework.modelling.command.AggregateCreationPolicy;
import org.axonframework.modelling.command.AggregateIdentifier;
import org.axonframework.modelling.command.CreationPolicy;
import org.axonframework.modelling.command.TargetAggregateIdentifier;
import org.axonframework.test.FixtureExecutionException;
import org.junit.jupiter.api.*;

import java.util.Collections;
import java.util.function.Supplier;

import static org.axonframework.modelling.command.AggregateLifecycle.apply;
import static org.hamcrest.CoreMatchers.any;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test class intended to validate the {@link AggregateTestFixture}'s inner workings after a {@link
 * AggregateTestFixture#givenState(Supplier)}.
 *
 * @author Allard Buijze
 */
class FixtureStateStorageTest {

    private static final String AGGREGATE_ID = "id";

    private FixtureConfiguration<StateStoredAggregate> fixture;

    @BeforeEach
    void setUp() {
        fixture = new AggregateTestFixture<>(StateStoredAggregate.class);
    }

    @AfterEach
    void tearDown() {
        if (CurrentUnitOfWork.isStarted()) {
            fail("A unit of work is still running");
        }
    }

    @Test
    @Disabled("TODO #3195 - Migration Module")
    void createStateStoredAggregate() {
        fixture.givenState(() -> new StateStoredAggregate(AGGREGATE_ID, "message"))
               .when(new SetMessageCommand(AGGREGATE_ID, "message2"))
               .expectEvents(new StubDomainEvent())
               .expectState(aggregate -> assertEquals("message2", aggregate.getMessage()));
    }

    @Test
    @Disabled("TODO #3195 - Migration Module")
    void givenCommandsForStateStoredAggregate() {
        fixture.useStateStorage()
               .givenCommands(new InitializeCommand(AGGREGATE_ID, "message"))
               .when(new SetMessageCommand(AGGREGATE_ID, "message2"))
               .expectEvents(new StubDomainEvent())
               .expectState(aggregate -> assertEquals("message2", aggregate.getMessage()));
    }


    @Test
    @Disabled("TODO #3195 - Migration Module")
    void createStateStoredAggregateWithCommand() {
        fixture.useStateStorage()
               .givenNoPriorActivity()
               .when(new InitializeCommand(AGGREGATE_ID, "message"))
               .expectEvents(new StubDomainEvent())
               .expectState(aggregate -> assertEquals("message", aggregate.getMessage()));
    }

    @Test
    @Disabled("TODO #3195 - Migration Module")
    void emittedEventsFromExpectStateAreNotStored() {
        fixture.givenState(() -> new StateStoredAggregate(AGGREGATE_ID, "message"))
               .when(new SetMessageCommand(AGGREGATE_ID, "message2"))
               .expectEvents(new StubDomainEvent())
               .expectState(aggregate -> {
                   apply(new StubDomainEvent());
                   assertEquals("message2", aggregate.getMessage());
               })
               .expectEvents(new StubDomainEvent())
               .expectState(Assertions::assertNotNull);
    }

    @Test
    @Disabled("TODO #3073 - Revisit Aggregate Test Fixture")
    void createStateStoredAggregate_ErrorInChanges() {
        ResultValidator<StateStoredAggregate> result =
                fixture.givenState(() -> new StateStoredAggregate(AGGREGATE_ID, "message"))
                       .when(new ErrorCommand(AGGREGATE_ID, "message2"))
                       .expectException(any(Exception.class))
                       .expectNoEvents();
        IllegalStateException e = assertThrows(
                IllegalStateException.class,
                () -> result.expectState(aggregate -> assertEquals("message2", aggregate.getMessage()))
        );
        assertTrue(e.getMessage().contains("Unit of Work"), "Wrong message: " + e.getMessage());
        assertTrue(e.getMessage().contains("rolled back"), "Wrong message: " + e.getMessage());
    }

    /**
     * Follow up on GitHub issue https://github.com/AxonFramework/AxonFramework/issues/1219
     */
    @Test
    @Disabled("TODO #3195 - Migration Module")
    void stateStoredAggregateCanAttachRegisteredResource() {
        String expectedMessage = "state stored resource injection works";
        HardToCreateResource testResource = spy(new HardToCreateResource());

        fixture.registerInjectableResource(testResource)
               .givenState(() -> new StateStoredAggregate(AGGREGATE_ID, "message"))
               .when(new HandleWithResourceCommand(AGGREGATE_ID, expectedMessage))
               .expectEvents(new StubDomainEvent());

        verify(testResource).difficultOperation(expectedMessage);
    }

    @Test
    void givenWithStateStorageException() {
        fixture.useStateStorage();

        assertThrows(
                FixtureExecutionException.class,
                () -> fixture.given(new StubDomainEvent())
        );
    }

    @Test
    void givenWithEventListAndStateStorageExpectException() {
        fixture.useStateStorage();

        assertThrows(
                FixtureExecutionException.class,
                () -> fixture.given(Collections.singletonList(new StubDomainEvent()))
        );
    }

    private static class InitializeCommand {

        private final String id;
        private final String message;

        private InitializeCommand(String id, String message) {
            this.id = id;
            this.message = message;
        }

        public String getId() {
            return id;
        }

        public String getMessage() {
            return message;
        }
    }

    private static class SetMessageCommand {

        @TargetAggregateIdentifier
        private final String id;
        private final String message;

        private SetMessageCommand(String id, String message) {
            this.id = id;
            this.message = message;
        }

        public String getId() {
            return id;
        }

        public String getMessage() {
            return message;
        }
    }

    private static class ErrorCommand {

        @TargetAggregateIdentifier
        private final String id;
        private final String message;

        private ErrorCommand(String id, String message) {
            this.id = id;
            this.message = message;
        }

        public String getId() {
            return id;
        }

        public String getMessage() {
            return message;
        }
    }

    private static class HandleWithResourceCommand {

        @TargetAggregateIdentifier
        private final String id;
        private final String messageToLog;

        private HandleWithResourceCommand(String id, String messageToLog) {
            this.id = id;
            this.messageToLog = messageToLog;
        }

        public String getId() {
            return id;
        }

        public String getMessageToLog() {
            return messageToLog;
        }
    }

    @SuppressWarnings("unused")
    public static class StateStoredAggregate {

        @AggregateIdentifier
        private String id;

        private String message;

        StateStoredAggregate(String id, String message) {
            this.id = id;
            this.message = message;
            // this event, published during the givenState operation, should not be included in the expectEvents phase
            apply(new StubDomainEvent());
        }

        @CommandHandler
        @CreationPolicy(AggregateCreationPolicy.ALWAYS)
        public void handle(InitializeCommand cmd) {
            this.id = cmd.getId();
            this.message = cmd.getMessage();
            apply(new StubDomainEvent());
        }

        @CommandHandler
        public void handle(SetMessageCommand cmd) {
            this.message = cmd.getMessage();
            apply(new StubDomainEvent());
        }

        @CommandHandler
        public void handle(ErrorCommand cmd) {
            this.message = cmd.getMessage();
            apply(new StubDomainEvent());
            throw new RuntimeException("Stub");
        }

        @CommandHandler
        public void handle(HandleWithResourceCommand command, HardToCreateResource resource) {
            resource.difficultOperation(command.getMessageToLog());
            apply(new StubDomainEvent());
        }

        public String getMessage() {
            return message;
        }
    }

    private static class StubDomainEvent {

        StubDomainEvent() {
        }
    }
}
