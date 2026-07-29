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

package org.axonframework.examples.demo.multitenancy.university.write.enrollstudent;

import io.axoniq.framework.messaging.multitenancy.annotation.TenantScoped;
import org.axonframework.examples.demo.multitenancy.shared.audit.AuditLog;
import org.axonframework.examples.demo.multitenancy.university.UniversityTags;
import org.axonframework.examples.demo.multitenancy.university.events.CourseOpened;
import org.axonframework.examples.demo.multitenancy.university.events.StudentEnrolledInCourse;
import org.axonframework.examples.demo.multitenancy.university.read.statistics.CourseStatisticsStore;
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.Snapshotting;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Handles enrolling a student in a course, following the load-decide-append shape. The {@link State} is
 * injected, having been sourced from the events of the tenant resolved from the message, the handler
 * decides against that state, and the resulting event is appended through the {@link EventAppender}.
 * <p>
 * The handler names no tenant. Sourcing the injected {@link State} and appending the resulting event are
 * both routed to the tenant's own event store by the framework, so the same course identifier in two
 * tenants is two isolated event streams. It also shows the two features working together in one handler:
 * alongside the event-sourced {@link State}, it takes the tenant's {@link TenantScoped}
 * {@link CourseStatisticsStore} and {@link AuditLog}, each injected for the command's tenant, matched by
 * type.
 */
class EnrollStudentCommandHandler {

    /**
     * Enrolls a student, rejecting the command with a {@link CourseNotOpenException} when the course was
     * never opened and a {@link CourseFullException} when no seat is left. Re-enrolling a student already
     * in the course is idempotent. Both decisions read the course sourced from the command's tenant's own
     * event store, so they reflect only that tenant's events.
     * <p>
     * The statistics store and audit log are updated here to keep this demo self-contained while the
     * tenant-aware read stream is still to come. That is a shortcut, not the recommended shape: a read
     * model should be a projection that consumes the appended {@link StudentEnrolledInCourse} events, not
     * something a command handler writes to. Once per-tenant event streaming is available, this write side
     * appends only, and the statistics become such a projection.
     *
     * @param command               the command enrolling the student
     * @param state                 the injected course state, sourced from the command's tenant's event store
     * @param eventAppender         the appender the enrollment event is appended through
     * @param courseStatisticsStore the injected course-statistics store of the command's tenant
     * @param auditLog              the injected audit log of the command's tenant
     */
    @CommandHandler
    void handle(EnrollStudent command,
                @InjectEntity(idProperty = UniversityTags.COURSE_ID) State state,
                EventAppender eventAppender,
                @TenantScoped CourseStatisticsStore courseStatisticsStore,
                @TenantScoped AuditLog auditLog) {
        List<StudentEnrolledInCourse> events = decide(command, state);
        eventAppender.append(events);
        if (!events.isEmpty()) {
            courseStatisticsStore.recordEnrollment(command.courseId());
            auditLog.record("Enrolled student [" + command.studentId() + "] in course [" + command.courseId() + "]");
        }
    }

    private List<StudentEnrolledInCourse> decide(EnrollStudent command, State state) {
        if (!state.open()) {
            throw new CourseNotOpenException(command.courseId());
        }
        if (state.isEnrolled(command.studentId())) {
            return List.of();
        }
        if (state.isFull()) {
            throw new CourseFullException(command.courseId(), state.capacity());
        }
        return List.of(new StudentEnrolledInCourse(command.courseId(), command.studentId()));
    }

    /**
     * The slice's own view of a course: whether it is open, how many seats it offers, and who is already
     * enrolled, which is all this handler needs to decide on an enrollment.
     * <p>
     * The course is snapshotted, and a snapshot is the entity itself handed to the {@code Converter}, so
     * this is an immutable record rather than a mutable class: each {@link EventSourcingHandler} returns
     * the evolved course instead of changing this one. A record's components are exactly the state to
     * capture, so it converts both ways without any converter-specific annotation. A mutable class whose
     * private fields have no accessors converts to an empty document against the default converter, and the
     * course then silently comes back blank.
     * <p>
     * Snapshots are per tenant. The framework stores each course's snapshot in the snapshot store of the
     * tenant whose command triggered it, and reads it back from that same store when sourcing that
     * tenant's course, so the same course identifier in two tenants is two unrelated snapshots.
     * <p>
     * {@link Snapshotting#afterEvents()} triggers once <em>more than</em> one event has been applied while
     * sourcing. Opening a course applies none, enrolling the first student applies one, and enrolling the
     * second applies two and so snapshots the course. The threshold is deliberately tiny to keep the demo
     * short. A real system snapshots after hundreds of events.
     */
    @EventSourcedEntity(tagKey = UniversityTags.COURSE_ID)
    @Snapshotting(afterEvents = 1)
    record State(boolean open, int capacity, Set<String> enrolledStudents) {

        State {
            // Also covers the constructor a snapshot is converted back through, where the students may be
            // absent.
            enrolledStudents = enrolledStudents == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(enrolledStudents));
        }

        @EntityCreator
        State() {
            // A fresh course, evolved from its own tenant's events before the command handler sees it.
            this(false, 0, Set.of());
        }

        @EventSourcingHandler
        State evolve(CourseOpened event) {
            return new State(true, event.capacity(), enrolledStudents);
        }

        @EventSourcingHandler
        State evolve(StudentEnrolledInCourse event) {
            Set<String> enrolled = new LinkedHashSet<>(enrolledStudents);
            enrolled.add(event.studentId());
            return new State(open, capacity, enrolled);
        }

        private boolean isEnrolled(String studentId) {
            return enrolledStudents.contains(studentId);
        }

        private boolean isFull() {
            return enrolledStudents.size() >= capacity;
        }
    }
}
