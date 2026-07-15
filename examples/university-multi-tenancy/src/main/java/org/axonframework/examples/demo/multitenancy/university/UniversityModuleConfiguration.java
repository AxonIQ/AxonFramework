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
import io.axoniq.framework.messaging.multitenancy.axonserver.AxonServerTenantProvider;
import io.axoniq.framework.messaging.multitenancy.configuration.MultiTenancyConfigurationDefaults;
import io.axoniq.framework.messaging.multitenancy.configuration.MultiTenancyConfigurationUtils.MultiTenancyEnabled;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.SearchScope;
import org.axonframework.examples.demo.multitenancy.university.audit.AuditLog;
import org.axonframework.examples.demo.multitenancy.university.audit.InMemoryAuditLog;
import org.axonframework.examples.demo.multitenancy.university.read.coursestats.CourseStatsProjection;
import org.axonframework.examples.demo.multitenancy.university.read.coursestats.CourseStatsRepository;
import org.axonframework.examples.demo.multitenancy.university.read.coursestats.InMemoryCourseStatsRepository;
import org.axonframework.messaging.core.SubscribableEventSource;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
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
     * Registers the in-memory tenant-aware wiring on the given {@code configurer}: the
     * {@link TenantProvider} supplying the tenants, one {@link TenantComponentProvider} per
     * tenant-scoped component type, and the {@link CourseStatsProjection} subscribed to the given
     * {@code eventSource}.
     * <p>
     * The {@link MultiTenancyConfigurationDefaults} enhancer, which installs the tenant parameter
     * resolver and subscribes the providers, only runs when multi-tenancy is enabled, so this method
     * turns it on with {@link MultiTenancyEnabled#enableMultiTenancyEnhancer(ComponentRegistry)}. The
     * Axon Server configuration enhancer is disabled and the {@code tenantProvider} is registered
     * explicitly, so the demo runs fully in memory. The processor uses a
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
                                 SubscribableEventSource eventSource,
                                 TenantProvider tenantProvider,
                                 TenantComponentProvider<CourseStatsRepository> statsProvider,
                                 TenantComponentProvider<AuditLog> auditProvider) {
        configurer.componentRegistry(registry -> {
            MultiTenancyEnabled.enableMultiTenancyEnhancer(registry);
            // Run in memory: no Axon Server connection, tenants come from the DemoTenantProvider.
            registry.disableEnhancer(AxonServerConfigurationEnhancer.class)
                    .registerComponent(TenantProvider.class, config -> tenantProvider);
            registerTenantComponents(registry, statsProvider, auditProvider);
        });
        registerProjection(configurer, eventSource);
    }

    /**
     * Registers the Axon Server backed tenant-aware wiring on the given {@code configurer}: the
     * {@link AxonServerTenantProvider} sourcing its tenants from the given {@code predefinedContexts},
     * one {@link TenantComponentProvider} per tenant-scoped component type, and the
     * {@link CourseStatsProjection} subscribed to the given {@code eventSource}.
     * <p>
     * The difference with {@link #configure(MessagingConfigurer, SubscribableEventSource, TenantProvider,
     * TenantComponentProvider, TenantComponentProvider)} is the source of the tenants: the Axon Server
     * configuration enhancer is left enabled, so an {@code AxonServerConnectionManager} is available,
     * and the {@link AxonServerTenantProvider} treats each configured context as a tenant. Enrolments
     * still flow through the given {@code eventSource} carrying their tenant in metadata, exactly as in
     * the in-memory setup, so the framework injects each context's per-tenant components.
     *
     * @param configurer         the configurer to extend
     * @param eventSource        the event source the projection processor subscribes to
     * @param predefinedContexts a comma-separated list of Axon Server context names to treat as tenants
     * @param statsProvider      the provider of the per-tenant course-statistics repositories
     * @param auditProvider      the provider of the per-tenant audit logs
     */
    public static void configureForAxonServer(MessagingConfigurer configurer,
                                              SubscribableEventSource eventSource,
                                              String predefinedContexts,
                                              TenantComponentProvider<CourseStatsRepository> statsProvider,
                                              TenantComponentProvider<AuditLog> auditProvider) {
        configurer.componentRegistry(registry -> {
            MultiTenancyEnabled.enableMultiTenancyEnhancer(registry);
            // Keep the Axon Server enhancer so the AxonServerConnectionManager the tenant provider needs
            // is available. Register the provider over predefined contexts, so it does not override the
            // default (auto-discovering) one the multi-tenancy enhancer would otherwise register.
            registry.registerIfNotPresent(
                    MultiTenancyConfigurationDefaults.axonServerTenantProvider(predefinedContexts),
                    SearchScope.ALL);
            registerTenantComponents(registry, statsProvider, auditProvider);
        });
        registerProjection(configurer, eventSource);
    }

    /**
     * Registers the two per-tenant component providers on the given {@code registry}.
     * <p>
     * The registration names only keep the two registrations distinct in the registry. A handler
     * parameter is still matched to a provider by the component type that provider produces. That is
     * why one provider per component type is fine, while two providers for the same type make a
     * parameter of that type ambiguous.
     *
     * @param registry      the registry to register the providers on
     * @param statsProvider the provider of the per-tenant course-statistics repositories
     * @param auditProvider the provider of the per-tenant audit logs
     */
    private static void registerTenantComponents(ComponentRegistry registry,
                                                 TenantComponentProvider<CourseStatsRepository> statsProvider,
                                                 TenantComponentProvider<AuditLog> auditProvider) {
        registry.registerComponent(TenantComponentProvider.class, "courseStats", config -> statsProvider)
                .registerComponent(TenantComponentProvider.class, "auditLog", config -> auditProvider);
    }

    /**
     * Registers the {@link CourseStatsProjection} as a subscribing event processor reading from the
     * given {@code eventSource}, using a {@link PropagatingErrorHandler} so that a rejected tenant
     * surfaces to the publisher rather than only being logged.
     *
     * @param configurer  the configurer to extend
     * @param eventSource the event source the projection processor subscribes to
     */
    private static void registerProjection(MessagingConfigurer configurer, SubscribableEventSource eventSource) {
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
