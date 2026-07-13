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

package org.axonframework.examples.demo.multitenancy.university;

import io.axoniq.framework.axonserver.connector.configuration.AxonServerConfigurationEnhancer;
import io.axoniq.framework.messaging.multitenancy.api.TenantComponentProvider;
import io.axoniq.framework.messaging.multitenancy.api.TenantProvider;
import io.axoniq.framework.messaging.multitenancy.configuration.MultiTenancyConfigurationDefaults;
import org.axonframework.examples.demo.multitenancy.university.audit.AuditLog;
import org.axonframework.examples.demo.multitenancy.university.audit.InMemoryAuditLog;
import org.axonframework.examples.demo.multitenancy.university.read.coursestats.CourseStatsProjection;
import org.axonframework.examples.demo.multitenancy.university.read.coursestats.CourseStatsRepository;
import org.axonframework.examples.demo.multitenancy.university.read.coursestats.InMemoryCourseStatsRepository;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.eventhandling.SimpleEventBus;
import org.axonframework.messaging.eventhandling.processing.errorhandling.PropagatingErrorHandler;

/**
 * Wires the university's tenant-aware components into a {@link MessagingConfigurer}.
 * <p>
 * This is the whole configuration a developer writes for the feature: register one
 * {@link TenantComponentProvider} per tenant-scoped component type, and register the
 * {@link CourseStatsProjection} as an ordinary event-handling component. From there the framework
 * provides each event's handler the instances of that event's tenant, each matched by type.
 */
public final class UniversityModuleConfiguration {

    private UniversityModuleConfiguration() {
        // Utility class, not meant to be instantiated.
    }

    /**
     * Creates the provider of per-tenant {@link CourseStatsRepository} instances, each built lazily
     * on the tenant's first use.
     *
     * @return the tenant-scoped course-statistics provider
     */
    public static TenantComponentProvider<CourseStatsRepository> courseStatsProvider() {
        return TenantComponentProvider.withFactory(
                CourseStatsRepository.class,
                tenant -> new InMemoryCourseStatsRepository(tenant.tenantId())
        );
    }

    /**
     * Creates the provider of per-tenant {@link AuditLog} instances, each built lazily on the
     * tenant's first use.
     *
     * @return the tenant-scoped audit-log provider
     */
    public static TenantComponentProvider<AuditLog> auditLogProvider() {
        return TenantComponentProvider.withFactory(
                AuditLog.class,
                tenant -> new InMemoryAuditLog(tenant.tenantId())
        );
    }

    /**
     * Registers the tenant-aware wiring on the given {@code configurer}: the {@link TenantProvider}
     * supplying the tenants, one {@link TenantComponentProvider} per tenant-scoped component type,
     * and the {@link CourseStatsProjection} subscribed to the given {@code eventSource}.
     * <p>
     * The {@link MultiTenancyConfigurationDefaults} enhancer, which installs the tenant parameter
     * resolver and subscribes the providers, is discovered automatically. Only the Axon Server
     * configuration enhancer is disabled, so the demo runs fully in memory. The processor uses a
     * {@link PropagatingErrorHandler} so that a rejected tenant surfaces to the publisher rather
     * than only being logged.
     *
     * @param configurer     the configurer to extend
     * @param eventSource    the event source the projection processor subscribes to
     * @param tenantProvider the provider of the application's tenants
     * @param statsProvider  the provider of the per-tenant course-statistics repositories
     * @param auditProvider  the provider of the per-tenant audit logs
     */
    public static void configure(MessagingConfigurer configurer,
                                 SimpleEventBus eventSource,
                                 TenantProvider tenantProvider,
                                 TenantComponentProvider<CourseStatsRepository> statsProvider,
                                 TenantComponentProvider<AuditLog> auditProvider) {
        configurer.componentRegistry(registry -> registry
                // Run in memory: no Axon Server connection, tenants come from the DemoTenantProvider.
                .disableEnhancer(AxonServerConfigurationEnhancer.class)
                .registerComponent(TenantProvider.class, config -> tenantProvider)
                // The names only keep the two registrations distinct in the registry. A handler
                // parameter is still matched to a provider by the component type that provider
                // produces. That is why one provider per component type is fine, while two
                // providers for the same type make a parameter of that type ambiguous.
                .registerComponent(TenantComponentProvider.class, "courseStats", config -> statsProvider)
                .registerComponent(TenantComponentProvider.class, "auditLog", config -> auditProvider));
        configurer.eventProcessing(eventProcessing -> eventProcessing.subscribing(
                subscribing -> subscribing
                        .defaults(defaults -> defaults.eventSource(eventSource)
                                                      .errorHandler(PropagatingErrorHandler.instance()))
                        .defaultProcessor(
                                "course-stats-projection",
                                components -> components.autodetected("course-stats",
                                                                      config -> new CourseStatsProjection()))));
    }
}
