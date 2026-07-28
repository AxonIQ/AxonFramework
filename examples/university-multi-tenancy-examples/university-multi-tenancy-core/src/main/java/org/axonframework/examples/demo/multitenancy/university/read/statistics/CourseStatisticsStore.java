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
     * Records one enrollment for the given {@code courseId}.
     *
     * @param courseId the identifier of the course to record an enrollment for
     */
    void recordEnrollment(String courseId);

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
