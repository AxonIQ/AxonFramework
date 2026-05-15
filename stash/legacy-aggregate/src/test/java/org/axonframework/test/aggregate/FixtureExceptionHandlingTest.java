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

import org.axonframework.messaging.commandhandling.CommandExecutionException;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.NoHandlerForCommandException;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.eventsourcing.eventstore.EventStoreException;
import org.axonframework.modelling.command.AggregateCreationPolicy;
import org.axonframework.modelling.command.AggregateIdentifier;
import org.axonframework.modelling.command.CreationPolicy;
import org.axonframework.modelling.command.TargetAggregateIdentifier;
import org.axonframework.test.AxonAssertionError;
import org.axonframework.test.FixtureExecutionException;
import org.junit.jupiter.api.*;

import static org.axonframework.modelling.command.AggregateLifecycle.apply;
import static org.hamcrest.core.StringContains.containsString;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Fixture tests validating exception handling.
 *
 * @author Patrick Haas
 */
class FixtureExceptionHandlingTest {

    private static final String AGGREGATE_ID = "1";

    private final FixtureConfiguration<MyAggregate> fixture = new AggregateTestFixture<>(MyAggregate.class);

    @Test
    @Disabled("TODO #3062 - Revisit Exception Handling")
    void createAggregate() {
        fixture.givenCommands()
               .when(new CreateMyAggregateCommand("14"))
               .expectEvents(new MyAggregateCreatedEvent("14"));
    }

    @Test
    @Disabled("TODO #3062 - Revisit Exception Handling")
    void givenUnknownCommand() {
        FixtureExecutionException result = assertThrows(FixtureExecutionException.class, () ->
                fixture.givenCommands(
                        new CreateMyAggregateCommand("14"),
                        new UnknownCommand("14")
                )
        );
        assertEquals(NoHandlerForCommandException.class, result.getCause().getClass());
    }

    @Test
    @Disabled("TODO #3062 - Revisit Exception Handling")
    void whenExceptionTriggeringCommand() {
        fixture.givenCommands(new CreateMyAggregateCommand("14"))
               .when(new ExceptionTriggeringCommand("14"))
               .expectException(RuntimeException.class);
    }

    @Test
    void givenExceptionTriggeringCommand() {
        assertThrows(RuntimeException.class, () ->
                fixture.givenCommands(
                        new CreateMyAggregateCommand("14"),
                        new ExceptionTriggeringCommand("14")
                )
        );
    }

    @Test
    @Disabled("TODO #3062 - Revisit Exception Handling")
    void givenCommandWithInvalidIdentifier() {
        fixture.givenCommands(new CreateMyAggregateCommand("1"))
               .when(new ValidMyAggregateCommand("2"))
               .expectException(EventStoreException.class);
    }

    @Test
    @Disabled("TODO #3062 - Revisit Exception Handling")
    void exceptionMessageCheck() {
        fixture.givenCommands(new CreateMyAggregateCommand("1"))
               .when(new ValidMyAggregateCommand("2"))
               .expectException(EventStoreException.class)
               .expectExceptionMessage(
                       "The aggregate identifier used in the 'when' step does not resemble the aggregate identifier "
                               + "used in the 'given' step. "
                               + "Please make sure the when-identifier [2] resembles the given-identifier [1]."
               );
    }

    @Test
    @Disabled("TODO #3062 - Revisit Exception Handling")
    void exceptionMessageCheckWithMatcher() {
        fixture.givenCommands(new CreateMyAggregateCommand("1"))
               .when(new ValidMyAggregateCommand("2"))
               .expectException(EventStoreException.class)
               .expectExceptionMessage(containsString("when-identifier"));
    }

    @Test
    @Disabled("TODO #3062 - Revisit Exception Handling")
    void exceptionDetailsCheckWithEquality() {
        fixture.givenCommands(new CreateMyAggregateCommand("1"))
                .when(new ExceptionWithDetailsTriggeringCommand("1"))
                .expectException(CommandExecutionException.class)
                .expectExceptionDetails("Details");
    }

    @Test
    @Disabled("TODO #3062 - Revisit Exception Handling")
    void exceptionDetailsCheckWithType() {
        fixture.givenCommands(new CreateMyAggregateCommand("1"))
               .when(new ExceptionWithDetailsTriggeringCommand("1"))
               .expectException(CommandExecutionException.class)
               .expectExceptionDetails(String.class);
    }

    @Test
    @Disabled("TODO #3062 - Revisit Exception Handling")
    void exceptionDetailsCheckWithMatcher() {
        fixture.givenCommands(new CreateMyAggregateCommand("1"))
               .when(new ExceptionWithDetailsTriggeringCommand("1"))
               .expectException(CommandExecutionException.class)
               .expectExceptionDetails(containsString("Details"));
    }

    @Test
    @Disabled("TODO #3062 - Revisit Exception Handling")
    void whenCommandWithInvalidIdentifier() {
        assertThrows(FixtureExecutionException.class, () ->
                fixture.givenCommands(
                        new CreateMyAggregateCommand("1"),
                        new ValidMyAggregateCommand("2")
                )
        );
    }

    @Test
    void expectExceptionMessageThrowsFixtureExecutionExceptionWhenNoExceptionIsThrown() {
        assertThrows(
                AxonAssertionError.class,
                () -> fixture.given(new MyAggregateCreatedEvent(AGGREGATE_ID))
                             .when(new ValidMyAggregateCommand(AGGREGATE_ID))
                             .expectExceptionMessage("some-exception-message")
        );
    }

    @Test
    void expectUncheckedExceptionsDuringAggregateConstructorCommandHandler() {
        boolean throwUncheckedException = true;
        boolean doNotThrowCheckedException = false;

        fixture.givenCommands()
               .when(new CreateMyAggregateCommand("14", doNotThrowCheckedException, throwUncheckedException))
               .expectException(RuntimeException.class);
    }

    @Test
    @Disabled("TODO #3062 - Revisit Exception Handling")
    void expectCheckedExceptionsDuringAggregateConstructorCommandHandler() {
        boolean doNoThrowUncheckedException = false;
        boolean throwCheckedException = true;

        fixture.givenCommands()
               .when(new CreateMyAggregateCommand("14", throwCheckedException, doNoThrowUncheckedException))
               .expectException(CheckedException.class);
    }

    private static abstract class AbstractMyAggregateCommand {

        @TargetAggregateIdentifier
        public final String id;

        protected AbstractMyAggregateCommand(String id) {
            this.id = id;
        }
    }

    private static class CreateMyAggregateCommand extends AbstractMyAggregateCommand {

        private final boolean shouldThrowCheckedException;
        private final boolean shouldThrowUncheckedException;

        protected CreateMyAggregateCommand(String id) {
            this(id, false, false);
        }

        protected CreateMyAggregateCommand(String id,
                                           boolean shouldThrowCheckedException,
                                           boolean shouldThrowUncheckedException) {
            super(id);
            this.shouldThrowCheckedException = shouldThrowCheckedException;
            this.shouldThrowUncheckedException = shouldThrowUncheckedException;
        }
    }

    private static class ExceptionTriggeringCommand extends AbstractMyAggregateCommand {

        protected ExceptionTriggeringCommand(String id) {
            super(id);
        }
    }

    private static class ExceptionWithDetailsTriggeringCommand extends AbstractMyAggregateCommand {

        protected ExceptionWithDetailsTriggeringCommand(String id) {
            super(id);
        }
    }

    private static class ValidMyAggregateCommand extends AbstractMyAggregateCommand {

        protected ValidMyAggregateCommand(String id) {
            super(id);
        }
    }

    private static class UnknownCommand extends AbstractMyAggregateCommand {

        protected UnknownCommand(String id) {
            super(id);
        }
    }

    private static class MyAggregateCreatedEvent {

        public final String id;

        public MyAggregateCreatedEvent(String id) {
            this.id = id;
        }
    }

    @SuppressWarnings("unused")
    private static class MyAggregate {

        @AggregateIdentifier
        String id;

        private MyAggregate() {
        }

        @CommandHandler
        @CreationPolicy(AggregateCreationPolicy.ALWAYS)
        public void handle(CreateMyAggregateCommand cmd) throws CheckedException {
            if (cmd.shouldThrowCheckedException) {
                throw new CheckedException();
            }
            if (cmd.shouldThrowUncheckedException) {
                throw new RuntimeException();
            }
            apply(new MyAggregateCreatedEvent(cmd.id));
        }

        @CommandHandler
        public void handle(ValidMyAggregateCommand cmd) {
            /* no-op */
        }

        @CommandHandler
        public void handle(ExceptionTriggeringCommand cmd) {
            throw new RuntimeException("Error");
        }

        @CommandHandler
        public void handle(ExceptionWithDetailsTriggeringCommand cmd) {
            throw new CommandExecutionException("Error", null, "Details");
        }

        @EventHandler
        private void on(MyAggregateCreatedEvent event) {
            this.id = event.id;
        }
    }

    private static class CheckedException extends Exception {

    }
}
