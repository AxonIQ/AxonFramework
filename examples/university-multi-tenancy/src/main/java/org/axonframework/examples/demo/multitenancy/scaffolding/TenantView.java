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

package org.axonframework.examples.demo.multitenancy.scaffolding;

import io.axoniq.framework.messaging.multitenancy.api.TenantComponentProvider;
import io.axoniq.framework.messaging.multitenancy.api.TenantDescriptor;
import org.axonframework.examples.demo.multitenancy.university.audit.AuditLog;
import org.axonframework.examples.demo.multitenancy.university.read.coursestats.CourseStatistics;
import org.axonframework.examples.demo.multitenancy.university.read.coursestats.CourseStatsRepository;

import java.util.List;

/**
 * Renders one tenant's isolated view: its per-course enrolment counts and its audit-entry count. The
 * two parts come from their own providers but are always the given tenant's instances, which is
 * what makes the isolation between tenants visible in the log.
 */
public final class TenantView {

    private TenantView() {
        // Utility class, not meant to be instantiated.
    }

    /**
     * Renders the given {@code tenant}'s view as a multi-line, human-readable block.
     * <p>
     * The per-tenant components are {@link AutoCloseable}, but the framework closes them on tenant
     * removal and shutdown, so this only reads their state and never closes them itself.
     *
     * @param label         the heading for the tenant's view
     * @param statsProvider the provider of the per-tenant course-statistics repositories
     * @param auditProvider the provider of the per-tenant audit logs
     * @param tenant        the tenant whose view to render
     * @return the rendered view
     */
    // The framework closes the per-tenant components on tenant removal and shutdown, so this only reads
    // their state and never closes them itself.
    @SuppressWarnings("resource")
    public static String render(String label,
                                TenantComponentProvider<CourseStatsRepository> statsProvider,
                                TenantComponentProvider<AuditLog> auditProvider,
                                TenantDescriptor tenant) {
        CourseStatsRepository statistics = statsProvider.componentFor(tenant);
        AuditLog auditLog = auditProvider.componentFor(tenant);
        StringBuilder view = new StringBuilder("\n").append(label).append(":\n");
        List<CourseStatistics> statisticsPerCourse = statistics.statistics();
        if (statisticsPerCourse.isEmpty()) {
            view.append("  (no enrolments)\n");
        } else {
            statisticsPerCourse.forEach(statistic -> view.append("  - ")
                                                         .append(statistic.courseId())
                                                         .append(": ")
                                                         .append(statistic.enrolments())
                                                         .append(" enrolments\n"));
        }
        view.append("  audit entries: ").append(auditLog.entries().size()).append('\n');
        return view.toString();
    }
}
