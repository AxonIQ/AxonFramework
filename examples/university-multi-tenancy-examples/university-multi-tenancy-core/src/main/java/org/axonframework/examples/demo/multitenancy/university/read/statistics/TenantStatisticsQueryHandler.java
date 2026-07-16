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

import org.axonframework.examples.demo.multitenancy.university.component.AuditLog;
import org.axonframework.examples.demo.multitenancy.university.component.CourseStatsStore;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;

/**
 * Handles the {@link GetTenantStatistics} query, reading back the statistics of the tenant the query
 * carries.
 * <p>
 * Like the enrolment command handler, it declares its per-tenant components as parameters, here a
 * {@link CourseStatsStore} and an {@link AuditLog}, and the framework injects the query tenant's
 * instances. It is the read-side proof that the same tenant-aware injection works for query handlers,
 * not only command handlers.
 */
public class TenantStatisticsQueryHandler {

    /**
     * Assembles the current tenant's statistics from its injected course-statistics store and
     * audit log.
     *
     * @param query      the statistics query being handled
     * @param statistics the injected course-statistics store of the query's tenant
     * @param auditLog   the injected audit log of the query's tenant
     * @return the querying tenant's isolated statistics
     */
    @QueryHandler
    public TenantStatistics handle(GetTenantStatistics query, CourseStatsStore statistics, AuditLog auditLog) {
        return new TenantStatistics(statistics.statistics(), auditLog.entries().size());
    }
}
