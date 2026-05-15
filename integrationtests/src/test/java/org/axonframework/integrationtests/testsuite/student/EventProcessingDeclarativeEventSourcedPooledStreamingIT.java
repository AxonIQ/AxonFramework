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

package org.axonframework.integrationtests.testsuite.student;

import org.axonframework.eventsourcing.EventSourcedEntityFactory;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.integrationtests.testsuite.student.commands.SendMaxCoursesNotificationCommand;
import org.axonframework.integrationtests.testsuite.student.events.MaxCoursesNotificationSentEvent;
import org.axonframework.integrationtests.testsuite.student.events.StudentEnrolledEvent;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.conversion.MessageConverter;
import org.axonframework.messaging.core.sequencing.SequentialPolicy;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.SimpleEventHandlingComponent;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorModule;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessor;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.axonframework.modelling.EntityEvolver;
import org.axonframework.modelling.StateManager;
import org.axonframework.modelling.configuration.EntityModule;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class validating the declarative {@link PooledStreamingEventProcessor} used as an automation (a kind of Saga)
 * that keeps the process state using {@link EventSourcedEntityModule} and sends a {@link CommandMessage} if certain
 * business rules are met.
 *
 * @author Mateusz Nowak
 * @since 5.0.0
 */
public abstract class EventProcessingDeclarativeEventSourcedPooledStreamingIT extends AbstractStudentIT {

    @Test
    void whenStudentEnrolled3CoursesThenSendNotificationTest() {
        // given
        startApp();

        // when
        var studentId = UUID.randomUUID().toString();
        studentEnrolledToCourse(studentId, "my-courseId-1");
        studentEnrolledToCourse(studentId, "my-courseId-2");
        studentEnrolledToCourse(studentId, "my-courseId-3");
        studentEnrolledToCourse(studentId, "my-courseId-4");

        // then
        await().atMost(10, TimeUnit.SECONDS)
               .untilAsserted(() -> verifyNotificationSentTo(studentId));
    }

    record StudentCoursesAutomationState(String studentId, List<String> courses, boolean notified) {

        StudentCoursesAutomationState(String studentId) {
            this(studentId, List.of(), false);
        }

        StudentCoursesAutomationState evolve(StudentEnrolledEvent event) {
            var updatedCourses = new ArrayList<>(courses);
            updatedCourses.add(event.courseId());
            return new StudentCoursesAutomationState(studentId, updatedCourses, false);
        }

        StudentCoursesAutomationState evolve(MaxCoursesNotificationSentEvent event) {
            return new StudentCoursesAutomationState(studentId, courses, true);
        }
    }

    static @NonNull EventHandlingComponent whenStudentEnrolledToMaxCoursesThenSendNotificationAutomation() {
        SimpleEventHandlingComponent handlingComponent =
                SimpleEventHandlingComponent.create("studentEnrolledAutomation", SequentialPolicy.INSTANCE);
        handlingComponent.subscribe(
                new QualifiedName(StudentEnrolledEvent.class),
                (event, context) -> {
                    // direct conversion via message-attached converter
                    var studentEnrolled = event.payloadAs(StudentEnrolledEvent.class);
                    var studentId = studentEnrolled.studentId();
                    var state = context.component(StateManager.class);
                    var loadedState = state.loadEntity(StudentCoursesAutomationState.class,
                                                       studentId,
                                                       context).join();
                    var readModel = loadedState != null
                            ? loadedState
                            : new StudentCoursesAutomationState(studentId);
                    if (readModel.courses.size() >= 3) {
                        var commandGateway = context.component(CommandGateway.class);
                        commandGateway.send(new SendMaxCoursesNotificationCommand(studentId), context);
                    }
                    return MessageStream.empty();
                }
        );
        return handlingComponent;
    }

    protected void verifyNotificationSentTo(String studentId) {
        UnitOfWork uow = unitOfWorkFactory.create();
        assertTrue(uow.executeWithResult(
                context -> context.component(StateManager.class)
                                  .repository(StudentCoursesAutomationState.class, String.class)
                                  .load(studentId, context)
                                  .thenApply(student -> student.entity().notified())
        ).join());
    }

    @Override
    protected EventSourcingConfigurer testSuiteConfigurer(EventSourcingConfigurer configurer) {
        configureEntityAndCommandHandler(configurer);
        return configureProcessorWithDeclarativeEventHandlingComponent(configurer);
    }

    private static EventSourcingConfigurer configureProcessorWithDeclarativeEventHandlingComponent(
            EventSourcingConfigurer configurer) {
        var studentRegisteredCoursesProcessor =
                EventProcessorModule
                        .pooledStreaming("when-student-enrolled-to-max-courses-then-send-notification")
                        .eventHandlingComponents(components -> components.declarative(
                                "maxCoursesAutomation",
                                cfg -> whenStudentEnrolledToMaxCoursesThenSendNotificationAutomation()
                        )).notCustomized();
        return configurer.messaging(
                messaging -> messaging.eventProcessing(
                        ep -> ep.pooledStreaming(
                                ps -> ps.processor(studentRegisteredCoursesProcessor)
                        )
                )
        );
    }

    private static void configureEntityAndCommandHandler(EventSourcingConfigurer configurer) {
        EntityModule<String, StudentCoursesAutomationState> studentCoursesEntity =
                EventSourcedEntityModule.declarative(String.class, StudentCoursesAutomationState.class)
                                        .messagingModel((c, model) -> model.entityEvolver(automationStateEvolver())
                                                                           .build())
                                        .entityFactory(c -> EventSourcedEntityFactory.fromIdentifier(
                                                StudentCoursesAutomationState::new))
                                        .criteriaResolver(c -> (id, ctx) -> EventCriteria.havingTags(Tag.of("Student",
                                                                                                            id)))
                                        .build();
        configurer.componentRegistry(cr -> cr.registerModule(studentCoursesEntity));

        CommandHandlingModule sendMaxCoursesNotificationCommandHandler = CommandHandlingModule
                .named("send-max-courses-notification-command-handler")
                .commandHandlers()
                .commandHandler(new QualifiedName(SendMaxCoursesNotificationCommand.class), (c, ctx) -> {
                    var stateManager = ctx.component(StateManager.class);
                    var converter = ctx.component(MessageConverter.class);
                    var command = c.payloadAs(SendMaxCoursesNotificationCommand.class, converter);
                    var studentId = command.studentId();
                    var state = stateManager.loadEntity(StudentCoursesAutomationState.class, studentId, ctx).join();
                    var canNotify = state != null && !state.notified();
                    if (canNotify) {
                        var eventAppender = EventAppender.forContext(ctx);
                        eventAppender.append(new MaxCoursesNotificationSentEvent(studentId));
                    }
                    return MessageStream.just(SUCCESSFUL_COMMAND_RESULT);
                }).build();

        configurer.registerCommandHandlingModule(sendMaxCoursesNotificationCommandHandler);
    }

    private static EntityEvolver<StudentCoursesAutomationState> automationStateEvolver() {
        return (entity, event, context) -> {
            if (event.type().qualifiedName().equals(new QualifiedName(StudentEnrolledEvent.class))) {
                var payload = event.payloadAs(StudentEnrolledEvent.class);
                return entity.evolve(payload);
            }
            if (event.type().qualifiedName().equals(new QualifiedName(MaxCoursesNotificationSentEvent.class))) {
                var payload = event.payloadAs(MaxCoursesNotificationSentEvent.class);
                return entity.evolve(payload);
            }
            return entity;
        };
    }
}
