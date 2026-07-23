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

package org.axonframework.examples.demo.multitenancy.university.write.course;

import io.axoniq.framework.messaging.multitenancy.annotation.TenantScoped;
import org.axonframework.examples.demo.multitenancy.university.component.AuditLog;
import org.axonframework.examples.demo.multitenancy.university.component.CourseStatisticsStore;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;

/**
 * Handles the course write side: opening a course and enrolling a student into it. Both handlers follow
 * the load-decide-append shape. The {@link Course} is injected, having been sourced from the events of
 * the tenant resolved from the message, the handler decides against that state, and new events are
 * appended through the {@link EventAppender}.
 * <p>
 * Neither handler names a tenant. Sourcing the injected {@code Course} and appending the resulting events
 * are both routed to the tenant's own event store by the framework, so the same course identifier in two
 * tenants is two isolated event streams. The enrollment handler additionally shows the two features
 * working together in one handler: alongside the event-sourced {@code Course}, it takes the tenant's
 * {@link TenantScoped} {@link CourseStatisticsStore} and {@link AuditLog}, each injected for the command's
 * tenant, matched by type.
 */
class CourseCommandHandler {

    /**
     * Opens the course, unless it is already open, so the command is idempotent.
     *
     * @param command       the command opening the course
     * @param course        the injected course, sourced from the command's tenant's event store
     * @param eventAppender the appender the opening event is appended through
     */
    @CommandHandler
    void handle(OpenCourse command,
                @InjectEntity(idProperty = CourseTags.COURSE_ID) Course course,
                EventAppender eventAppender) {
        if (course.isOpen()) {
            return;
        }
        eventAppender.append(new CourseOpened(command.courseId(), command.capacity()));
    }

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
     * @param course                the injected course, sourced from the command's tenant's event store
     * @param eventAppender         the appender the enrollment event is appended through
     * @param courseStatisticsStore the injected course-statistics store of the command's tenant
     * @param auditLog              the injected audit log of the command's tenant
     */
    @CommandHandler
    void handle(EnrollStudent command,
                @InjectEntity(idProperty = CourseTags.COURSE_ID) Course course,
                EventAppender eventAppender,
                @TenantScoped CourseStatisticsStore courseStatisticsStore,
                @TenantScoped AuditLog auditLog) {
        if (!course.isOpen()) {
            throw new CourseNotOpenException(command.courseId());
        }
        if (course.isEnrolled(command.studentId())) {
            return;
        }
        if (course.isFull()) {
            throw new CourseFullException(command.courseId(), course.capacity());
        }
        eventAppender.append(new StudentEnrolledInCourse(command.courseId(), command.studentId()));
        courseStatisticsStore.recordEnrollment(command.courseId());
        auditLog.record("Enrolled student [" + command.studentId() + "] in course [" + command.courseId() + "]");
    }
}
