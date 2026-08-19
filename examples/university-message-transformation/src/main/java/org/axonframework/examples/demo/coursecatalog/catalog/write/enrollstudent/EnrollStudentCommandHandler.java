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

package org.axonframework.examples.demo.coursecatalog.catalog.write.enrollstudent;

import org.axonframework.examples.demo.coursecatalog.catalog.CourseCatalogMessageNames;
import org.axonframework.examples.demo.coursecatalog.catalog.CourseCatalogTags;
import org.axonframework.examples.demo.coursecatalog.catalog.events.CoursePublished;
import org.axonframework.examples.demo.coursecatalog.catalog.events.RegistrationClosed;
import org.axonframework.examples.demo.coursecatalog.catalog.events.StudentEnrolledInCourse;
import org.axonframework.examples.demo.coursecatalog.catalog.events.StudentRegistered;
import org.axonframework.examples.demo.coursecatalog.shared.region.RequestRegion;
import org.axonframework.examples.demo.coursecatalog.catalog.values.CapacityRange;
import org.axonframework.eventsourcing.annotation.EventCriteriaBuilder;
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.axonframework.modelling.annotation.InjectEntity;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Enrols a student in a course. The DCB scope spans two tags ({@code courseId} and
 * {@code studentId}); the {@code @EventCriteriaBuilder} composes them as a union so
 * one read returns every event relevant to either side of the enrolment.
 */
class EnrollStudentCommandHandler {

    @CommandHandler
    void handle(
            EnrollStudent command,
            @InjectEntity State state,
            EventAppender eventAppender
    ) {
        eventAppender.append(decide(command, state));
    }

    private List<StudentEnrolledInCourse> decide(EnrollStudent command, State state) {
        assertCoursePublished(state);
        assertStudentRegistered(state);
        assertRegistrationOpen(state);
        assertCapacityAvailable(state);
        if (state.alreadyEnrolled) {
            return List.of();
        }
        return List.of(new StudentEnrolledInCourse(command.courseId(), command.studentId(), state.region));
    }

    private static void assertCoursePublished(State state) {
        if (!state.coursePublished) {
            throw new IllegalStateException("Course is not published");
        }
    }

    private static void assertStudentRegistered(State state) {
        if (!state.studentRegistered) {
            throw new IllegalStateException("Student is not registered with the catalog");
        }
    }

    private static void assertRegistrationOpen(State state) {
        if (state.registrationClosed) {
            throw new IllegalStateException("Registration is closed for this course");
        }
    }

    private static void assertCapacityAvailable(State state) {
        CapacityRange range = state.range;
        if (range != null && state.enrolmentsInCourse >= range.max()) {
            throw new IllegalStateException(
                    "Course is full (capacity max=" + range.max() + ")");
        }
    }

    @EventSourcedEntity
    static final class State {

        private boolean coursePublished;
        private boolean studentRegistered;
        private String region = RequestRegion.UNKNOWN_REGION;
        private boolean registrationClosed;
        private boolean alreadyEnrolled;
        @Nullable
        private CapacityRange range;
        private int enrolmentsInCourse;

        @EntityCreator
        State() {
        }

        @EventSourcingHandler
        void evolve(CoursePublished event) {
            this.coursePublished = true;
            this.range = event.range();
        }

        @EventSourcingHandler
        void evolve(StudentRegistered event) {
            this.studentRegistered = true;
            this.region = event.region();
        }

        @EventSourcingHandler
        void evolve(RegistrationClosed event) {
            this.registrationClosed = true;
        }

        @EventSourcingHandler
        void evolve(StudentEnrolledInCourse event) {
            this.enrolmentsInCourse++;
            this.alreadyEnrolled = true;
        }

        @EventCriteriaBuilder
        private static EventCriteria resolveCriteria(EnrolmentId id) {
            String courseId = id.courseId().toString();
            String studentId = id.studentId().toString();
            // Filter by the qualified name the @Event annotation declares for each event,
            // not the Java FQN.
            return EventCriteria.either(
                    EventCriteria
                            .havingTags(Tag.of(CourseCatalogTags.COURSE_ID, courseId))
                            .andBeingOneOfTypes(
                                    CourseCatalogMessageNames.COURSE_PUBLISHED,
                                    CourseCatalogMessageNames.REGISTRATION_CLOSED,
                                    CourseCatalogMessageNames.STUDENT_ENROLLED_IN_COURSE
                            ),
                    EventCriteria
                            .havingTags(Tag.of(CourseCatalogTags.STUDENT_ID, studentId))
                            .andBeingOneOfTypes(
                                    CourseCatalogMessageNames.STUDENT_REGISTERED,
                                    CourseCatalogMessageNames.STUDENT_ENROLLED_IN_COURSE
                            )
            );
        }
    }
}
