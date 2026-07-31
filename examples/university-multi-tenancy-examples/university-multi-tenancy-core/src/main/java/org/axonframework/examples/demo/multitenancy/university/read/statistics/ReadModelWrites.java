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
 * {@link GetTenantStatistics} subscription query, and the one place that completes those subscriptions once
 * none of the tenant's courses has a seat left. Neither the emit nor the complete predicate names a tenant, and
 * still only that tenant's own subscriptions are affected: the framework resolves the tenant of the message
 * being handled and scopes both to it.
 * <p>
 * Emitting follows the write rather than the event, so a redelivered enrollment that leaves the read model as
 * it was emits nothing either. A subscriber therefore sees one update per enrollment however often its event
 * arrives, which is what lets the demo compare the updates it received one for one. Each update carries the
 * statistics as that enrollment left them, so a batch of enrollments reports its steps rather than its result.
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
     * Records one enrollment in the given tenant's {@code courseStatisticsStore} and {@code auditLog}, and
     * emits the tenant's fresh statistics to any open {@link GetTenantStatistics} subscription query through
     * the given {@code updateEmitter}. All three belong to a single tenant, so this never names one: the
     * caller was handed the instances of the tenant whose message it is handling.
     * <p>
     * Once that enrollment leaves every one of the tenant's courses with no seats left, its open subscriptions
     * are completed as well: there is no further enrollment to report, so there is no further update to expect.
     * A subscription reports the whole tenant, so one full course is not enough to complete it.
     * Completing after the emit is what lets the subscriber still see the update that filled the course.
     * <p>
     * An enrollment this store already held changes nothing, so it emits nothing and completes nothing.
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
        if (!courseStatisticsStore.recordEnrollment(courseId, studentId)) {
            // Already held, so there is nothing to audit and nothing fresh to report either.
            return;
        }
        auditLog.record("Enrolled student [" + studentId + "] in course [" + courseId + "]");
        // Read now, and hand the emitter a value rather than a supplier. Each update has to carry the statistics
        // this enrollment left behind, and a supplier is invoked later, so a batch holding several enrollments
        // would report the batch's end state for every one of them. This builds the update even when nothing is
        // subscribed, which for a read model this size is a cheaper price than a wrong update. Two threads
        // recording for one tenant can still snapshot each other's write, so this reports one enrollment at a
        // time rather than a strict order.
        TenantStatistics freshStatistics = new TenantStatistics(courseStatisticsStore.statistics(),
                                                                auditLog.entries().size());
        updateEmitter.emit(GetTenantStatistics.class, query -> true, freshStatistics);
        if (courseStatisticsStore.isEveryCourseFull()) {
            updateEmitter.complete(GetTenantStatistics.class, query -> true);
        }
    }
}
