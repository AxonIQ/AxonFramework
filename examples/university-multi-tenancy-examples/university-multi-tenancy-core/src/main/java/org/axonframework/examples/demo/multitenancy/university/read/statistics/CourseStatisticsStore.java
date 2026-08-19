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

import java.util.List;

/**
 * A tenant-scoped read model holding course enrollment statistics for a single tenant.
 * <p>
 * One instance exists per tenant. Message handlers never look a tenant up: they declare this type
 * as a parameter, and the framework injects the instance belonging to the tenant of the message
 * being handled. Being {@link AutoCloseable}, the instance is closed when its tenant is removed.
 */
public interface CourseStatisticsStore extends AutoCloseable {

    /**
     * Creates an in-memory course-statistics store for the tenant with the given {@code tenantId}, the
     * implementation this demo uses. Callers depend only on this interface, never on the implementation
     * type.
     *
     * @param tenantId the identifier of the tenant the store belongs to
     * @return an in-memory course-statistics store for the tenant
     */
    static CourseStatisticsStore inMemory(String tenantId) {
        return new InMemoryCourseStatisticsStore(tenantId);
    }

    /**
     * Records how many seats the given {@code courseId} offers, so this store can tell when that course has
     * no seats left.
     * <p>
     * Recording the same course again overwrites the capacity held for it. A course's capacity does not change
     * in this demo, so a repeat carries the same number and leaves the store as it was.
     *
     * @param courseId the identifier of the course
     * @param capacity the number of seats the course offers
     */
    void recordCourseCapacity(String courseId, int capacity);

    /**
     * Indicates whether every course this tenant holds is at capacity, and so whether the tenant's statistics
     * can still change at all.
     * <p>
     * A course counts as held once this store knows either its capacity or an enrollment in it, so a course that
     * was opened and has nobody enrolled yet still keeps the tenant from being full. That is a wider set than
     * {@link #statistics()} reports, which covers only courses somebody enrolled in.
     * <p>
     * This is a tenant-wide question rather than a per-course one, because {@link GetTenantStatistics} reports
     * the whole tenant. A single full course says nothing while another still has seats.
     * <p>
     * Returns {@code false} while any course's capacity is unknown to this store, since an unknown capacity is
     * no reason to declare a course full, and {@code false} for a tenant holding no courses at all.
     *
     * @return {@code true} if every course held is known to be at capacity
     */
    boolean isEveryCourseFull();

    /**
     * Records that the given {@code studentId} is enrolled in the given {@code courseId}, reporting whether
     * that enrollment was new to this store.
     * <p>
     * Recording the same student in the same course again has no effect, and reports {@code false}. That
     * matters because this store is filled from a streamed event, and an event can reach a handler more than
     * once: the stream is re-opened whenever a tenant is added or removed, and the processor cannot always tell
     * that an event was already handled. Counting enrollments instead of remembering who is enrolled would
     * drift upwards every time that happens, and anything derived from this write would repeat with it.
     *
     * @param courseId  the identifier of the course enrolled in
     * @param studentId the identifier of the enrolled student
     * @return {@code true} if this enrollment was newly recorded, {@code false} if it was already held
     */
    boolean recordEnrollment(String courseId, String studentId);

    /**
     * Returns the enrollment statistics per course held for this tenant.
     *
     * @return the enrollment statistics per course
     */
    List<CourseStatistics> statistics();

    /**
     * Indicates whether this store has been closed, which happens when its tenant is removed. It lets the
     * demo observe the framework closing a tenant's per-tenant instances.
     *
     * @return {@code true} if this store was closed
     */
    boolean isClosed();

    @Override
    void close();
}
