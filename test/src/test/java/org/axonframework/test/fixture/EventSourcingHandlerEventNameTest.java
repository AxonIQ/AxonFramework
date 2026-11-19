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

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.annotation.Event;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.TargetEntityId;
import org.axonframework.modelling.entity.annotation.EntityMember;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Proves that {@link EventSourcingHandler#eventName()} lets an entity evolve from an event whose name differs from the
 * name of the handler's payload class.
 * <p>
 * The billing module publishes {@link EnrollmentPaid} under the name {@code billing.EnrollmentPaid}. The course entity
 * reads it through its own {@link PaymentReceived} record and only confirms an enrollment once it is paid. Whether the
 * handler ran is therefore observable through the command outcome:
 * <ul>
 *     <li>payment event applied -> the enrollment is paid -> the confirmation succeeds and appends an event;</li>
 *     <li>payment event not applied -> the enrollment is unpaid -> the confirmation is rejected.</li>
 * </ul>
 */
class EventSourcingHandlerEventNameTest {

    private static final String COURSE_ID = "course-1";
    private static final String STUDENT_ID = "student-1";
    private static final String OTHER_STUDENT_ID = "student-2";
    private static final String BILLING_ENROLLMENT_PAID = "billing.EnrollmentPaid";

    // Commands
    record ConfirmEnrollment(@TargetEntityId String courseId, String studentId) {

    }

    // Events
    record CourseCreated(@EventTag String courseId) {

    }

    record StudentEnrolled(@EventTag String courseId, String studentId) {

    }

    record EnrollmentConfirmed(@EventTag String courseId, String studentId) {

    }

    // The billing module's event, published under the name "billing.EnrollmentPaid".
    @Event(namespace = "billing", name = "EnrollmentPaid")
    record EnrollmentPaid(@EventTag String courseId, String studentId) {

    }

    // The course module's local view of the billing event; its own class name resolves to a different event name.
    record PaymentReceived(String courseId, String studentId) {

    }

    private static AxonTestFixture fixture(Class<?> entityType) {
        return AxonTestFixture.with(EventSourcingConfigurer.create().registerEntity(
                EventSourcedEntityModule.autodetected(String.class, entityType)));
    }

    @Nested
    class RootEntity {

        @EventSourcedEntity(tagKey = "courseId")
        static class Course {

            private boolean paid;

            @CommandHandler
            public void handle(ConfirmEnrollment cmd, EventAppender appender) {
                if (!paid) {
                    throw new IllegalStateException("Enrollment not paid");
                }
                appender.append(new EnrollmentConfirmed(cmd.courseId(), cmd.studentId()));
            }

            @EventSourcingHandler
            void on(CourseCreated event) {
                // only creates the entity
            }

            @EventSourcingHandler(eventName = BILLING_ENROLLMENT_PAID)
            void on(PaymentReceived event) {
                this.paid = true;
            }

            @EntityCreator
            protected Course() {
            }
        }

        @Test
        void eventNamedByEventNameEvolvesRootEntity() {
            fixture(Course.class).given()
                     .events(new CourseCreated(COURSE_ID), new EnrollmentPaid(COURSE_ID, STUDENT_ID))
                     .when()
                     .command(new ConfirmEnrollment(COURSE_ID, STUDENT_ID))
                     .then()
                     // the handler ran, so the course is paid and confirms the enrollment
                     .events(new EnrollmentConfirmed(COURSE_ID, STUDENT_ID));
        }

        @Test
        void withoutPaymentEventRootEntityRejectsConfirmation() {
            fixture(Course.class).given()
                     .events(new CourseCreated(COURSE_ID))
                     .when()
                     .command(new ConfirmEnrollment(COURSE_ID, STUDENT_ID))
                     .then()
                     .exception(IllegalStateException.class, "Enrollment not paid");
        }
    }

    @Nested
    class SingleChildEntity {

        @EventSourcedEntity(tagKey = "courseId")
        static class Course {

            @EntityMember
            private Enrollment enrollment;

            @EventSourcingHandler
            void on(CourseCreated event) {
                // only creates the entity
            }

            @EventSourcingHandler
            void on(StudentEnrolled event) {
                this.enrollment = new Enrollment();
            }

            @EntityCreator
            protected Course() {
            }
        }

        static class Enrollment {

            private boolean paid;

            @CommandHandler
            public void handle(ConfirmEnrollment cmd, EventAppender appender) {
                if (!paid) {
                    throw new IllegalStateException("Enrollment not paid");
                }
                appender.append(new EnrollmentConfirmed(cmd.courseId(), cmd.studentId()));
            }

            @EventSourcingHandler(eventName = BILLING_ENROLLMENT_PAID)
            void on(PaymentReceived event) {
                this.paid = true;
            }
        }

        @Test
        void eventNamedByEventNameEvolvesChildEntity() {
            fixture(Course.class).given()
                     .events(new CourseCreated(COURSE_ID),
                             new StudentEnrolled(COURSE_ID, STUDENT_ID),
                             new EnrollmentPaid(COURSE_ID, STUDENT_ID))
                     .when()
                     .command(new ConfirmEnrollment(COURSE_ID, STUDENT_ID))
                     .then()
                     // the child's handler ran, so the enrollment is paid and confirms itself
                     .events(new EnrollmentConfirmed(COURSE_ID, STUDENT_ID));
        }

        @Test
        void withoutPaymentEventChildEntityRejectsConfirmation() {
            fixture(Course.class).given()
                     .events(new CourseCreated(COURSE_ID), new StudentEnrolled(COURSE_ID, STUDENT_ID))
                     .when()
                     .command(new ConfirmEnrollment(COURSE_ID, STUDENT_ID))
                     .then()
                     .exception(IllegalStateException.class, "Enrollment not paid");
        }
    }

    @Nested
    class ListChildEntity {

        @EventSourcedEntity(tagKey = "courseId")
        static class Course {

            @EntityMember(routingKey = "studentId")
            private final List<RoutedEnrollment> enrollments = new ArrayList<>();

            @EventSourcingHandler
            void on(CourseCreated event) {
                // only creates the entity
            }

            @EventSourcingHandler
            void on(StudentEnrolled event) {
                this.enrollments.add(new RoutedEnrollment(event.studentId()));
            }

            @EntityCreator
            protected Course() {
            }
        }

        @Test
        void eventNamedByEventNameEvolvesMatchingListChild() {
            fixture(Course.class).given()
                                 .events(new CourseCreated(COURSE_ID),
                                         new StudentEnrolled(COURSE_ID, STUDENT_ID),
                                         new StudentEnrolled(COURSE_ID, OTHER_STUDENT_ID),
                                         new EnrollmentPaid(COURSE_ID, STUDENT_ID))
                                 .when()
                                 .command(new ConfirmEnrollment(COURSE_ID, STUDENT_ID))
                                 .then()
                                 .events(new EnrollmentConfirmed(COURSE_ID, STUDENT_ID));
        }

        @Test
        void eventNamedByEventNameSkipsListChildWithOtherRoutingValue() {
            fixture(Course.class).given()
                                 .events(new CourseCreated(COURSE_ID),
                                         new StudentEnrolled(COURSE_ID, STUDENT_ID),
                                         new StudentEnrolled(COURSE_ID, OTHER_STUDENT_ID),
                                         new EnrollmentPaid(COURSE_ID, STUDENT_ID))
                                 .when()
                                 // the payment for student-1 leaves student-2 unpaid
                                 .command(new ConfirmEnrollment(COURSE_ID, OTHER_STUDENT_ID))
                                 .then()
                                 .exception(IllegalStateException.class, "Enrollment not paid");
        }
    }

    @Nested
    class MapChildEntity {

        @EventSourcedEntity(tagKey = "courseId")
        static class Course {

            @EntityMember(routingKey = "studentId")
            private final Map<String, RoutedEnrollment> enrollments = new HashMap<>();

            @EventSourcingHandler
            void on(CourseCreated event) {
                // only creates the entity
            }

            @EventSourcingHandler
            void on(StudentEnrolled event) {
                this.enrollments.put(event.studentId(), new RoutedEnrollment(event.studentId()));
            }

            @EntityCreator
            protected Course() {
            }
        }

        @Test
        void eventNamedByEventNameEvolvesMatchingMapChild() {
            fixture(Course.class).given()
                                 .events(new CourseCreated(COURSE_ID),
                                         new StudentEnrolled(COURSE_ID, STUDENT_ID),
                                         new StudentEnrolled(COURSE_ID, OTHER_STUDENT_ID),
                                         new EnrollmentPaid(COURSE_ID, STUDENT_ID))
                                 .when()
                                 .command(new ConfirmEnrollment(COURSE_ID, STUDENT_ID))
                                 .then()
                                 .events(new EnrollmentConfirmed(COURSE_ID, STUDENT_ID));
        }

        @Test
        void eventNamedByEventNameSkipsMapChildWithOtherRoutingValue() {
            fixture(Course.class).given()
                                 .events(new CourseCreated(COURSE_ID),
                                         new StudentEnrolled(COURSE_ID, STUDENT_ID),
                                         new StudentEnrolled(COURSE_ID, OTHER_STUDENT_ID),
                                         new EnrollmentPaid(COURSE_ID, STUDENT_ID))
                                 .when()
                                 // the payment for student-1 leaves student-2 unpaid
                                 .command(new ConfirmEnrollment(COURSE_ID, OTHER_STUDENT_ID))
                                 .then()
                                 .exception(IllegalStateException.class, "Enrollment not paid");
        }
    }

    // A child in a List or Map, routed by its studentId.
    static class RoutedEnrollment {

        private final String studentId;
        private boolean paid;

        RoutedEnrollment(String studentId) {
            this.studentId = studentId;
        }

        @SuppressWarnings("unused")
        public String getStudentId() {
            return studentId;
        }

        @CommandHandler
        public void handle(ConfirmEnrollment cmd, EventAppender appender) {
            if (!paid) {
                throw new IllegalStateException("Enrollment not paid");
            }
            appender.append(new EnrollmentConfirmed(cmd.courseId(), cmd.studentId()));
        }

        @EventSourcingHandler(eventName = BILLING_ENROLLMENT_PAID)
        void on(PaymentReceived event) {
            this.paid = true;
        }
    }
}
