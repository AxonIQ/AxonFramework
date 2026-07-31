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

package org.axonframework.examples.demo.multitenancy.university.read.statistics;

import io.axoniq.framework.messaging.multitenancy.annotation.TenantScoped;
import org.axonframework.examples.demo.multitenancy.shared.audit.AuditLog;
import org.axonframework.examples.demo.multitenancy.university.events.CourseOpened;
import org.axonframework.examples.demo.multitenancy.university.events.StudentEnrolledInCourse;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.queryhandling.QueryUpdateEmitter;

/**
 * Builds every tenant's course statistics from the enrollment events of every tenant, writing each into the
 * read model of the tenant its event came from.
 * <p>
 * This is an ordinary event handler. One pooled streaming event processor runs it for every tenant at once, so
 * there is no processor and no token store per tenant, and adding a tenant needs no configuration change.
 * <p>
 * Which tenant an event belongs to follows from the event store it was streamed from, not from anything stored
 * in the event. The framework puts that tenant on the processing context and resolves the {@link TenantScoped}
 * parameters below from it, the same injection the command and query handlers use. The
 * {@link QueryUpdateEmitter} parameter resolves from that same processing context, so the statistics update it
 * emits is isolated to that tenant's own subscription queries too.
 *
 * @author Laura Devriendt
 * @since 5.3.0
 */
public class CourseStatisticsProjection {

    /**
     * Records the course's capacity in the course statistics of the tenant whose event store this event was
     * streamed from, so that tenant's read model can tell when the course has no seats left.
     *
     * @param event                 the course-opened event being projected
     * @param courseStatisticsStore the injected course-statistics store of the event's tenant
     */
    @EventHandler
    public void on(CourseOpened event,
                   @TenantScoped CourseStatisticsStore courseStatisticsStore) {
        ReadModelWrites.recordCourseCapacity(courseStatisticsStore, event.courseId(), event.capacity());
    }

    /**
     * Records the enrollment in the course statistics and audit log of the tenant whose event store this
     * event was streamed from, and emits the tenant's fresh statistics to any open subscription query.
     *
     * @param event                 the enrollment event being projected
     * @param courseStatisticsStore the injected course-statistics store of the event's tenant
     * @param auditLog              the injected audit log of the event's tenant
     * @param updateEmitter         the update emitter to notify open subscription queries through
     */
    @EventHandler
    public void on(StudentEnrolledInCourse event,
                   @TenantScoped CourseStatisticsStore courseStatisticsStore,
                   @TenantScoped AuditLog auditLog,
                   QueryUpdateEmitter updateEmitter) {
        ReadModelWrites.recordEnrollment(courseStatisticsStore, auditLog, updateEmitter,
                                         event.courseId(), event.studentId());
    }
}
