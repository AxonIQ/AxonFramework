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

import org.axonframework.integrationtests.testsuite.student.commands.AssignMentorCommand;
import org.axonframework.integrationtests.testsuite.student.commands.EnrollStudentToCourseCommand;
import org.axonframework.integrationtests.testsuite.student.events.MentorAssignedToStudentEvent;
import org.axonframework.integrationtests.testsuite.student.events.StudentEnrolledEvent;
import org.axonframework.integrationtests.testsuite.student.state.Course;
import org.axonframework.integrationtests.testsuite.student.state.Student;
import org.axonframework.messaging.commandhandling.CommandExecutionException;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.EntityIdResolutionException;
import org.axonframework.modelling.EntityIdResolver;
import org.axonframework.modelling.StateManager;
import org.axonframework.modelling.annotation.InjectEntity;
import org.axonframework.modelling.repository.ManagedEntity;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests whether stateful command handling components can process commands that target multiple entities at the same
 * time.
 *
 * @author Mitchell Herrijgers
 */
public abstract class MultiEntityCommandHandlingComponentIT extends AbstractCommandHandlingStudentIT {
    private final String student1 = createId("student-1");
    private final String student2 = createId("student-2");
    private final String student3 = createId("student-3");
    private final String student4 = createId("student-4");
    private final String student5 = createId("student-5");
    private final String course1 = createId("course-1");
    private final String course2 = createId("course-2");

    @Test
    void canCombineModelsInAnnotatedCommandHandlerViaStateManagerParameter() {
        registerCommandHandlers(handlerPhase -> handlerPhase.autodetectedCommandHandlingComponent(
                c -> new MultiModelAnnotatedCommandHandler()
        ));
        startApp();

        enrollStudentToCourse(student1, course1);

        verifyStudentEnrolledInCourse(student1, course1);
    }

    @Test
    void canCombineStatesInLambdaCommandHandlerViaStateManagerParameter() {
        registerCommandHandlers(handlerPhase -> handlerPhase.commandHandler(
                new QualifiedName(EnrollStudentToCourseCommand.class),
                c -> (command, context) -> {
                    EventAppender eventAppender = EventAppender.forContext(context);
                    EnrollStudentToCourseCommand payload =
                            command.payloadAs(EnrollStudentToCourseCommand.class);
                    StateManager state = context.component(StateManager.class);
                    Student student = state.loadEntity(Student.class, payload.studentId(), context).join();
                    Course course = state.loadEntity(Course.class, payload.courseId(), context).join();

                    if (student.getCoursesEnrolled().size() > 2) {
                        throw new IllegalArgumentException("Student already enrolled in 3 courses");
                    }

                    if (course.getStudentsEnrolled().size() > 2) {
                        throw new IllegalArgumentException("Course already has 3 students");
                    }
                    eventAppender.append(new StudentEnrolledEvent(payload.studentId(), payload.courseId()));
                    return MessageStream.just(SUCCESSFUL_COMMAND_RESULT).cast();
                }
        ));
        startApp();

        // First student
        enrollStudentToCourse(student2, course1);
        verifyStudentEnrolledInCourse(student2, course1);

        // Second student
        enrollStudentToCourse(student3, course1);
        verifyStudentEnrolledInCourse(student3, course1);
        verifyStudentEnrolledInCourse(student2, course1);

        // Third and last possible student
        enrollStudentToCourse(student4, course1);
        verifyStudentEnrolledInCourse(student4, course1);
        verifyStudentEnrolledInCourse(student3, course1);
        verifyStudentEnrolledInCourse(student2, course1);

        // Fourth can still enroll for other course
        enrollStudentToCourse(student4, course2);
        verifyStudentEnrolledInCourse(student4, course2);

        // But five can not enroll for the first course
        assertThatThrownBy(() -> enrollStudentToCourse(student5, course1))
                .hasMessageContaining("Course already has 3 students");
    }

    @Test
    void canHandleCommandThatTargetsMultipleOfTheSameModelInSameAnnotatedCommandHandler() {
        registerCommandHandlers(handlerPhase -> handlerPhase.autodetectedCommandHandlingComponent(
                c -> new MultiModelAnnotatedCommandHandler()
        ));
        startApp();

        // Can assign mentor to mentee
        sendCommand(new AssignMentorCommand(student1, student2));

        // But not a second time
        assertThatThrownBy(() -> sendCommand(new AssignMentorCommand(student1, student3)))
                .hasMessageContaining("Mentor already assigned to a mentee");
    }

    private static class MultiModelAnnotatedCommandHandler {

        @SuppressWarnings("unused")
        @CommandHandler
        public void handle(EnrollStudentToCourseCommand command,
                           @InjectEntity(idProperty = "studentId") Student student,
                           @InjectEntity(idProperty = "courseId") Course course,
                           EventAppender eventAppender,
                           ProcessingContext context) {
            if (student.getCoursesEnrolled().size() > 2) {
                throw new IllegalArgumentException("Student already enrolled in 3 courses");
            }

            if (course.getStudentsEnrolled().size() > 2) {
                throw new IllegalArgumentException("Course already has 3 students");
            }

            eventAppender.append(new StudentEnrolledEvent(command.studentId(), command.courseId()));

            assertTrue(student.getCoursesEnrolled().contains(command.courseId()));
            assertTrue(course.getStudentsEnrolled().contains(command.studentId()));
        }

        @SuppressWarnings("unused")
        @CommandHandler
        public void handle(AssignMentorCommand command,
                           @InjectEntity(idResolver = MentorIdResolver.class) Student mentor,
                           @InjectEntity(idProperty = "menteeId") ManagedEntity<?, Student> mentee,
                           EventAppender eventAppender,
                           ProcessingContext context) {
            if (mentor.getMenteeId() != null) {
                throw new IllegalArgumentException("Mentor already assigned to a mentee");
            }
            if (mentee.entity().getMentorId() != null) {
                throw new IllegalArgumentException("Mentee already has a mentor");
            }

            eventAppender.append(
                    new MentorAssignedToStudentEvent(mentor.getId(), mentee.entity().getId())
            );
        }

        public static class MentorIdResolver implements EntityIdResolver<String> {

            @Override
            public @NonNull String resolve(@NonNull Message command, @NonNull ProcessingContext context) throws EntityIdResolutionException {
                //noinspection unused
                if (command.payload() instanceof AssignMentorCommand(String studentId, String mentorId)) {
                    return studentId;
                }
                throw new EntityIdResolutionException(command.payloadType(), List.of());
            }
        }
    }

    private void verifyStudentEnrolledInCourse(String id, String courseId) {
        UnitOfWork uow = unitOfWorkFactory.create();
        uow.executeWithResult(context -> context.component(StateManager.class)
                                                .repository(Student.class, String.class)
                                                .load(id, context)
                                                .thenAccept(student -> assertTrue(student.entity()
                                                                                         .getCoursesEnrolled()
                                                                                         .contains(courseId)))
                                                .thenCompose(v -> context.component(StateManager.class)
                                                                         .repository(Course.class,
                                                                                     String.class)
                                                                         .load(courseId, context))
                                                .thenAccept(course -> assertTrue(course.entity()
                                                                                       .getStudentsEnrolled()
                                                                                       .contains(id))))
           .join();
    }
}