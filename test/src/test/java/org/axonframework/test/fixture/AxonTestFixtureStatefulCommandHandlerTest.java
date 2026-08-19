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

import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.commandhandling.SimpleCommandHandlingComponent;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.eventhandling.SimpleEventBus;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.axonframework.messaging.eventhandling.EventSink;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.eventsourcing.EventSourcedEntityFactory;
import org.axonframework.eventsourcing.EventSourcingRepository;
import org.axonframework.eventsourcing.eventstore.AnnotationBasedTagResolver;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.eventsourcing.eventstore.StorageEngineBackedEventStore;
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.modelling.annotation.AnnotationBasedEntityEvolvingComponent;
import org.axonframework.modelling.SimpleStateManager;
import org.axonframework.modelling.StateManager;
import org.axonframework.test.fixture.sampledomain.ChangeStudentNameCommand;
import org.axonframework.test.fixture.sampledomain.Student;
import org.axonframework.test.fixture.sampledomain.StudentNameChangedEvent;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class AxonTestFixtureStatefulCommandHandlerTest {

    @Test
    void givenEventsWhenCommandThenNoEvents() {
        var configurer = MessagingConfigurer.create();
        registerSampleStatefulCommandHandler(configurer);

        var fixture = AxonTestFixture.with(configurer);

        var studentNameChanged = studentNameChangedEventMessage("my-studentId-1", "name-1", 1);
        var changeToTheSameName = new ChangeStudentNameCommand("my-studentId-1", "name-1");
        fixture.given()
               .events(studentNameChanged)
               .when()
               .command(changeToTheSameName)
               .then()
               .noEvents();
    }

    @Test
    void givenEventsWhenCommandThenNoEventsConsumer() {
        var configurer = MessagingConfigurer.create();
        registerSampleStatefulCommandHandler(configurer);

        var fixture = AxonTestFixture.with(configurer);

        var studentNameChanged = studentNameChangedEventMessage("my-studentId-1", "name-1", 1);
        var changeToTheSameName = new ChangeStudentNameCommand("my-studentId-1", "name-1");
        fixture.given()
               .events(studentNameChanged)
               .when()
               .command(changeToTheSameName)
               .then()
               .noEvents()
               .eventsSatisfy(events -> assertTrue(events.isEmpty()))
               .eventsMatch(List::isEmpty)
               .success();
    }

    @Test
    void givenEventsWhenCommandThenEvents() {
        var configurer = MessagingConfigurer.create();
        registerSampleStatefulCommandHandler(configurer);

        var fixture = AxonTestFixture.with(configurer);

        var studentNameChanged = studentNameChangedEventMessage("my-studentId-1", "name-1", 1);
        var changeToTheAnotherName = new ChangeStudentNameCommand("my-studentId-1", "name-2");
        fixture.given()
               .events(studentNameChanged)
               .when()
               .command(changeToTheAnotherName)
               .then()
               .events(new StudentNameChangedEvent("my-studentId-1", "name-2", 2));
    }


    @Test
    void givenEventAndCommandWhenCommandThenEvents() {
        var configurer = MessagingConfigurer.create();
        registerSampleStatefulCommandHandler(configurer);

        var fixture = AxonTestFixture.with(configurer);

        fixture.given()
               .event(new StudentNameChangedEvent("my-studentId-1", "name-1", 1))
               .command(new ChangeStudentNameCommand("my-studentId-1", "name-2"))
               .when()
               .command(new ChangeStudentNameCommand("my-studentId-1", "name-3"))
               .then()
               .events(new StudentNameChangedEvent("my-studentId-1", "name-3", 3));
    }

    @Test
    void givenMultipleEventsWhenCommandThenEvents() {
        var configurer = MessagingConfigurer.create();
        registerSampleStatefulCommandHandler(configurer);

        var fixture = AxonTestFixture.with(configurer);

        fixture.given()
               .event(new StudentNameChangedEvent("my-studentId-1", "name-1", 1))
               .event(new StudentNameChangedEvent("my-studentId-1", "name-2", 2))
               .when()
               .command(new ChangeStudentNameCommand("my-studentId-1", "name-3"))
               .then()
               .events(new StudentNameChangedEvent("my-studentId-1", "name-3", 3));
    }

    @Test
    void givenEventsListWhenCommandThenEvents() {
        var configurer = MessagingConfigurer.create();
        registerSampleStatefulCommandHandler(configurer);

        var fixture = AxonTestFixture.with(configurer);

        var givenEventsList = List.of(
                new StudentNameChangedEvent("my-studentId-1", "name-1", 1),
                new StudentNameChangedEvent("my-studentId-1", "name-2", 2));
        fixture.given()
               .events(givenEventsList)
               .when()
               .command(new ChangeStudentNameCommand("my-studentId-1", "name-3"))
               .then()
               .events(new StudentNameChangedEvent("my-studentId-1", "name-3", 3));
    }

    @Test
    void givenCommandsListWhenCommandThenEvents() {
        var configurer = MessagingConfigurer.create();
        registerSampleStatefulCommandHandler(configurer);

        var fixture = AxonTestFixture.with(configurer);

        var givenCommandsList = List.of(
                new ChangeStudentNameCommand("my-studentId-1", "name-1"),
                new ChangeStudentNameCommand("my-studentId-1", "name-2"));
        fixture.given()
               .commands(givenCommandsList)
               .when()
               .command(new ChangeStudentNameCommand("my-studentId-1", "name-3"))
               .then()
               .events(new StudentNameChangedEvent("my-studentId-1", "name-3", 3));
    }

    @Test
    void givenEventsWhenCommandThenExpectEvents() {
        var configurer = MessagingConfigurer.create();
        registerSampleStatefulCommandHandler(configurer);

        var fixture = AxonTestFixture.with(configurer);

        fixture.given()
               .noPriorActivity()
               .when()
               .command(new ChangeStudentNameCommand("my-studentId-1", "name-1"))
               .then()
               .events(new StudentNameChangedEvent("my-studentId-1", "name-1", 1));
    }

    @Nested
    class AndChaining {

        @Test
        void givenEventAndMultipleCommandsWhenCommandThenEvents() {
            var configurer = MessagingConfigurer.create();
            registerSampleStatefulCommandHandler(configurer);

            var fixture = AxonTestFixture.with(configurer);

            fixture.given()
                   .event(new StudentNameChangedEvent("my-studentId-1", "name-1", 1))
                   .command(new ChangeStudentNameCommand("my-studentId-1", "name-2"))
                   .when()
                   .command(new ChangeStudentNameCommand("my-studentId-1", "name-3"))
                   .then()
                   .events(new StudentNameChangedEvent("my-studentId-1", "name-3", 3))
                   .and()
                   .when()
                   .command(new ChangeStudentNameCommand("my-studentId-1", "name-4"))
                   .then()
                   .events(new StudentNameChangedEvent("my-studentId-1", "name-4", 4));
        }

        @Test
        void chainSameFixturePhaseTwice() {
            var configurer = MessagingConfigurer.create();
            registerSampleStatefulCommandHandler(configurer);

            var fixture = AxonTestFixture.with(configurer);

            var then = fixture.given()
                              .event(new StudentNameChangedEvent("my-studentId-1", "name-1", 1))
                              .command(new ChangeStudentNameCommand("my-studentId-1", "name-2"))
                              .when()
                              .command(new ChangeStudentNameCommand("my-studentId-1", "name-3"))
                              .then()
                              .events(new StudentNameChangedEvent("my-studentId-1", "name-3", 3));

            then.and()
                .when()
                .command(new ChangeStudentNameCommand("my-studentId-1", "name-4"))
                .then()
                .events(new StudentNameChangedEvent("my-studentId-1", "name-4", 4));

            then.and()
                .when()
                .command(new ChangeStudentNameCommand("my-studentId-1", "name-4"))
                .then()
                .noEvents();
        }
    }

    private static void registerSampleStatefulCommandHandler(MessagingConfigurer configurer) {
        configurer.componentRegistry(cr -> cr
                .registerComponent(
                        StateManager.class,
                        c -> {
                            var repository = new EventSourcingRepository<>(
                                    String.class,
                                    Student.class,
                                    c.getComponent(EventStore.class),
                                    EventSourcedEntityFactory.fromIdentifier(Student::new),
                                    (id, context) -> EventCriteria.havingTags("Student", id),
                                    new AnnotationBasedEntityEvolvingComponent<>(
                                            Student.class,
                                            c.getComponent(EventConverter.class),
                                            c.getComponent(MessageTypeResolver.class)
                                    )
                            );
                            return SimpleStateManager.named("testfixture")
                                                     .register(repository);
                        })
                .registerComponent(EventStore.class,
                                   c -> new StorageEngineBackedEventStore(new InMemoryEventStorageEngine(),
                                                             new SimpleEventBus(),
                                                             new AnnotationBasedTagResolver()))
                .registerComponent(EventSink.class,
                                   c -> c.getComponent(EventStore.class))
                .registerDecorator(CommandBus.class, 50, (c, name, delegate) -> {
                    var statefulCommandHandler = SimpleCommandHandlingComponent
                            .create("mystatefulCH")
                            .subscribe(
                                    new QualifiedName(ChangeStudentNameCommand.class),
                                    (cmd, ctx) -> {
                                        var sm = ctx.component(StateManager.class);
                                        ChangeStudentNameCommand payload = (ChangeStudentNameCommand) cmd.payload();
                                        var student = sm.loadEntity(Student.class, payload.id(), ctx).join();
                                        if (!Objects.equals(student.getName(), payload.name())) {
                                            var eventSink = c.getComponent(EventSink.class);
                                            eventSink.publish(
                                                    ctx,
                                                    studentNameChangedEventMessage(payload.id(),
                                                                                   payload.name(),
                                                                                   student.getChanges() + 1)
                                            );
                                        }
                                        return MessageStream.empty().cast();
                                    });
                    delegate.subscribe(statefulCommandHandler);

                    return delegate;
                })
        );
    }

    private static GenericEventMessage studentNameChangedEventMessage(
            String id,
            String name,
            int change
    ) {
        return new GenericEventMessage(new MessageType(StudentNameChangedEvent.class),
                                         new StudentNameChangedEvent(id, name, change));
    }
}
