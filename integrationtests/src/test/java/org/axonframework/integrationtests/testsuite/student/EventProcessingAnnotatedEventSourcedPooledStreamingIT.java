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

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorModule;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessor;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.eventsourcing.annotation.reflection.InjectEntityId;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.integrationtests.testsuite.student.commands.SendMaxCoursesNotificationCommand;
import org.axonframework.integrationtests.testsuite.student.events.MaxCoursesNotificationSentEvent;
import org.axonframework.integrationtests.testsuite.student.events.StudentEnrolledEvent;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.modelling.StateManager;
import org.axonframework.modelling.annotation.InjectEntity;
import org.axonframework.modelling.configuration.EntityModule;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class validating the annotated {@link PooledStreamingEventProcessor}
 * used as an automation (a kind of Saga) that keeps the process state using {@link EventSourcedEntityModule} and sends
 * a {@link CommandMessage} if certain business rules are met.
 *
 * @author Mateusz Nowak
 * @since 5.0.0
 */
public abstract class EventProcessingAnnotatedEventSourcedPooledStreamingIT extends AbstractStudentIT {

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

    @EventSourcedEntity(tagKey = "Student")
    record StudentCoursesAutomationState(String studentId, List<String> courses, boolean notified) {

        @EntityCreator
        StudentCoursesAutomationState(@InjectEntityId String studentId) {
            this(studentId, List.of(), false);
        }

        @EventSourcingHandler
        StudentCoursesAutomationState evolve(StudentEnrolledEvent event) {
            var updatedCourses = new ArrayList<>(courses);
            updatedCourses.add(event.courseId());
            return new StudentCoursesAutomationState(studentId, updatedCourses, false);
        }

        @EventSourcingHandler
        StudentCoursesAutomationState evolve(MaxCoursesNotificationSentEvent event) {
            return new StudentCoursesAutomationState(studentId, courses, true);
        }
    }

    static class SendMaxCoursesNotificationCommandHandler {

        @CommandHandler
        public MessageStream.Single<?> decide(
                SendMaxCoursesNotificationCommand command,
                @InjectEntity(idProperty = "studentId") StudentCoursesAutomationState state,
                ProcessingContext context
        ) {
            var studentId = command.studentId();
            var canNotify = state != null && !state.notified();
            if (canNotify) {
                var eventAppender = EventAppender.forContext(context);
                eventAppender.append(new MaxCoursesNotificationSentEvent(studentId));
            }
            return MessageStream.just(SUCCESSFUL_COMMAND_RESULT);
        }
    }

    static class WhenStudentEnrolledToMaxCoursesThenSendNotificationAutomation {

        @EventHandler
        public MessageStream.Empty<?> react(
                StudentEnrolledEvent event,
                @InjectEntity(idProperty = "studentId") StudentCoursesAutomationState state,
                ProcessingContext context
        ) {
            var studentId = event.studentId();
            var readModel = state != null ? state : new StudentCoursesAutomationState(studentId);
            if (readModel.courses.size() >= 3 && !readModel.notified()) {
                var commandGateway = context.component(CommandGateway.class);
                commandGateway.send(new SendMaxCoursesNotificationCommand(studentId), context);
            }
            return MessageStream.empty();
        }
    }

    protected void verifyNotificationSentTo(String studentId) {
        UnitOfWork uow = unitOfWorkFactory.create();
        assertTrue(uow.executeWithResult(context -> context.component(StateManager.class)
            .repository(StudentCoursesAutomationState.class, String.class)
            .load(studentId, context)
            .thenApply(student -> student.entity().notified())).join()
        );
    }

    @Override
    protected EventSourcingConfigurer testSuiteConfigurer(EventSourcingConfigurer configurer) {
        configureEntityAndCommandHandler(configurer);
        return configureProcessorWithAnnotatedEventHandlingComponent(configurer);
    }

    private static EventSourcingConfigurer configureProcessorWithAnnotatedEventHandlingComponent(
            EventSourcingConfigurer configurer) {
        var studentRegisteredCoursesProcessor = EventProcessorModule
                .pooledStreaming("when-student-enrolled-to-max-courses-then-send-notification")
                .eventHandlingComponents(components -> components.autodetected(
                        "maxCoursesAutomation",
                        cfg -> new WhenStudentEnrolledToMaxCoursesThenSendNotificationAutomation()
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
                EventSourcedEntityModule.autodetected(String.class, StudentCoursesAutomationState.class);
        configurer.componentRegistry(cr -> cr.registerModule(studentCoursesEntity));

        CommandHandlingModule sendMaxCoursesNotificationCommandHandler = CommandHandlingModule
                .named("send-max-courses-notification-command-handler")
                .commandHandlers()
                .autodetectedCommandHandlingComponent(cfg -> new SendMaxCoursesNotificationCommandHandler()).build();

        configurer.registerCommandHandlingModule(sendMaxCoursesNotificationCommandHandler);
    }
}
