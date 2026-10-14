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

package org.axonframework.test.fixture;

import org.jspecify.annotations.NonNull;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.commandhandling.GenericCommandResultMessage;
import org.axonframework.messaging.commandhandling.SimpleCommandBus;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.core.EmptyApplicationContext;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.SubscribableEventSource;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.core.unitofwork.SimpleUnitOfWorkFactory;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventSink;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.test.AxonAssertionError;
import org.axonframework.test.fixture.sampledomain.ChangeStudentNameCommand;
import org.axonframework.test.fixture.sampledomain.StudentNameChangedEvent;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class AxonTestFixtureMessagingTest {

    @Nested
    class WhenCommand {

        @Test
        void givenNoPriorActivityWhenCommandThenExpectEvents() {
            var configurer = messagingConfigurer();
            registerChangeStudentNameHandlerReturnsEmpty(configurer);

            var fixture = AxonTestFixture.with(configurer);

            fixture.given()
                   .noPriorActivity()
                   .when()
                   .command(new ChangeStudentNameCommand("my-studentId-1", "name-1"))
                   .then()
                   .events(studentNameChangedEvent("my-studentId-1", "name-1", 1));
        }

        @Test
        void givenNothingWhenCommandThenExpectEvents() {
            var configurer = messagingConfigurer();
            registerChangeStudentNameHandlerReturnsEmpty(configurer);

            var fixture = AxonTestFixture.with(configurer);

            fixture.given()
                   .when()
                   .command(new ChangeStudentNameCommand("my-studentId-1", "name-1"))
                   .then()
                   .events(studentNameChangedEvent("my-studentId-1", "name-1", 1));
        }

        @Test
        void whenCommandThenExpectEvents() {
            var configurer = messagingConfigurer();
            registerChangeStudentNameHandlerReturnsEmpty(configurer);

            var fixture = AxonTestFixture.with(configurer);

            fixture.when()
                   .command(new ChangeStudentNameCommand("my-studentId-1", "name-1"))
                   .then()
                   .events(studentNameChangedEvent("my-studentId-1", "name-1", 1));
        }

        @Test
        void whenCommandThenSuccess() {
            var configurer = messagingConfigurer();
            registerChangeStudentNameHandlerReturnsEmpty(configurer);

            var fixture = AxonTestFixture.with(configurer);

            fixture.when()
                   .command(new ChangeStudentNameCommand("my-studentId-1", "name-1"))
                   .then()
                   .success();
        }

        @Test
        void whenCommandReturnsEmptyThenSuccessWithNullPayload() {
            var configurer = messagingConfigurer();
            registerChangeStudentNameHandlerReturnsEmpty(configurer);

            var fixture = AxonTestFixture.with(configurer);

            fixture.when()
                   .command(new ChangeStudentNameCommand("my-studentId-1", "name-1"))
                   .then()
                   .success()
                   .resultMessageSatisfies(resultMessage -> Assertions.assertNull(resultMessage.payload()));
        }

        @Test
        void whenCommandReturnsSingleThenSuccessWithValue() {
            var configurer = messagingConfigurer();
            registerChangeStudentNameHandlerReturnsSingle(configurer);

            var fixture = AxonTestFixture.with(configurer);

            fixture.when()
                   .command(new ChangeStudentNameCommand("my-studentId-1", "name-1"))
                   .then()
                   .success()
                   .resultMessagePayload(new CommandResult("Result name-1"));
        }

        @Test
        void whenCommandReturnsSingleThenSuccessWithValueConsumer() {
            var configurer = messagingConfigurer();
            registerChangeStudentNameHandlerReturnsSingle(configurer);

            var fixture = AxonTestFixture.with(configurer);

            fixture.when()
                   .command(new ChangeStudentNameCommand("my-studentId-1", "name-1"))
                   .then()
                   .resultMessagePayloadSatisfies(result -> {
                       var payload = (CommandResult) result;
                       assertEquals("Result name-1", payload.message());
                       assertNull(payload.metadataSample());
                   }).success();
        }

        @Test
        void whenCommandWithMetadataThenSuccessWithTheMetadata() {
            var configurer = messagingConfigurer();
            registerChangeStudentNameHandlerReturnsSingle(configurer);

            var fixture = AxonTestFixture.with(configurer);

            fixture.when()
                   .command(new ChangeStudentNameCommand("my-studentId-1", "name-1"),
                            Metadata.with("sample", "metaValue"))
                   .then()
                   .success()
                   .resultMessagePayload(new CommandResult("Result name-1", "metaValue"));
        }

        @Test
        void whenCommandWithMetadataMapThenSuccessWithTheMetadata() {
            var configurer = messagingConfigurer();
            registerChangeStudentNameHandlerReturnsSingle(configurer);

            var fixture = AxonTestFixture.with(configurer);

            fixture.when()
                   .command(new ChangeStudentNameCommand("my-studentId-1", "name-1"), Map.of("sample", "metaValue"))
                   .then()
                   .success()
                   .resultMessagePayload(new CommandResult("Result name-1", "metaValue"));
        }

        @Test
        void whenCommandWithMetadataMapThenSuccessWithTheMetadataSatisfies() {
            var configurer = messagingConfigurer();
            registerChangeStudentNameHandlerReturnsSingle(configurer);

            var fixture = AxonTestFixture.with(configurer);

            fixture.when()
                   .command(new ChangeStudentNameCommand("my-studentId-1", "name-1"), Map.of("sample", "metaValue"))
                   .then()
                   .success()
                   .resultMessageSatisfies(c -> assertEquals(new CommandResult("Result name-1", "metaValue"),
                                                             c.payload()));
        }

        @Test
        void givenEventsWhenCommandReturnsSingleThenSuccessWithValue() {
            var configurer = messagingConfigurer();
            registerChangeStudentNameHandlerReturnsSingle(configurer);

            var fixture = AxonTestFixture.with(configurer);

            var givenEvents = List.of(
                    new StudentNameChangedEvent("my-studentId-1", "name-1", 1),
                    new StudentNameChangedEvent("my-studentId-1", "name-2", 2)
            );
            fixture.given()
                   .events(givenEvents)
                   .when()
                   .command(new ChangeStudentNameCommand("my-studentId-1", "name-3"))
                   .then()
                   .success()
                   .resultMessagePayload(new CommandResult("Result name-3"));
        }

        @Test
        void givenSingleEventMessageWhenCommandThenSuccess() {
            // given
            var configurer = messagingConfigurer();
            registerChangeStudentNameHandlerReturnsSingle(configurer);
            var receivedEvents = new ArrayList<>();

            var fixture = AxonTestFixture.with(configurer);

            // when
            fixture.given()
                   .execute(c -> c.getComponent(SubscribableEventSource.class).subscribe((events, context) -> {
                       receivedEvents.addAll(events);
                       return CompletableFuture.completedFuture(null);
                   }))
                   .event(studentNameChangedEventMessage("my-studentId-1", "name-1", 1))
                   .when()
                   .command(new ChangeStudentNameCommand("my-studentId-1", "name-2"))
                   .then()
                   .success();

            // then - verify EventMessage was passed through correctly (not wrapped)
            // First event is from Given phase, second event is from command handler
            assertEquals(2, receivedEvents.size());
            var givenPhaseEvent = (EventMessage) receivedEvents.getFirst();
            assertEquals("my-studentId-1", ((StudentNameChangedEvent) givenPhaseEvent.payload()).id());
            assertEquals("name-1", ((StudentNameChangedEvent) givenPhaseEvent.payload()).name());
        }

        @Test
        void givenEventsWithEventMessagesWhenCommandThenSuccess() {
            // given
            var configurer = messagingConfigurer();
            registerChangeStudentNameHandlerReturnsSingle(configurer);
            var receivedEvents = new ArrayList<>();

            var fixture = AxonTestFixture.with(configurer);

            // when
            fixture.given()
                   .execute(c -> c.getComponent(SubscribableEventSource.class).subscribe((events, context) -> {
                       receivedEvents.addAll(events);
                       return CompletableFuture.completedFuture(null);
                   }))
                   .events(
                           studentNameChangedEventMessage("my-studentId-1", "name-1", 1),
                           studentNameChangedEventMessage("my-studentId-1", "name-2", 2)
                   )
                   .when()
                   .command(new ChangeStudentNameCommand("my-studentId-1", "name-3"))
                   .then()
                   .success();

            // then - verify EventMessages were passed through correctly (not wrapped)
            // First two events are from Given phase, third event is from command handler
            assertEquals(3, receivedEvents.size());
            var firstEvent = (EventMessage) receivedEvents.get(0);
            var secondEvent = (EventMessage) receivedEvents.get(1);
            assertEquals("name-1", ((StudentNameChangedEvent) firstEvent.payload()).name());
            assertEquals("name-2", ((StudentNameChangedEvent) secondEvent.payload()).name());
        }

        @Test
        void givenEventsWithListOfEventMessagesWhenCommandThenSuccess() {
            // given
            var configurer = messagingConfigurer();
            registerChangeStudentNameHandlerReturnsSingle(configurer);
            var receivedEvents = new ArrayList<EventMessage>();

            var fixture = AxonTestFixture.with(configurer);

            var givenEventMessage1 = studentNameChangedEventMessage("my-studentId-1", "name-1", 1);
            var givenEventMessage2 = studentNameChangedEventMessage("my-studentId-1", "name-2", 2);
            var givenEventMessages = List.of(givenEventMessage1, givenEventMessage2);

            // when
            fixture.given()
                   .execute(c -> c.getComponent(SubscribableEventSource.class).subscribe((events, context) -> {
                       receivedEvents.addAll(events);
                       return CompletableFuture.completedFuture(null);
                   }))
                   .events(givenEventMessages)
                   .when()
                   .command(new ChangeStudentNameCommand("my-studentId-1", "name-3"))
                   .then()
                   .success();

            // then - verify EventMessages were passed through correctly (not wrapped)
            // First two events are from Given phase, third event is from command handler
            assertEquals(3, receivedEvents.size());
            // Verify the exact same EventMessage instances were received (not wrapped)
            assertSame(givenEventMessage1, receivedEvents.get(0));
            assertSame(givenEventMessage2, receivedEvents.get(1));
        }

        @Test
        void givenEventMessageWithAdditionalMetadataThenMetadataIsMerged() {
            // given
            var configurer = messagingConfigurer();
            registerChangeStudentNameHandlerReturnsSingle(configurer);
            var receivedEvents = new ArrayList<EventMessage>();

            var fixture = AxonTestFixture.with(configurer);

            var originalEventMessage = new GenericEventMessage(
                    new MessageType(StudentNameChangedEvent.class),
                    new StudentNameChangedEvent("my-studentId-1", "name-1", 1),
                    Metadata.with("original-key", "original-value")
            );

            // when
            fixture.given()
                   .execute(c -> c.getComponent(SubscribableEventSource.class).subscribe((events, context) -> {
                       receivedEvents.addAll(events);
                       return CompletableFuture.completedFuture(null);
                   }))
                   .event(originalEventMessage, Metadata.with("additional-key", "additional-value"))
                   .when()
                   .command(new ChangeStudentNameCommand("my-studentId-1", "name-2"))
                   .then()
                   .success();

            // then - verify metadata was merged
            assertEquals(2, receivedEvents.size());
            var receivedEvent = receivedEvents.getFirst();
            assertEquals("original-value", receivedEvent.metadata().get("original-key"));
            assertEquals("additional-value", receivedEvent.metadata().get("additional-key"));
        }

        @Test
        void givenEventWhenCommandThenExpectEvents() {
            var configurer = messagingConfigurer();
            var studentEvents = new ArrayList<>();
            configurer.registerCommandBus(
                    c -> aCommandBus()
                            .subscribe(
                                    new QualifiedName(ChangeStudentNameCommand.class),
                                    (command, context) -> {
                                        ChangeStudentNameCommand payload = (ChangeStudentNameCommand) command.payload();
                                        var eventSink = c.getComponent(EventSink.class);
                                        var changeNo = studentEvents.size() + 1;
                                        eventSink.publish(context,
                                                          studentNameChangedEventMessage(payload.id(), payload.name(),
                                                                                         changeNo));
                                        return MessageStream.empty().cast();
                                    }));

            var fixture = AxonTestFixture.with(configurer);

            fixture.given()
                   .execute(c -> c.getComponent(SubscribableEventSource.class).subscribe((events, context) -> {
                       studentEvents.addAll(events);
                       return CompletableFuture.completedFuture(null);
                   }))
                   .event(studentNameChangedEventMessage("my-studentId-1", "name-1", 1))
                   .when()
                   .command(new ChangeStudentNameCommand("my-studentId-1", "name-1"))
                   .then()
                   .events(studentNameChangedEventMessage("my-studentId-1", "name-1", 2));
        }

        static @NonNull SimpleCommandBus aCommandBus() {
            return new SimpleCommandBus(new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE));
        }

        @Test
        void givenNoPriorActivityWhenCommandThenExpectException() {
            var configurer = messagingConfigurer();
            configurer.componentRegistry(cr -> cr.registerDecorator(
                    CommandBus.class,
                    0,
                    (c, n, d) -> d.subscribe(
                            new QualifiedName(ChangeStudentNameCommand.class),
                            (command, context) -> MessageStream.failed(new RuntimeException("Test"))
                    )
            ));

            var fixture = AxonTestFixture.with(configurer);

            fixture.given()
                   .noPriorActivity()
                   .when()
                   .command(new ChangeStudentNameCommand("my-studentId-1", "name-1"))
                   .then()
                   .exception(RuntimeException.class)
                   .exceptionSatisfies(thrown -> assertEquals("Test", thrown.getMessage()));
        }

        @Test
        void givenNoPriorActivityWhenCommandHandlerThrowsThenExpectException() {
            var configurer = messagingConfigurer();
            configurer.componentRegistry(cr -> cr.registerDecorator(
                    CommandBus.class,
                    0,
                    (c, n, d) -> d.subscribe(
                            new QualifiedName(ChangeStudentNameCommand.class),
                            (command, context) -> {
                                throw new RuntimeException("Simulated exception");
                            }
                    )
            ));

            var fixture = AxonTestFixture.with(configurer);

            fixture.given()
                   .noPriorActivity()
                   .when()
                   .command(new ChangeStudentNameCommand("my-studentId-1", "name-1"))
                   .then()
                   .exception(RuntimeException.class)
                   .exceptionSatisfies(thrown -> assertEquals("Simulated exception", thrown.getMessage()));
        }

        @Nested
        class AssertionErrors {

            @Test
            void ifThenNoEventsButThereAreSomeEvents() {
                var configurer = messagingConfigurer();
                registerChangeStudentNameHandlerReturnsEmpty(configurer);

                var fixture = AxonTestFixture.with(configurer);

                var assertionError = assertThrows(AxonAssertionError.class, () ->
                        fixture.given()
                               .noPriorActivity()
                               .when()
                               .command(new ChangeStudentNameCommand("my-studentId-1", "name-1"))
                               .then()
                               .noEvents()
                );
                assertTrue(assertionError.getMessage()
                                         .contains("The published events do not match the expected events"));
            }


            @Test
            void ifThenExpectEventsButWithDifferentPayload() {
                var configurer = messagingConfigurer();
                registerChangeStudentNameHandlerReturnsEmpty(configurer);

                var fixture = AxonTestFixture.with(configurer);

                var assertionError = assertThrows(AxonAssertionError.class, () ->
                        fixture.given()
                               .when()
                               .command(new ChangeStudentNameCommand("my-studentId-1", "name-1"))
                               .then()
                               .events(studentNameChangedEventMessage("my-studentId-1", "name-2", 1))
                );
                assertTrue(assertionError.getMessage()
                                         .contains("One of the messages contained a different payload than expected"));
            }

            @Test
            void ifExpectExceptionButSuccess() {
                var configurer = messagingConfigurer();
                configurer.componentRegistry(cr -> cr.registerDecorator(
                        CommandBus.class,
                        0,
                        (c, n, d) -> d.subscribe(
                                new QualifiedName(ChangeStudentNameCommand.class),
                                (command, context) -> MessageStream.empty().cast()
                        )
                ));

                var fixture = AxonTestFixture.with(configurer);

                var assertionError = assertThrows(AxonAssertionError.class, () ->
                        fixture.given()
                               .noPriorActivity()
                               .when()
                               .command(new ChangeStudentNameCommand("my-studentId-1", "name-1"))
                               .then()
                               .exception(RuntimeException.class)
                );
                assertTrue(assertionError.getMessage()
                                         .contains(
                                                 "The message handler returned normally, but an exception was expected"));
            }

            @Test
            void ifExpectSuccessButException() {
                var configurer = messagingConfigurer();
                configurer.componentRegistry(cr -> cr.registerDecorator(
                        CommandBus.class,
                        0,
                        (c, n, d) -> d.subscribe(
                                new QualifiedName(ChangeStudentNameCommand.class),
                                (command, context) -> MessageStream.failed(new RuntimeException("Test"))
                        )
                ));

                var fixture = AxonTestFixture.with(configurer);

                var assertionError = assertThrows(AxonAssertionError.class, () ->
                        fixture.given()
                               .noPriorActivity()
                               .when()
                               .command(new ChangeStudentNameCommand("my-studentId-1", "name-1"))
                               .then()
                               .success()
                );
                assertTrue(assertionError.getMessage().contains("The message handler threw an unexpected exception"));
            }
        }
    }

    @Nested
    class WhenEvent {

        @Test
        void whenEventThenExpectedCommand() {
            var configurer = messagingConfigurer();
            AtomicBoolean eventHandled = new AtomicBoolean(false);
            registerChangeStudentNameHandlerReturnsSingle(configurer);

            var fixture = AxonTestFixture.with(configurer);

            fixture.given()
                   .execute(c -> c.getComponent(SubscribableEventSource.class).subscribe((events, context) -> {
                       if (!eventHandled.getAndSet(true)) {
                           var commandGateway = c.getComponent(CommandGateway.class);
                           commandGateway.sendAndWait(new ChangeStudentNameCommand("id", "name"));
                       }
                       return CompletableFuture.completedFuture(null);
                   }))
                   .when()
                   .event(new StudentNameChangedEvent("my-studentId-1", "name-1", 1))
                   .then()
                   .commands(new ChangeStudentNameCommand("id", "name"));
        }

        @Test
        void whenEventThenExpectedCommandCustomAssertion() {
            var configurer = messagingConfigurer();
            AtomicBoolean eventHandled = new AtomicBoolean(false);
            registerChangeStudentNameHandlerReturnsSingle(configurer);

            var fixture = AxonTestFixture.with(configurer);

            fixture.given()
                   .execute(c -> c.getComponent(SubscribableEventSource.class).subscribe((events, context) -> {
                       if (!eventHandled.getAndSet(true)) {
                           var commandGateway = c.getComponent(CommandGateway.class);
                           commandGateway.sendAndWait(new ChangeStudentNameCommand("id", "name"));
                       }
                       return CompletableFuture.completedFuture(null);
                   }))
                   .when()
                   .event(new StudentNameChangedEvent("my-studentId-1", "name-1", 1))
                   .then()
                   .commandsSatisfy(commands -> {
                       assertEquals(1, commands.size());
                       var command = commands.getFirst();
                       assertEquals("id", ((ChangeStudentNameCommand) command.payload()).id());
                       assertEquals("name", ((ChangeStudentNameCommand) command.payload()).name());
                   }).commandsMatch(commands -> !commands.isEmpty())
                   .success();
        }

        @Test
        void whenEventHandlerFailsThenException() {
            var configurer = messagingConfigurer();

            var fixture = AxonTestFixture.with(configurer);

            fixture.given()
                   .execute(c -> c.getComponent(SubscribableEventSource.class).subscribe((events, context) -> {
                       throw new RuntimeException("Simulated failure");
                   }))
                   .when()
                   .event(new StudentNameChangedEvent("my-studentId-1", "name-1", 1))
                   .then()
                   .exception(RuntimeException.class)
                   .noCommands();
        }

        @Test
        void whenEventHandlerDoesNotFailThenSuccess() {
            var configurer = messagingConfigurer();

            var fixture = AxonTestFixture.with(configurer);

            fixture.given()
                   .execute(c -> c.getComponent(SubscribableEventSource.class).subscribe((events, context) -> {
                       // No-op handler - succeeds without throwing
                       return CompletableFuture.completedFuture(null);
                   }))
                   .when()
                   .event(new StudentNameChangedEvent("my-studentId-1", "name-1", 1))
                   .then()
                   .noCommands()
                   .success();
        }

        @Test
        void whenEventsHandlerDoesNotFailThenSuccess() {
            var configurer = messagingConfigurer();

            var fixture = AxonTestFixture.with(configurer);

            var whenEvents = List.of(
                    new StudentNameChangedEvent("my-studentId-1", "name-1", 1),
                    new StudentNameChangedEvent("my-studentId-1", "name-2", 2)
            );
            fixture.given()
                   .execute(c -> c.getComponent(SubscribableEventSource.class).subscribe((events, context) -> {
                       // No-op handler - succeeds without throwing
                       return CompletableFuture.completedFuture(null);
                   }))
                   .when()
                   .events(whenEvents)
                   .then()
                   .success()
                   .noCommands();
        }

        @Test
        void whenEventsWithPayloadVarargsThenSuccess() {
            // given
            var configurer = messagingConfigurer();
            var receivedEvents = new ArrayList<>();
            var fixture = AxonTestFixture.with(configurer);

            // when
            fixture.given()
                   .execute(c -> c.getComponent(SubscribableEventSource.class).subscribe((events, context) -> {
                       receivedEvents.addAll(events);
                       return CompletableFuture.completedFuture(null);
                   }))
                   .when()
                   .events(
                           new StudentNameChangedEvent("my-studentId-1", "name-1", 1),
                           new StudentNameChangedEvent("my-studentId-1", "name-2", 2)
                   )
                   .then()
                   .success()
                   .noCommands();

            // then
            assertEquals(2, receivedEvents.size());
        }

        @Test
        void whenEventsWithEventMessageVarargsThenSuccess() {
            // given
            var configurer = messagingConfigurer();
            var receivedEvents = new ArrayList<>();
            var fixture = AxonTestFixture.with(configurer);

            // when
            fixture.given()
                   .execute(c -> c.getComponent(SubscribableEventSource.class).subscribe((events, context) -> {
                       receivedEvents.addAll(events);
                       return CompletableFuture.completedFuture(null);
                   }))
                   .when()
                   .events(
                           studentNameChangedEventMessage("my-studentId-1", "name-1", 1),
                           studentNameChangedEventMessage("my-studentId-1", "name-2", 2)
                   )
                   .then()
                   .success()
                   .noCommands();

            // then
            assertEquals(2, receivedEvents.size());
        }

        @Test
        void whenSingleEventWithEventMessageThenSuccess() {
            // given
            var configurer = messagingConfigurer();
            var receivedEvents = new ArrayList<>();
            var fixture = AxonTestFixture.with(configurer);

            // when
            fixture.given()
                   .execute(c -> c.getComponent(SubscribableEventSource.class).subscribe((events, context) -> {
                       receivedEvents.addAll(events);
                       return CompletableFuture.completedFuture(null);
                   }))
                   .when()
                   .event(studentNameChangedEventMessage("my-studentId-1", "name-1", 1))
                   .then()
                   .success()
                   .noCommands();

            // then
            assertEquals(1, receivedEvents.size());
            var receivedEvent = (EventMessage) receivedEvents.getFirst();
            assertEquals("my-studentId-1", ((StudentNameChangedEvent) receivedEvent.payload()).id());
        }

        @Test
        void whenSingleEventWithMapMetadataThenSuccess() {
            // given
            var configurer = messagingConfigurer();
            var receivedEvents = new ArrayList<>();
            var fixture = AxonTestFixture.with(configurer);

            // when
            fixture.given()
                   .execute(c -> c.getComponent(SubscribableEventSource.class).subscribe((events, context) -> {
                       receivedEvents.addAll(events);
                       return CompletableFuture.completedFuture(null);
                   }))
                   .when()
                   .event(new StudentNameChangedEvent("my-studentId-1", "name-1", 1), Map.of("key", "value"))
                   .then()
                   .success()
                   .noCommands();

            // then
            assertEquals(1, receivedEvents.size());
            var receivedEvent = (EventMessage) receivedEvents.getFirst();
            assertEquals("value", receivedEvent.metadata().get("key"));
        }

        @Test
        void whenEventMessageWithAdditionalMetadataThenMetadataIsMerged() {
            // given
            var configurer = messagingConfigurer();
            var receivedEvents = new ArrayList<EventMessage>();
            var fixture = AxonTestFixture.with(configurer);

            var originalEventMessage = new GenericEventMessage(
                    new MessageType(StudentNameChangedEvent.class),
                    new StudentNameChangedEvent("my-studentId-1", "name-1", 1),
                    Metadata.with("original-key", "original-value")
            );

            // when
            fixture.given()
                   .execute(c -> c.getComponent(SubscribableEventSource.class).subscribe((events, context) -> {
                       receivedEvents.addAll(events);
                       return CompletableFuture.completedFuture(null);
                   }))
                   .when()
                   .event(originalEventMessage, Metadata.with("additional-key", "additional-value"))
                   .then()
                   .success()
                   .noCommands();

            // then - verify metadata was merged
            assertEquals(1, receivedEvents.size());
            var receivedEvent = receivedEvents.getFirst();
            assertEquals("original-value", receivedEvent.metadata().get("original-key"));
            assertEquals("additional-value", receivedEvent.metadata().get("additional-key"));
        }

        @Nested
        class AssertionErrors {

            @Test
            void ifExpectSuccessButException() {
                var configurer = messagingConfigurer();

                var fixture = AxonTestFixture.with(configurer);

                var assertionError = assertThrows(AxonAssertionError.class, () ->
                        fixture.given()
                               .execute(c -> c.getComponent(SubscribableEventSource.class).subscribe((events, context) -> {
                                   throw new RuntimeException("Simulated failure");
                               }))
                               .when()
                               .event(new StudentNameChangedEvent("my-studentId-1", "name-1", 1))
                               .then()
                               .success()
                );
                assertTrue(assertionError.getMessage().contains("The message handler threw an unexpected exception"));
            }

            @Test
            void ifExpectExceptionButSuccess() {
                var configurer = messagingConfigurer();

                var fixture = AxonTestFixture.with(configurer);

                var assertionError = assertThrows(AxonAssertionError.class, () ->
                        fixture.given()
                               .execute(c -> c.getComponent(SubscribableEventSource.class).subscribe((events, context) -> {
                                   // No-op handler - succeeds without throwing
                                   return CompletableFuture.completedFuture(null);
                               }))
                               .when()
                               .event(new StudentNameChangedEvent("my-studentId-1", "name-1", 1))
                               .then()
                               .exception(RuntimeException.class)
                );
                assertTrue(assertionError.getMessage()
                                         .contains(
                                                 "The message handler returned normally, but an exception was expected"));
            }

            @Test
            void whenEventHandlerFailsThenException() {
                var configurer = messagingConfigurer();

                var fixture = AxonTestFixture.with(configurer);

                var assertionError = assertThrows(AxonAssertionError.class, () ->
                        fixture.given()
                               .execute(c -> c.getComponent(SubscribableEventSource.class).subscribe((events, context) -> {
                                   throw new IllegalStateException("Simulated failure");
                               }))
                               .when()
                               .event(new StudentNameChangedEvent("my-studentId-1", "name-1", 1))
                               .then()
                               .exception(IllegalStateException.class, "Simulated exception")
                );
                assertTrue(assertionError.getMessage().contains(
                        "Expected class java.lang.IllegalStateException with message 'Simulated exception' but got java.lang.IllegalStateException: Simulated failure"));
            }
        }
    }

    @Nested
    class AndChaining {

        @Test
        void whenThenAndWhenThen() {
            var configurer = messagingConfigurer();
            registerChangeStudentNameHandlerReturnsSingle(configurer);

            var fixture = AxonTestFixture.with(configurer);

            fixture.given()
                   .command(new ChangeStudentNameCommand("my-studentId-1", "name-1"))
                   .when()
                   .command(new ChangeStudentNameCommand("my-studentId-1", "name-2"))
                   .then()
                   .resultMessagePayload(new AxonTestFixtureMessagingTest.CommandResult("Result name-2"))
                   .events(studentNameChangedEvent("my-studentId-1", "name-2", 1))
                   .and()
                   .when()
                   .command(new ChangeStudentNameCommand("my-studentId-1", "name-3"))
                   .then()
                   .success()
                   .resultMessagePayload(new AxonTestFixtureMessagingTest.CommandResult("Result name-3"));
        }

        @Test
        void chainSameFixturePhaseTwice() {
            var configurer = messagingConfigurer();
            registerChangeStudentNameHandlerReturnsSingle(configurer);

            var fixture = AxonTestFixture.with(configurer);

            var then = fixture.given()
                              .command(new ChangeStudentNameCommand("my-studentId-1", "name-1"))
                              .when()
                              .command(new ChangeStudentNameCommand("my-studentId-1", "name-2"))
                              .then()
                              .resultMessagePayload(new AxonTestFixtureMessagingTest.CommandResult("Result name-2"))
                              .events(studentNameChangedEvent("my-studentId-1", "name-2", 1));

            then.and()
                .when()
                .command(new ChangeStudentNameCommand("my-studentId-1", "name-3"))
                .then()
                .success()
                .events(studentNameChangedEvent("my-studentId-1", "name-3", 1));

            then.and()
                .when()
                .command(new ChangeStudentNameCommand("my-studentId-1", "name-3"))
                .then()
                .success()
                .events(studentNameChangedEvent("my-studentId-1", "name-3", 1));
        }
    }

    @Nested
    class Customized {

        @Test
        void setupRegisterIgnoredFieldThenShouldPassWithDifferentNameInExpectedEvents() {
            var configurer = messagingConfigurer();
            registerChangeStudentNameHandlerReturnsEmpty(configurer);

            var fixture = AxonTestFixture.with(
                    configurer,
                    c -> c.registerIgnoredField(StudentNameChangedEvent.class, "name")
            );

            fixture.given()
                   .noPriorActivity()
                   .when()
                   .command(new ChangeStudentNameCommand("my-studentId-1", "name-1"))
                   .then()
                   .events(studentNameChangedEvent("my-studentId-1", "another-name", 1));
        }
    }

    private static GenericEventMessage studentNameChangedEventMessage(
            String id,
            String name,
            int change
    ) {
        return new GenericEventMessage(
                new MessageType(StudentNameChangedEvent.class),
                studentNameChangedEvent(id, name, change)
        );
    }

    private static StudentNameChangedEvent studentNameChangedEvent(String id, String name, int change) {
        return new StudentNameChangedEvent(id, name, change);
    }

    record CommandResult(String message, String metadataSample) {

        CommandResult(String message) {
            this(message, null);
        }
    }

    private static void registerChangeStudentNameHandlerReturnsEmpty(MessagingConfigurer configurer) {
        configurer.componentRegistry(cr -> cr.registerDecorator(
                CommandBus.class,
                0,
                (c, n, d) -> d.subscribe(
                        new QualifiedName(ChangeStudentNameCommand.class),
                        (command, context) -> {
                            ChangeStudentNameCommand payload = (ChangeStudentNameCommand) command.payload();
                            var eventSink = c.getComponent(EventSink.class);
                            eventSink.publish(context,
                                              studentNameChangedEventMessage(payload.id(), payload.name(), 1));
                            return MessageStream.empty().cast();
                        })
        ));
    }

    private static void registerChangeStudentNameHandlerReturnsSingle(MessagingConfigurer configurer) {
        configurer.componentRegistry(cr -> cr.registerDecorator(
                CommandBus.class,
                0,
                (c, n, d) -> d.subscribe(
                        new QualifiedName(ChangeStudentNameCommand.class),
                        (command, context) -> {
                            ChangeStudentNameCommand payload = (ChangeStudentNameCommand) command.payload();
                            var metadataSample = command.metadata().get("sample");
                            var eventSink = c.getComponent(EventSink.class);
                            eventSink.publish(context, studentNameChangedEventMessage(payload.id(), payload.name(), 1));
                            var resultMessage = new GenericCommandResultMessage(
                                    new MessageType(CommandResult.class),
                                    new CommandResult("Result " + payload.name(), metadataSample));
                            return MessageStream.just(resultMessage);
                        })
        ));
    }

    private static MessagingConfigurer messagingConfigurer() {
        return MessagingConfigurer.create()
                                  .componentRegistry(ComponentRegistry::disableEnhancerScanning);
    }
}