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

import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.integrationtests.testsuite.student.commands.EnrollStudentToCourseCommand;
import org.axonframework.integrationtests.testsuite.student.commands.UnenrollStudentFromCourseCommand;
import org.axonframework.integrationtests.testsuite.student.events.StudentEnrolledEvent;
import org.axonframework.integrationtests.testsuite.student.events.StudentUnenrolledEvent;
import org.axonframework.integrationtests.testsuite.student.state.CourseAppendSourceCriteria;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.StateManager;
import org.axonframework.modelling.annotation.InjectEntity;
import org.axonframework.modelling.configuration.EntityModule;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests validating Dynamic Consistency Boundaries (DCB) using
 * {@link org.axonframework.eventsourcing.annotation.SourceCriteriaBuilder} and
 * {@link org.axonframework.eventsourcing.annotation.AppendCriteriaBuilder} annotations.
 * <p>
 * These tests demonstrate that:
 * <ul>
 *     <li>Source criteria correctly loads both event types (enroll and unenroll) to calculate state</li>
 *     <li>Append criteria only checks conflicts on enrollment events</li>
 *     <li>Commands can be handled with entity injection using separate criteria</li>
 * </ul>
 *
 * @author Mateusz Nowak
 * @since 5.0.0
 */
class SeparateCriteriaAnnotationsIT extends AbstractCommandHandlingStudentIT {

    @Override
    protected EventSourcingConfigurer testSuiteConfigurer(EventSourcingConfigurer configurer) {
        EntityModule<String, CourseAppendSourceCriteria> courseAppendSourceCriteriaEntity =
                EventSourcedEntityModule.autodetected(String.class, CourseAppendSourceCriteria.class);
        configurer.componentRegistry(cr -> cr.registerModule(courseAppendSourceCriteriaEntity));

        // Register command handlers for CourseAppendSourceCriteria
        registerCommandHandlers(handlerPhase -> handlerPhase.annotatedCommandHandlingComponent(
                c -> new CourseCommandHandler()
        ));

        return super.testSuiteConfigurer(configurer);
    }

    @Nested
    class SourceCriteriaBehavior {

        @Test
        void shouldSourceBothEnrollAndUnenrollEvents() {
            // given
            startApp();
            String courseId = createId("course-dcb");
            String student1 = createId("student-1");
            String student2 = createId("student-2");

            storeEvent(StudentEnrolledEvent.class, new StudentEnrolledEvent(student1, courseId));
            storeEvent(StudentEnrolledEvent.class, new StudentEnrolledEvent(student2, courseId));
            storeEvent(StudentUnenrolledEvent.class, new StudentUnenrolledEvent(student1, courseId));

            // when
            CourseAppendSourceCriteria course = loadCourse(courseId);

            // then - state reflects both enroll and unenroll
            assertThat(course.getStudentsEnrolled())
                    .containsExactly(student2);
        }

        @Test
        void shouldSourceAllEventsInCorrectOrder() {
            // given
            startApp();
            String courseId = createId("course-dcb");
            String student1 = createId("student-1");
            String student2 = createId("student-2");
            String student3 = createId("student-3");

            storeEvent(StudentEnrolledEvent.class, new StudentEnrolledEvent(student1, courseId));
            storeEvent(StudentEnrolledEvent.class, new StudentEnrolledEvent(student2, courseId));
            storeEvent(StudentEnrolledEvent.class, new StudentEnrolledEvent(student3, courseId));
            storeEvent(StudentUnenrolledEvent.class, new StudentUnenrolledEvent(student1, courseId));
            storeEvent(StudentUnenrolledEvent.class, new StudentUnenrolledEvent(student3, courseId));

            // when
            CourseAppendSourceCriteria course = loadCourse(courseId);

            // then - only student2 should remain enrolled
            assertThat(course.getStudentsEnrolled())
                    .containsExactly(student2);
        }
    }

    @Nested
    class CommandHandlerBehavior {

        @Test
        void shouldEnrollStudentViaCourseCommandHandler() {
            // given
            startApp();
            String courseId = createId("course-dcb");
            String student1 = createId("student-1");

            // Pre-populate course with initial enrollment
            storeEvent(StudentEnrolledEvent.class, new StudentEnrolledEvent(student1, courseId));

            // when - enroll another student via command handler
            String student2 = createId("student-2");
            sendCommand(new EnrollStudentToCourseCommand(student2, courseId));

            // then
            CourseAppendSourceCriteria course = loadCourse(courseId);
            assertThat(course.getStudentsEnrolled())
                    .containsExactlyInAnyOrder(student1, student2);
        }

        @Test
        void shouldUnenrollStudentViaCourseCommandHandler() {
            // given
            startApp();
            String courseId = createId("course-dcb");
            String student1 = createId("student-1");
            String student2 = createId("student-2");

            // Pre-populate course with enrollments
            storeEvent(StudentEnrolledEvent.class, new StudentEnrolledEvent(student1, courseId));
            storeEvent(StudentEnrolledEvent.class, new StudentEnrolledEvent(student2, courseId));

            // when - unenroll student1 via command handler
            sendCommand(new UnenrollStudentFromCourseCommand(student1, courseId));

            // then
            CourseAppendSourceCriteria course = loadCourse(courseId);
            assertThat(course.getStudentsEnrolled())
                    .containsExactly(student2);
        }

        @Test
        void shouldHandleMultipleEnrollmentsSequentially() {
            // given
            startApp();
            String courseId = createId("course-dcb");
            String student1 = createId("student-1");
            String student2 = createId("student-2");
            String student3 = createId("student-3");

            // when - enroll students one by one
            sendCommand(new EnrollStudentToCourseCommand(student1, courseId));
            sendCommand(new EnrollStudentToCourseCommand(student2, courseId));
            sendCommand(new EnrollStudentToCourseCommand(student3, courseId));

            // then
            CourseAppendSourceCriteria course = loadCourse(courseId);
            assertThat(course.getStudentsEnrolled())
                    .containsExactlyInAnyOrder(student1, student2, student3);
        }

        @Test
        void shouldHandleEnrollAndUnenrollSequentially() {
            // given
            startApp();
            String courseId = createId("course-dcb");
            String student1 = createId("student-1");
            String student2 = createId("student-2");

            // when - enroll, then unenroll, then enroll again
            sendCommand(new EnrollStudentToCourseCommand(student1, courseId));
            sendCommand(new EnrollStudentToCourseCommand(student2, courseId));
            sendCommand(new UnenrollStudentFromCourseCommand(student1, courseId));

            // then
            CourseAppendSourceCriteria course = loadCourse(courseId);
            assertThat(course.getStudentsEnrolled())
                    .containsExactly(student2);
        }
    }

    /**
     * Command handler for {@link CourseAppendSourceCriteria} entity.
     * <p>
     * Uses {@link InjectEntity} to inject the entity with separate source and append criteria.
     * The entity is loaded using source criteria (both enroll and unenroll events) and
     * conflict checking uses append criteria (only enroll events).
     */
    static class CourseCommandHandler {

        @CommandHandler
        public void handle(EnrollStudentToCourseCommand command,
                           @InjectEntity CourseAppendSourceCriteria course,
                           EventAppender eventAppender) {
            eventAppender.append(new StudentEnrolledEvent(command.studentId(), command.courseId()));
        }

        @CommandHandler
        public void handle(UnenrollStudentFromCourseCommand command,
                           @InjectEntity CourseAppendSourceCriteria course,
                           EventAppender eventAppender) {
            eventAppender.append(new StudentUnenrolledEvent(command.studentId(), command.courseId()));
        }
    }

    private CourseAppendSourceCriteria loadCourse(String courseId) {
        UnitOfWork uow = unitOfWorkFactory.create();
        return uow.executeWithResult(context -> context.component(StateManager.class)
                        .repository(CourseAppendSourceCriteria.class, String.class)
                        .load(courseId, context)
                        .thenApply(result -> result.entity()))
                .join();
    }
}
