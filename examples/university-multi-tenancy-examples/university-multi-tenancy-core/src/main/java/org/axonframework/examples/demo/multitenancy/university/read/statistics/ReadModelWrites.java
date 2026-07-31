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

import org.axonframework.examples.demo.multitenancy.shared.audit.AuditLog;
import org.axonframework.messaging.queryhandling.QueryUpdateEmitter;

import java.util.Objects;

/**
 * The one write that records an enrollment in a tenant's read model, so the {@link CourseStatisticsProjection}
 * and the enroll-student command handler fill it identically.
 * <p>
 * Recording the same enrollment twice leaves the read model as it was. See
 * {@link CourseStatisticsStore#recordEnrollment} for why that matters.
 * <p>
 * Being the one write also makes this the one place that emits the fresh statistics to any open
 * {@link GetTenantStatistics} subscription query, and the one place that completes those subscriptions once a
 * course has no seats left. Neither the emit nor the complete predicate names a tenant, and still only that
 * tenant's own subscriptions are affected: the framework resolves the tenant of the message being handled and
 * scopes both to it.
 *
 * @author Laura Devriendt
 * @since 5.3.0
 */
public final class ReadModelWrites {

    private ReadModelWrites() {
        // Utility class, not meant to be instantiated.
    }

    /**
     * Records that the given {@code courseId} was opened with the given {@code capacity} in the given tenant's
     * {@code courseStatisticsStore}, so the store can later tell that the course has no seats left.
     * <p>
     * This records no audit entry and emits no update: opening a course is not an enrollment, and the
     * statistics a subscription reports are about enrollments.
     *
     * @param courseStatisticsStore the course-statistics store of the tenant the course belongs to
     * @param courseId              the identifier of the course that was opened
     * @param capacity              the number of seats the course offers
     */
    public static void recordCourseOpened(CourseStatisticsStore courseStatisticsStore,
                                          String courseId,
                                          int capacity) {
        Objects.requireNonNull(courseStatisticsStore, "The course-statistics store must not be null");
        Objects.requireNonNull(courseId, "The course id must not be null");
        courseStatisticsStore.recordCourseOpened(courseId, capacity);
    }

    /**
     * Records one enrollment in the given tenant's {@code courseStatisticsStore} and {@code auditLog}, and
     * emits the tenant's fresh statistics to any open {@link GetTenantStatistics} subscription query through
     * the given {@code updateEmitter}. All three belong to a single tenant, so this never names one: the
     * caller was handed the instances of the tenant whose message it is handling.
     * <p>
     * Once that enrollment leaves the course with no seats left, the tenant's open subscriptions are completed
     * as well: a full course can receive no further enrollments, so there is no further update to expect.
     * Completing after the emit is what lets the subscriber still see the update that filled the course.
     *
     * @param courseStatisticsStore the course-statistics store of the tenant the enrollment belongs to
     * @param auditLog              the audit log of the tenant the enrollment belongs to
     * @param updateEmitter         the update emitter to notify open subscription queries through
     * @param courseId              the identifier of the course enrolled in
     * @param studentId             the identifier of the enrolled student
     */
    public static void recordEnrollment(CourseStatisticsStore courseStatisticsStore,
                                        AuditLog auditLog,
                                        QueryUpdateEmitter updateEmitter,
                                        String courseId,
                                        String studentId) {
        Objects.requireNonNull(courseStatisticsStore, "The course-statistics store must not be null");
        Objects.requireNonNull(auditLog, "The audit log must not be null");
        Objects.requireNonNull(updateEmitter, "The update emitter must not be null");
        Objects.requireNonNull(courseId, "The course id must not be null");
        Objects.requireNonNull(studentId, "The student id must not be null");
        courseStatisticsStore.recordEnrollment(courseId, studentId);
        auditLog.record("Enrolled student [" + studentId + "] in course [" + courseId + "]");
        updateEmitter.emit(GetTenantStatistics.class, query -> true,
                          new TenantStatistics(courseStatisticsStore.statistics(), auditLog.entries().size()));
        if (courseStatisticsStore.isFull(courseId)) {
            updateEmitter.complete(GetTenantStatistics.class, query -> true);
        }
    }
}
