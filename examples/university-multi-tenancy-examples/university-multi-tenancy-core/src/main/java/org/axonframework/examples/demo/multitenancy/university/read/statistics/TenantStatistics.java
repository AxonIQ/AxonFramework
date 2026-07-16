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

import org.axonframework.examples.demo.multitenancy.university.component.CourseStatistics;

import java.util.List;

/**
 * One tenant's isolated read model: its enrolment count per course and the size of its audit trail.
 * Because it is assembled from the tenant's own injected components, it only ever contains that
 * tenant's data, which is what makes the isolation between tenants observable.
 *
 * @param perCourse    the enrolment count per course for this tenant
 * @param auditEntries the number of audit entries recorded for this tenant
 */
public record TenantStatistics(List<CourseStatistics> perCourse, int auditEntries) {

    /**
     * Returns the total number of enrolments recorded across all of this tenant's courses.
     *
     * @return the total number of enrolments for this tenant
     */
    public int totalEnrolments() {
        return perCourse.stream().mapToInt(CourseStatistics::enrolments).sum();
    }
}
