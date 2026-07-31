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

package org.axonframework.examples.demo.multitenancy.shared.run;

import org.axonframework.examples.demo.multitenancy.university.read.statistics.CourseStatistics;
import org.axonframework.examples.demo.multitenancy.university.read.statistics.TenantStatistics;

import java.util.List;

/**
 * Renders one tenant's isolated view from its {@link TenantStatistics}: its per-course enrollment
 * counts and its audit-entry count. Since the statistics are read back through a tenant-scoped query,
 * the view only ever shows the queried tenant's data, which is what makes the isolation between
 * tenants visible in the log.
 */
final class TenantView {

    private TenantView() {
        // Utility class, not meant to be instantiated.
    }

    /**
     * Renders what each tenant's own subscription received, as a multi-line, human-readable block.
     *
     * @param springfieldTotals    the running enrollment totals Springfield's subscription received
     * @param springfieldCompleted whether Springfield's subscription was completed
     * @param shelbyvilleTotals    the running enrollment totals Shelbyville's subscription received
     * @param shelbyvilleCompleted whether Shelbyville's subscription was completed
     * @return the rendered view
     */
    public static String renderSubscriptions(List<Integer> springfieldTotals,
                                             boolean springfieldCompleted,
                                             List<Integer> shelbyvilleTotals,
                                             boolean shelbyvilleCompleted) {
        return """

               Subscription queries, one per tenant:
                 - Springfield received %s, and its subscription %s
                 - Shelbyville received %s, and its subscription %s
               """.formatted(springfieldTotals,
                             springfieldCompleted ? "completed" : "stayed open",
                             shelbyvilleTotals,
                             shelbyvilleCompleted ? "completed" : "stayed open");
    }

    /**
     * Renders the given {@code statistics} under the given {@code label} as a multi-line,
     * human-readable block.
     *
     * @param label      the heading for the tenant's view
     * @param statistics the tenant's statistics to render
     * @return the rendered view
     */
    public static String render(String label, TenantStatistics statistics) {
        StringBuilder view = new StringBuilder("\n").append(label).append(":\n");
        List<CourseStatistics> statisticsPerCourse = statistics.perCourse();
        if (statisticsPerCourse.isEmpty()) {
            view.append("  (no enrollments)\n");
        } else {
            statisticsPerCourse.forEach(statistic -> view.append("  - ")
                                                         .append(statistic.courseId())
                                                         .append(": ")
                                                         .append(statistic.enrollments())
                                                         .append(" enrollments\n"));
        }
        view.append("  audit entries: ").append(statistics.auditEntries()).append('\n');
        return view.toString();
    }
}
