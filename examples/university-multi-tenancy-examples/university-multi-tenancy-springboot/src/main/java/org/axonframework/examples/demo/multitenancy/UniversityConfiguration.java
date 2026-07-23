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

package org.axonframework.examples.demo.multitenancy;

import io.axoniq.framework.messaging.multitenancy.api.TenantComponentProvider;
import io.axoniq.framework.messaging.multitenancy.axonserver.AxonServerTenantProvider;
import org.axonframework.common.configuration.Module;
import org.axonframework.examples.demo.multitenancy.shared.TenantComponents;
import org.axonframework.examples.demo.multitenancy.university.component.AuditLog;
import org.axonframework.examples.demo.multitenancy.university.component.CourseStatisticsStore;
import org.axonframework.examples.demo.multitenancy.university.read.statistics.TenantStatisticsQueryHandler;
import org.axonframework.examples.demo.multitenancy.university.write.course.CourseConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The whole multi-tenancy configuration a Spring Boot developer writes for the feature: declare one
 * {@link TenantComponentProvider} bean per tenant-scoped component type, the statistics query handler,
 * and the event-sourced course modules, as ordinary beans.
 * <p>
 * The multi-tenancy auto-configuration from the Axoniq Framework Spring Boot starter activates the
 * feature (tenants are Axon Server contexts, so it activates only while Axon Server is enabled), picks
 * these provider beans up, subscribes them to the tenant lifecycle, and installs the tenant parameter
 * resolver and interceptor. It also registers the default auto-discovering {@link AxonServerTenantProvider},
 * which watches Axon Server's contexts and registers each as a tenant (filtering out {@code _admin}). So
 * the framework hands each command and query handler the components of the message's tenant for their
 * {@code @TenantScoped} parameters, matched by type, with no explicit multi-tenancy wiring here at all.
 */
@Configuration
public class UniversityConfiguration {

    /**
     * The provider of per-tenant {@link CourseStatisticsStore} instances.
     *
     * @return the tenant-scoped course-statistics provider
     */
    @Bean
    public TenantComponentProvider<CourseStatisticsStore> courseStatisticsProvider() {
        return TenantComponents.courseStatisticsProvider();
    }

    /**
     * The provider of per-tenant {@link AuditLog} instances.
     *
     * @return the tenant-scoped audit-log provider
     */
    @Bean
    public TenantComponentProvider<AuditLog> auditLogProvider() {
        return TenantComponents.auditLogProvider();
    }

    /**
     * The statistics query handler, whose {@code @TenantScoped} parameters the framework injects with
     * the message tenant's per-tenant components, matched by type.
     *
     * @return the statistics query handler
     */
    @Bean
    public TenantStatisticsQueryHandler tenantStatisticsQueryHandler() {
        return new TenantStatisticsQueryHandler();
    }

    /**
     * The event-sourced course entity module, which the starter registers so a course can be sourced from
     * and appended to its tenant's own event store.
     *
     * @return the course entity module
     */
    @Bean
    public Module courseEntity() {
        return CourseConfiguration.entityModule();
    }

    /**
     * The course command handling module, holding the handler that opens courses and enrolls students. Its
     * enrollment handler both appends to the tenant's event store and updates that tenant's
     * {@code @TenantScoped} components.
     *
     * @return the course command handling module
     */
    @Bean
    public Module courseCommandHandling() {
        return CourseConfiguration.commandModule();
    }
}
