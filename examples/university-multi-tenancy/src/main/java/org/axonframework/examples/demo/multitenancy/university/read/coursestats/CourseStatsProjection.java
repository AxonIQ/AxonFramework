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

package org.axonframework.examples.demo.multitenancy.university.read.coursestats;

import org.axonframework.examples.demo.multitenancy.university.audit.AuditLog;
import org.axonframework.examples.demo.multitenancy.university.events.StudentEnrolledInCourse;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;

/**
 * Projection maintaining per-tenant course statistics and an audit trail.
 * <p>
 * The handler declares a {@link CourseStatsRepository} and an {@link AuditLog} parameter and is
 * handed the instances of the tenant of the event being handled, each matched by its own type, so
 * it never resolves a tenant itself. This is the whole developer-facing surface of tenant-aware
 * components.
 */
public class CourseStatsProjection {

    /**
     * Records the enrolment on the current tenant's course-statistics repository and audit log.
     *
     * @param event      the enrolment event being handled
     * @param statistics the injected course-statistics repository of the event's tenant
     * @param auditLog   the injected audit log of the event's tenant
     */
    @EventHandler
    public void on(StudentEnrolledInCourse event, CourseStatsRepository statistics, AuditLog auditLog) {
        statistics.recordEnrolment(event.courseId());
        auditLog.record("Enrolled student [" + event.studentId() + "] in course [" + event.courseId() + "]");
    }
}
