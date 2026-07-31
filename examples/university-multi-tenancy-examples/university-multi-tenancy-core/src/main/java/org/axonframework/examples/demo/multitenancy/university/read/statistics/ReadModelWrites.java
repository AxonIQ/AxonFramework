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

import java.util.Objects;

/**
 * The one write that records an enrollment in a tenant's read model, so the {@link CourseStatisticsProjection}
 * and the enroll-student command handler fill it identically.
 * <p>
 * Recording the same enrollment twice leaves the read model as it was. See
 * {@link CourseStatisticsStore#recordEnrollment} for why that matters.
 *
 * @author Laura Devriendt
 * @since 5.3.0
 */
public final class ReadModelWrites {

    private ReadModelWrites() {
        // Utility class, not meant to be instantiated.
    }

    /**
     * Records one enrollment in the given tenant's {@code courseStatisticsStore} and {@code auditLog}. Both
     * belong to a single tenant, so this never names one: the caller was handed the instances of the tenant
     * whose message it is handling.
     *
     * @param courseStatisticsStore the course-statistics store of the tenant the enrollment belongs to
     * @param auditLog              the audit log of the tenant the enrollment belongs to
     * @param courseId              the identifier of the course enrolled in
     * @param studentId             the identifier of the enrolled student
     */
    public static void recordEnrollment(CourseStatisticsStore courseStatisticsStore,
                                        AuditLog auditLog,
                                        String courseId,
                                        String studentId) {
        Objects.requireNonNull(courseStatisticsStore, "The course-statistics store must not be null");
        Objects.requireNonNull(auditLog, "The audit log must not be null");
        Objects.requireNonNull(courseId, "The course id must not be null");
        Objects.requireNonNull(studentId, "The student id must not be null");
        courseStatisticsStore.recordEnrollment(courseId, studentId);
        auditLog.record("Enrolled student [" + studentId + "] in course [" + courseId + "]");
    }
}
