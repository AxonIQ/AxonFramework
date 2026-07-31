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
 * Telling open subscription queries about the change is separate, in {@link #announceEnrollment}, and only the
 * projection does it. Announcing follows the write rather than the event, so a redelivered enrollment that leaves
 * the read model as it was announces nothing either, and a subscriber sees one update per enrollment however
 * often its event arrives.
 *
 * @author Laura Devriendt
 * @since 5.3.0
 */
public final class ReadModelWrites {

    private ReadModelWrites() {
        // Utility class, not meant to be instantiated.
    }

    /**
     * Records how many seats the given {@code courseId} offers in the given tenant's
     * {@code courseStatisticsStore}, so the store can later tell that the course has no seats left.
     * <p>
     * This records no audit entry and emits no update: a course's capacity is not an enrollment, and the
     * statistics a subscription reports are about enrollments.
     * <p>
     * Record a course's capacity before its enrollments. Until the store knows it, that course counts as having
     * seats left, so nothing about it can be reported as complete.
     *
     * @param courseStatisticsStore the course-statistics store of the tenant the course belongs to
     * @param courseId              the identifier of the course
     * @param capacity              the number of seats the course offers
     */
    public static void recordCourseCapacity(CourseStatisticsStore courseStatisticsStore,
                                            String courseId,
                                            int capacity) {
        Objects.requireNonNull(courseStatisticsStore, "The course-statistics store must not be null");
        Objects.requireNonNull(courseId, "The course id must not be null");
        courseStatisticsStore.recordCourseCapacity(courseId, capacity);
    }

    /**
     * Records one enrollment in the given tenant's {@code courseStatisticsStore} and {@code auditLog}, reporting
     * whether it was new to them. Both belong to a single tenant, so this never names one: the caller was handed
     * the instances of the tenant whose message it is handling.
     * <p>
     * An enrollment they already held changes nothing, and reports {@code false}.
     *
     * @param courseStatisticsStore the course-statistics store of the tenant the enrollment belongs to
     * @param auditLog              the audit log of the tenant the enrollment belongs to
     * @param courseId              the identifier of the course enrolled in
     * @param studentId             the identifier of the enrolled student
     * @return {@code true} if this enrollment was newly recorded, {@code false} if it was already held
     */
    public static boolean recordEnrollment(CourseStatisticsStore courseStatisticsStore,
                                           AuditLog auditLog,
                                           String courseId,
                                           String studentId) {
        Objects.requireNonNull(courseStatisticsStore, "The course-statistics store must not be null");
        Objects.requireNonNull(auditLog, "The audit log must not be null");
        Objects.requireNonNull(courseId, "The course id must not be null");
        Objects.requireNonNull(studentId, "The student id must not be null");
        if (!courseStatisticsStore.recordEnrollment(courseId, studentId)) {
            return false;
        }
        auditLog.record("Enrolled student [" + studentId + "] in course [" + courseId + "]");
        return true;
    }

    /**
     * Tells any open {@link GetTenantStatistics} subscription query of this tenant that its statistics changed, and
     * completes those subscriptions once none of the tenant's courses has a seat left.
     * <p>
     * Neither the emit nor the complete predicate names a tenant, and still only that tenant's own subscriptions
     * are affected: the framework resolves the tenant of the event being handled and scopes both to it.
     * <p>
     * Announcing belongs with the event handler that projected the change, not with the command handler that
     * decided it. A command handler has no business telling read-side subscribers anything.
     *
     * @param updateEmitter         the update emitter to notify open subscription queries through
     * @param courseStatisticsStore the course-statistics store of the tenant the enrollment belongs to
     * @param auditLog              the audit log of the tenant the enrollment belongs to
     * @param courseId              the identifier of the course enrolled in
     */
    public static void announceEnrollment(QueryUpdateEmitter updateEmitter,
                                          CourseStatisticsStore courseStatisticsStore,
                                          AuditLog auditLog,
                                          String courseId) {
        Objects.requireNonNull(updateEmitter, "The update emitter must not be null");
        Objects.requireNonNull(courseStatisticsStore, "The course-statistics store must not be null");
        Objects.requireNonNull(auditLog, "The audit log must not be null");
        Objects.requireNonNull(courseId, "The course id must not be null");
        // Read the statistics now and emit that value. The supplier overload is invoked later, by which time a
        // batch of enrollments would report its end state for every update in the batch.
        TenantStatistics freshStatistics = new TenantStatistics(courseStatisticsStore.statistics(),
                                                                auditLog.entries().size());
        updateEmitter.emit(GetTenantStatistics.class, query -> true, freshStatistics);
        if (courseStatisticsStore.isEveryCourseFull()) {
            updateEmitter.complete(GetTenantStatistics.class, query -> true);
        }
    }

}
