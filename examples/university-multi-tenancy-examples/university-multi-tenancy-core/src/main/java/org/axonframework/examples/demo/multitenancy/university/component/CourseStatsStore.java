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

package org.axonframework.examples.demo.multitenancy.university.component;

import java.util.List;

/**
 * A tenant-scoped read model holding course enrolment statistics for a single tenant.
 * <p>
 * One instance exists per tenant. Message handlers never look a tenant up: they declare this type
 * as a parameter, and the framework injects the instance belonging to the tenant of the message
 * being handled. Being {@link AutoCloseable}, the instance is closed when its tenant is removed.
 */
public interface CourseStatsStore extends AutoCloseable {

    /**
     * Records one enrolment for the given {@code courseId}.
     *
     * @param courseId the identifier of the course to record an enrolment for
     */
    void recordEnrolment(String courseId);

    /**
     * Returns the enrolment statistics per course held for this tenant.
     *
     * @return the enrolment statistics per course
     */
    List<CourseStatistics> statistics();

    @Override
    void close();
}
