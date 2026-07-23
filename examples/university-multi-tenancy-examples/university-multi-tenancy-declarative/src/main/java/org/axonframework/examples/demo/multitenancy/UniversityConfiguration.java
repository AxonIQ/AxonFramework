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
import io.axoniq.framework.messaging.multitenancy.axonserver.AxonServerTenantProvider;
import io.axoniq.framework.messaging.multitenancy.configuration.MultiTenancyConfigurationDefaults;
import io.axoniq.framework.messaging.multitenancy.configuration.MultiTenancyConfigurationUtils.MultiTenancyEnabled;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.examples.demo.multitenancy.university.component.AuditLog;
import org.axonframework.examples.demo.multitenancy.university.component.CourseStatisticsStore;
import org.axonframework.examples.demo.multitenancy.university.read.statistics.TenantStatisticsQueryHandler;
import org.axonframework.examples.demo.multitenancy.university.write.course.CourseConfiguration;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.queryhandling.configuration.QueryHandlingModule;

/**
 * Wires the university's tenant-aware pieces onto an {@link EventSourcingConfigurer}, the declarative
 * Configuration API's equivalent of what Spring Boot auto-configuration does for the Spring Boot demo.
 * <p>
 * This is the whole configuration a developer writes for the feature: register the event-sourced course
 * write side, one {@link TenantComponentProvider} per tenant-scoped component type, and the statistics
 * query handler. The enrollment command handler and the query handler mark their per-tenant parameters
 * {@link io.axoniq.framework.messaging.multitenancy.annotation.TenantScoped}, so the framework hands each
 * handler the components of the message's tenant, matched by type, and routes the course's events to that
 * tenant's own event store, without the handler ever resolving a tenant itself.
 */
public final class UniversityConfiguration {

    private UniversityConfiguration() {
        // Utility class, not meant to be instantiated.
    }

    /**
     * Registers the in-memory tenant-aware wiring on the given {@code configurer}: the event-sourced
     * course write side, the {@link TenantProvider} supplying the tenants, one
     * {@link TenantComponentProvider} per tenant-scoped component type, and the statistics query handler.
     * <p>
     * The {@link MultiTenancyConfigurationDefaults} enhancer, which installs the tenant parameter
     * resolver and subscribes the providers, only runs when multi-tenancy is enabled, so this method
     * turns it on with {@link MultiTenancyEnabled#enableMultiTenancyEnhancer(ComponentRegistry)}. The
     * Axon Server configuration enhancer is disabled and the {@code tenantProvider} is registered
     * explicitly, so the demo runs fully in memory, on a single shared in-memory event store rather than
     * one per tenant.
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
        CourseConfiguration.configure(configurer)
                           .componentRegistry(registry -> {
                               MultiTenancyEnabled.enableMultiTenancyEnhancer(registry);
                               // Run in memory: no Axon Server connection, tenants come from the DemoTenantProvider.
                               registry.disableEnhancer(AxonServerConfigurationEnhancer.class)
                                       .disableEnhancer(AxonServerMultiTenancyConfigurationDefaults.class)
                                       .registerComponent(TenantProvider.class, config -> tenantProvider);
                               registerTenantComponents(registry, statisticsProvider, auditProvider);
                           })
                           .messaging(UniversityConfiguration::registerHandlers);
    }

    /**
     * Registers the Axon Server backed tenant-aware wiring on the given {@code configurer}: the
     * event-sourced course write side (which includes the enrollment command handler), one
     * {@link TenantComponentProvider} per tenant-scoped component type, and the statistics query handler.
     * <p>
     * The difference with {@link #configure(EventSourcingConfigurer, TenantProvider, TenantComponentProvider,
     * TenantComponentProvider)} is twofold. The source of the tenants: the Axon Server configuration
     * enhancer is left enabled, so the multi-tenancy enhancer registers its default auto-discovering
     * {@link AxonServerTenantProvider}. That provider watches Axon Server's contexts and registers each
     * as a tenant, filtering out the {@code _admin} context through its connect predicate. And the event
     * store: an {@link EventSourcingConfigurer} is used so the {@link CourseConfiguration course write
     * side} can be registered against the per-tenant, tenant-aware event store Axon Server provides.
     * Commands and queries still carry their tenant in metadata exactly as in the in-memory setup, so the
     * framework injects each context's per-tenant components and routes each tenant's events to its own
     * store.
     *
     * @param configurer         the configurer to extend
     * @param statisticsProvider the provider of the per-tenant course-statistics stores
     * @param auditProvider      the provider of the per-tenant audit logs
     */
    public static void configureForAxonServer(EventSourcingConfigurer configurer,
                                              TenantComponentProvider<CourseStatisticsStore> statisticsProvider,
                                              TenantComponentProvider<AuditLog> auditProvider) {
        CourseConfiguration.configure(configurer)
                           .componentRegistry(registry -> {
                               MultiTenancyEnabled.enableMultiTenancyEnhancer(registry);
                               // Leave the Axon Server enhancer enabled, so the multi-tenancy enhancer
                               // registers its default auto-discovering AxonServerTenantProvider and tenants
                               // are discovered from Axon Server's contexts rather than declared up front.
                               registerTenantComponents(registry, statisticsProvider, auditProvider);
                           })
                           .messaging(UniversityConfiguration::registerHandlers);
    }

    /**
     * Registers the two per-tenant component providers on the given {@code registry}.
     * <p>
     * The registration names only keep the two registrations distinct in the registry. A handler
     * parameter is still matched to a provider by the component type that provider produces. That is
     * why one provider per component type is fine, while two providers for the same type make a
     * parameter of that type ambiguous.
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

    /**
     * Registers the statistics query handler as an annotation-based handling component. The enrollment
     * command handler is registered with the event-sourced course by {@link CourseConfiguration}. The
     * tenant-descriptor interceptor the multi-tenancy enhancer installs runs for command and query
     * handlers alike, so both have their tenant resolved from the message metadata and their
     * {@code @TenantScoped} parameters injected with that tenant's per-tenant components, matched by type.
     *
     * @param configurer the configurer to extend
     */
    private static void registerHandlers(MessagingConfigurer configurer) {
        configurer.registerQueryHandlingModule(
                QueryHandlingModule.named("tenant-statistics")
                                   .queryHandlers()
                                   .autodetectedQueryHandlingComponent(
                                           config -> new TenantStatisticsQueryHandler()));
    }
}
