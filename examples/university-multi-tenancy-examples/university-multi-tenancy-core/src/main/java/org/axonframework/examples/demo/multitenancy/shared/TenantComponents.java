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

package org.axonframework.examples.demo.multitenancy.shared;

import io.axoniq.framework.messaging.multitenancy.api.TenantComponentProvider;
import org.axonframework.examples.demo.multitenancy.university.component.AuditLog;
import org.axonframework.examples.demo.multitenancy.university.component.CourseStatisticsStore;
import org.axonframework.examples.demo.multitenancy.university.component.InMemoryAuditLog;
import org.axonframework.examples.demo.multitenancy.university.component.InMemoryCourseStatisticsStore;

/**
 * The university's tenant-scoped component providers, shared by both demos so each registers exactly
 * the same components and only their configuration style differs. Each provider builds one instance
 * per tenant lazily on the tenant's first use, and the framework injects the right tenant's instance
 * into a handler that declares the component's type as a parameter.
 */
public final class TenantComponents {

    private TenantComponents() {
        // Utility class, not meant to be instantiated.
    }

    /**
     * Creates the provider of per-tenant {@link CourseStatisticsStore} instances.
     *
     * @return the tenant-scoped course-statistics provider
     */
    public static TenantComponentProvider<CourseStatisticsStore> courseStatisticsProvider() {
        return TenantComponentProvider.withFactory(
                CourseStatisticsStore.class,
                tenant -> new InMemoryCourseStatisticsStore(tenant.tenantId())
        );
    }

    /**
     * Creates the provider of per-tenant {@link AuditLog} instances.
     *
     * @return the tenant-scoped audit-log provider
     */
    public static TenantComponentProvider<AuditLog> auditLogProvider() {
        return TenantComponentProvider.withFactory(
                AuditLog.class,
                tenant -> new InMemoryAuditLog(tenant.tenantId())
        );
    }
}
