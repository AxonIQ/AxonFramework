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

import org.axonframework.common.AxonConfigurationException;
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.TargetEntityId;
import org.axonframework.modelling.entity.annotation.AnnotatedEntityMetamodel;
import org.axonframework.modelling.entity.annotation.EntityMember;
import org.axonframework.modelling.entity.annotation.EventTargetMatcherDefinition;
import org.axonframework.modelling.entity.child.EventTargetMatcher;
import org.junit.jupiter.api.*;

import java.lang.reflect.Member;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves how Axon Framework 5 routes commands and events to child entities declared with {@link EntityMember}, the
 * replacement for Axon Framework 4's {@code @AggregateMember}.
 * <p>
 * The child evolves its own state through an {@link EventSourcingHandler} and rejects a command once already confirmed.
 * Whether the child's event handler ran is therefore observable through the command outcome:
 * <ul>
 *     <li>child event handled -> the child is already confirmed -> the follow-up command is rejected;</li>
 *     <li>child event skipped -> the child is not confirmed -> the follow-up command succeeds and appends an event.</li>
 * </ul>
 * The scenarios show that the default {@code RoutingKeyEventTargetMatcherDefinition} is routing-key based, not a
 * broadcast to every child, and that command routing and event routing are resolved separately.
 */
class EntityMemberMessageRoutingTest {

    private static final String COURSE_ID = "course-1";
    private static final String STUDENT_ONE = "student-1";
    private static final String STUDENT_TWO = "student-2";

    // Commands
    record CreateCourse(@TargetEntityId String courseId) {

    }

    record ConfirmEnrollment(@TargetEntityId String courseId, String studentId) {

    }

    // Events
    record CourseCreated(@EventTag String courseId) {

    }

    record StudentEnrolled(@EventTag String courseId, String studentId) {

    }

    record EnrollmentConfirmed(@EventTag String courseId, String studentId) {

    }

    @Nested
    class SingleChildWithoutRoutingKey {

        // No routing key on a single child -> RoutingKeyEventTargetMatcherDefinition falls back to MATCH_ANY.
        @EventSourcedEntity(tagKey = "courseId")
        static class Course {

            @SuppressWarnings("unused")
            private String courseId;

            @EntityMember
            private Enrollment enrollment;

            @CommandHandler
            public static void handle(CreateCourse cmd, EventAppender appender) {
                appender.append(new CourseCreated(cmd.courseId()));
            }

            @EventSourcingHandler
            void on(CourseCreated event) {
                this.courseId = event.courseId();
            }

            @EventSourcingHandler
            void on(StudentEnrolled event) {
                this.enrollment = new Enrollment(event.studentId());
            }

            @EntityCreator
            protected Course() {
            }
        }

        AxonTestFixture fixture() {
            return AxonTestFixture.with(EventSourcingConfigurer.create().registerEntity(
                    EventSourcedEntityModule.autodetected(String.class, Course.class)));
        }

        // then: every event reaches the single child, even when its studentId does not match (ForwardAll equivalent).
        @Test
        void everyEventReachesTheSingleChild() {
            fixture().given()
                     .events(new CourseCreated(COURSE_ID),
                             new StudentEnrolled(COURSE_ID, STUDENT_ONE),
                             new EnrollmentConfirmed(COURSE_ID, "irrelevant-value"))
                     .when()
                     .command(new ConfirmEnrollment(COURSE_ID, STUDENT_ONE))
                     .then()
                     // child event handler ran, so the child rejects a second confirmation
                     .exception(IllegalStateException.class);
        }
    }

    @Nested
    class SingleChildWithRoutingKey {

        // Routing key on a single child -> events are routed by the "studentId" property.
        @EventSourcedEntity(tagKey = "courseId")
        static class Course {

            @SuppressWarnings("unused")
            private String courseId;

            @EntityMember(routingKey = "studentId")
            private Enrollment enrollment;

            @CommandHandler
            public static void handle(CreateCourse cmd, EventAppender appender) {
                appender.append(new CourseCreated(cmd.courseId()));
            }

            @EventSourcingHandler
            void on(CourseCreated event) {
                this.courseId = event.courseId();
            }

            @EventSourcingHandler
            void on(StudentEnrolled event) {
                this.enrollment = new Enrollment(event.studentId());
            }

            @EntityCreator
            protected Course() {
            }
        }

        AxonTestFixture fixture() {
            return AxonTestFixture.with(EventSourcingConfigurer.create().registerEntity(
                    EventSourcedEntityModule.autodetected(String.class, Course.class)));
        }

        // then: a matching routing value reaches the child's @EventSourcingHandler.
        @Test
        void eventReachesChildWhenRoutingValueMatches() {
            fixture().given()
                     .events(new CourseCreated(COURSE_ID),
                             new StudentEnrolled(COURSE_ID, STUDENT_ONE),
                             new EnrollmentConfirmed(COURSE_ID, STUDENT_ONE))
                     .when()
                     .command(new ConfirmEnrollment(COURSE_ID, STUDENT_ONE))
                     .then()
                     .exception(IllegalStateException.class);
        }

        // then: a non-matching routing value silently skips the child (the reported migration symptom).
        @Test
        void eventSkippedWhenRoutingValueDiffers() {
            fixture().given()
                     .events(new CourseCreated(COURSE_ID),
                             new StudentEnrolled(COURSE_ID, STUDENT_ONE),
                             new EnrollmentConfirmed(COURSE_ID, "someone-else"))
                     .when()
                     .command(new ConfirmEnrollment(COURSE_ID, STUDENT_ONE))
                     .then()
                     // child event handler never ran, so the command succeeds and appends the event
                     .events(new EnrollmentConfirmed(COURSE_ID, STUDENT_ONE));
        }
    }

    @Nested
    class CollectionChild {

        // A collection child requires a routing key; each event reaches only the matching child.
        @EventSourcedEntity(tagKey = "courseId")
        static class Course {

            @SuppressWarnings("unused")
            private String courseId;

            @EntityMember(routingKey = "studentId")
            private final List<Enrollment> enrollments = new ArrayList<>();

            @CommandHandler
            public static void handle(CreateCourse cmd, EventAppender appender) {
                appender.append(new CourseCreated(cmd.courseId()));
            }

            @EventSourcingHandler
            void on(CourseCreated event) {
                this.courseId = event.courseId();
            }

            @EventSourcingHandler
            void on(StudentEnrolled event) {
                this.enrollments.add(new Enrollment(event.studentId()));
            }

            @EntityCreator
            protected Course() {
            }
        }

        AxonTestFixture fixture() {
            return AxonTestFixture.with(EventSourcingConfigurer.create().registerEntity(
                    EventSourcedEntityModule.autodetected(String.class, Course.class)));
        }

        // then: only the child whose routing value matches receives the event.
        @Test
        void eventRoutedOnlyToMatchingChild() {
            fixture().given()
                     .events(new CourseCreated(COURSE_ID),
                             new StudentEnrolled(COURSE_ID, STUDENT_ONE),
                             new StudentEnrolled(COURSE_ID, STUDENT_TWO),
                             // routed to student-1 only
                             new EnrollmentConfirmed(COURSE_ID, STUDENT_ONE))
                     .when()
                     // student-1 is already confirmed
                     .command(new ConfirmEnrollment(COURSE_ID, STUDENT_ONE))
                     .then()
                     .exception(IllegalStateException.class);
        }

        // then: the non-matching child was never evolved by the event, so its command still succeeds.
        @Test
        void otherChildNotEvolvedByEvent() {
            fixture().given()
                     .events(new CourseCreated(COURSE_ID),
                             new StudentEnrolled(COURSE_ID, STUDENT_ONE),
                             new StudentEnrolled(COURSE_ID, STUDENT_TWO),
                             // routed to student-1 only
                             new EnrollmentConfirmed(COURSE_ID, STUDENT_ONE))
                     .when()
                     // student-2 was not confirmed by the event above
                     .command(new ConfirmEnrollment(COURSE_ID, STUDENT_TWO))
                     .then()
                     .events(new EnrollmentConfirmed(COURSE_ID, STUDENT_TWO));
        }
    }

    @Nested
    class CollectionChildWithoutRoutingKeyFailsFast {

        // A collection child WITHOUT a routing key is a configuration error.
        @EventSourcedEntity(tagKey = "courseId")
        static class Course {

            @SuppressWarnings("unused")
            private String courseId;

            @EntityMember
            private final List<Enrollment> enrollments = new ArrayList<>();

            @EventSourcingHandler
            void on(CourseCreated event) {
                this.courseId = event.courseId();
            }

            @EntityCreator
            protected Course() {
            }
        }

        // then: building the fixture fails at startup, because a routing key is mandatory for a collection child.
        // The AxonConfigurationException surfaces wrapped in the lifecycle start-handler failure.
        @Test
        void missingRoutingKeyOnCollectionThrows() {
            assertThatThrownBy(() -> AxonTestFixture.with(EventSourcingConfigurer.create().registerEntity(
                    EventSourcedEntityModule.autodetected(String.class, Course.class))))
                    .hasRootCauseInstanceOf(AxonConfigurationException.class)
                    .rootCause()
                    .hasMessageContaining("does not define a routing key");
        }
    }

    @Nested
    class CollectionChildWithBroadcastMatcher {

        // A custom matcher that delivers every event to every child (the ForwardAll equivalent for collections).
        @EventSourcedEntity(tagKey = "courseId")
        static class Course {

            @SuppressWarnings("unused")
            private String courseId;

            @EntityMember(routingKey = "studentId", eventTargetMatcher = BroadcastToAllChildren.class)
            private final List<Enrollment> enrollments = new ArrayList<>();

            @CommandHandler
            public static void handle(CreateCourse cmd, EventAppender appender) {
                appender.append(new CourseCreated(cmd.courseId()));
            }

            @EventSourcingHandler
            void on(CourseCreated event) {
                this.courseId = event.courseId();
            }

            @EventSourcingHandler
            void on(StudentEnrolled event) {
                this.enrollments.add(new Enrollment(event.studentId()));
            }

            @EntityCreator
            protected Course() {
            }
        }

        AxonTestFixture fixture() {
            return AxonTestFixture.with(EventSourcingConfigurer.create().registerEntity(
                    EventSourcedEntityModule.autodetected(String.class, Course.class)));
        }

        // then: even a child whose routing value does not match the event is evolved by it.
        @Test
        void broadcastReachesEveryChild() {
            fixture().given()
                     .events(new CourseCreated(COURSE_ID),
                             new StudentEnrolled(COURSE_ID, STUDENT_ONE),
                             new StudentEnrolled(COURSE_ID, STUDENT_TWO),
                             // broadcast: confirms BOTH children despite naming only student-1
                             new EnrollmentConfirmed(COURSE_ID, STUDENT_ONE))
                     .when()
                     // student-2 was also confirmed by the broadcast
                     .command(new ConfirmEnrollment(COURSE_ID, STUDENT_TWO))
                     .then()
                     .exception(IllegalStateException.class);
        }
    }

    // Custom event target matcher: delivers every event to every child, regardless of routing key.
    static class BroadcastToAllChildren implements EventTargetMatcherDefinition {

        @Override
        public <E> EventTargetMatcher<E> createChildEntityMatcher(AnnotatedEntityMetamodel<E> entity, Member member) {
            return (targetEntity, message, processingContext) -> true;
        }
    }

    // Child entity with its own command handler AND event-sourcing handler.
    static class Enrollment {

        private final String studentId;
        private boolean confirmed;

        Enrollment(String studentId) {
            this.studentId = studentId;
        }

        @SuppressWarnings("unused")
        public String getStudentId() {
            return studentId;
        }

        @CommandHandler
        public void handle(ConfirmEnrollment cmd, EventAppender appender) {
            if (confirmed) {
                throw new IllegalStateException("Enrollment already confirmed");
            }
            appender.append(new EnrollmentConfirmed(cmd.courseId(), cmd.studentId()));
        }

        @EventSourcingHandler
        void on(EnrollmentConfirmed event) {
            this.confirmed = true;
        }
    }
}
