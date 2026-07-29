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

import io.axoniq.framework.axonserver.connector.configuration.AxonServerConfigurationEnhancer;
import io.axoniq.framework.messaging.multitenancy.api.TenantComponentProvider;
import io.axoniq.framework.messaging.multitenancy.api.TenantProvider;
import io.axoniq.framework.messaging.multitenancy.axonserver.AxonServerMultiTenancyConfigurationDefaults;
import io.axoniq.framework.messaging.multitenancy.configuration.MultiTenancyConfigurationUtils.MultiTenancyEnabled;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.eventsourcing.snapshot.inmemory.InMemorySnapshotStore;
import org.axonframework.eventsourcing.snapshot.store.SnapshotStore;
import org.axonframework.examples.demo.multitenancy.shared.audit.AuditLog;
import org.axonframework.examples.demo.multitenancy.university.UniversityModuleConfiguration;
import org.axonframework.examples.demo.multitenancy.university.read.statistics.CourseStatisticsStore;

/**
 * Wires the university's tenant-aware pieces onto an {@link EventSourcingConfigurer}, the declarative
 * Configuration API's equivalent of what Spring Boot auto-configuration does for the Spring Boot demo.
 * <p>
 * The domain itself, the write slices and the statistics read slice, is registered by
 * {@link UniversityModuleConfiguration}, shared with the Spring Boot demo. This class only adds the
 * multi-tenancy wiring around it: it turns on the multi-tenancy enhancer and registers one
 * {@link TenantComponentProvider} per tenant-scoped component type, so the framework hands each handler
 * the components of the message's tenant, matched by type, and routes the course's events to that
 * tenant's own event store.
 */
public final class UniversityConfiguration {

    private UniversityConfiguration() {
        // Utility class, not meant to be instantiated.
    }

    /**
     * Registers the in-memory tenant-aware wiring on the given {@code configurer}: the university domain,
     * the {@link TenantProvider} supplying the tenants, and one {@link TenantComponentProvider} per
     * tenant-scoped component type.
     * <p>
     * The Axon Server configuration enhancer is disabled and the {@code tenantProvider} is registered
     * explicitly, so the demo runs fully in memory, on a single shared in-memory event store rather than
     * one per tenant. The course is snapshotted, so this path also needs a {@link SnapshotStore}, and in
     * memory that is one shared store rather than one per tenant. Against Axon Server the application
     * registers none: the multi-tenancy defaults own that registration so every tenant's snapshots land
     * in its own context, and an application-registered store is refused.
     *
     * @param configurer         the configurer to extend
     * @param tenantProvider     the provider of the application's tenants
     * @param statisticsProvider the provider of the per-tenant course-statistics stores
     * @param auditProvider      the provider of the per-tenant audit logs
     */
    public static void configure(EventSourcingConfigurer configurer,
                                 TenantProvider tenantProvider,
                                 TenantComponentProvider<CourseStatisticsStore> statisticsProvider,
                                 TenantComponentProvider<AuditLog> auditProvider) {
        UniversityModuleConfiguration.configure(configurer)
                                     .componentRegistry(registry -> {
                                         MultiTenancyEnabled.enableMultiTenancyEnhancer(registry);
                                         // Run in memory: no Axon Server connection, tenants come from the DemoTenantProvider.
                                         registry.disableEnhancer(AxonServerConfigurationEnhancer.class)
                                                 .disableEnhancer(AxonServerMultiTenancyConfigurationDefaults.class)
                                                 .registerComponent(TenantProvider.class, config -> tenantProvider)
                                                 .registerComponent(SnapshotStore.class,
                                                                    config -> new InMemorySnapshotStore());
                                         registerTenantComponents(registry, statisticsProvider, auditProvider);
                                     });
    }

    /**
     * Registers the Axon Server backed tenant-aware wiring on the given {@code configurer}: the same
     * university domain, and one {@link TenantComponentProvider} per tenant-scoped component type.
     * <p>
     * The Axon Server configuration enhancer is left enabled, so the multi-tenancy enhancer registers its
     * default auto-discovering tenant provider watching Axon Server's contexts, and the course write side
     * is registered against the per-tenant, tenant-aware event store Axon Server provides. This path needs
     * a running multi-context (Enterprise Edition) Axon Server.
     *
     * @param configurer         the configurer to extend
     * @param statisticsProvider the provider of the per-tenant course-statistics stores
     * @param auditProvider      the provider of the per-tenant audit logs
     */
    public static void configureForAxonServer(EventSourcingConfigurer configurer,
                                              TenantComponentProvider<CourseStatisticsStore> statisticsProvider,
                                              TenantComponentProvider<AuditLog> auditProvider) {
        UniversityModuleConfiguration.configure(configurer)
                                     .componentRegistry(registry -> {
                                         MultiTenancyEnabled.enableMultiTenancyEnhancer(registry);
                                         registerTenantComponents(registry, statisticsProvider, auditProvider);
                                     });
    }

    /**
     * Registers the two per-tenant component providers on the given {@code registry}. The registration
     * names only keep the two registrations distinct; a handler parameter is matched to a provider by the
     * component type that provider produces.
     *
     * @param registry           the registry to register the providers on
     * @param statisticsProvider the provider of the per-tenant course-statistics stores
     * @param auditProvider      the provider of the per-tenant audit logs
     */
    private static void registerTenantComponents(ComponentRegistry registry,
                                                 TenantComponentProvider<CourseStatisticsStore> statisticsProvider,
                                                 TenantComponentProvider<AuditLog> auditProvider) {
        registry.registerComponent(TenantComponentProvider.class, "courseStatistics", config -> statisticsProvider)
                .registerComponent(TenantComponentProvider.class, "auditLog", config -> auditProvider);
    }
}
