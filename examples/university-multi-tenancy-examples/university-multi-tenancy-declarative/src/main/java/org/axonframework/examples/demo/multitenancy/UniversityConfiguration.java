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
import io.axoniq.framework.messaging.multitenancy.axonserver.configuration.AxonServerMultiTenancyConfigurationDefaults;
import io.axoniq.framework.messaging.multitenancy.axonserver.queryhandling.MultiTenantAxonServerQueryBusConnector;
import io.axoniq.framework.messaging.multitenancy.configuration.MultiTenantStreamingProcessorRestartConfiguration;
import io.axoniq.framework.messaging.queryhandling.distributed.DistributedQueryBusConfiguration;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.eventsourcing.snapshot.inmemory.InMemorySnapshotStore;
import org.axonframework.eventsourcing.snapshot.store.SnapshotStore;
import org.axonframework.examples.demo.multitenancy.shared.DemoBacking;
import org.axonframework.examples.demo.multitenancy.shared.EventProcessingStyle;
import org.axonframework.examples.demo.multitenancy.shared.audit.AuditLog;
import org.axonframework.examples.demo.multitenancy.university.UniversityModuleConfiguration;
import org.axonframework.examples.demo.multitenancy.university.read.statistics.CourseStatisticsStore;

import java.time.Duration;

/**
 * Wires the university's tenant-aware pieces onto an {@link EventSourcingConfigurer}, the declarative
 * Configuration API's equivalent of what Spring Boot auto-configuration does for the Spring Boot demo.
 * <p>
 * The domain itself, the write slices and the statistics read slice, is registered by
 * {@link UniversityModuleConfiguration}, shared with the Spring Boot demo. This class only adds the
 * multi-tenancy wiring around it. Multi-tenancy itself is active because the {@code axoniq-multi-tenancy}
 * module is on the classpath, so all this class registers is one {@link TenantComponentProvider} per
 * tenant-scoped component type. The framework then hands each handler the components of the message's
 * tenant, matched by type, and routes the course's events to that tenant's own event store.
 */
public final class UniversityConfiguration {

    // The framework's own default.
    private static final Duration PROCESSOR_RESTART_TIMEOUT = Duration.ofSeconds(30);

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
     * <p>
     * That single shared event store is also why this path fills the read model
     * inline rather than from a projection. An event streamed from a store
     * every tenant shares cannot be attributed to one tenant, so tenant-aware event processing needs the
     * per-tenant event stores only {@link #configureForAxonServer} has.
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
        UniversityModuleConfiguration.configure(configurer, DemoBacking.IN_MEMORY)
                                     .componentRegistry(registry -> {
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
     * The Axon Server configuration enhancer is left enabled, so multi-tenancy uses its default
     * auto-discovering tenant provider watching Axon Server's contexts, and the course write side
     * is registered against the per-tenant, tenant-aware event store Axon Server provides. This path needs
     * a running multi-context (Enterprise Edition) Axon Server.
     * <p>
     * Because every tenant has its own event store here, the framework knows which tenant a streamed event
     * came from, so this path fills the read model from a projection. One ordinary event processor consumes
     * every tenant's events, run in the given {@code streamingMode}, and no multi-tenancy wiring is needed to
     * make it tenant-aware.
     * <p>
     * This path also routes direct queries through the per-tenant connector rather than serving them from a
     * locally subscribed handler, so that {@code query()} dispatch is really exercised, not only made
     * tenant-aware once handling starts.
     *
     * @param configurer         the configurer to extend
     * @param statisticsProvider the provider of the per-tenant course-statistics stores
     * @param auditProvider      the provider of the per-tenant audit logs
     * @param streamingMode      how the projection processor is fed
     */
    public static void configureForAxonServer(EventSourcingConfigurer configurer,
                                              TenantComponentProvider<CourseStatisticsStore> statisticsProvider,
                                              TenantComponentProvider<AuditLog> auditProvider,
                                              EventProcessingStyle streamingMode) {
        UniversityModuleConfiguration.configure(configurer, DemoBacking.AXON_SERVER, streamingMode)
                                     .componentRegistry(registry -> {
                                         registerTenantComponents(registry, statisticsProvider, auditProvider);
                                         if (streamingMode == EventProcessingStyle.POOLED_STREAMING) {
                                             registerProcessorRestartTimeout(registry);
                                         }
                                         registerDirectQueryRouting(registry);
                                     });
    }

    /**
     * Registers how long each processor gets to stop and start again when the set of tenants changes.
     * <p>
     * A tenant change restarts the running streaming event processors, and this bounds how long each one gets.
     * The framework already registers a default, so raise this only for a deployment whose processors are slow
     * to stop and start. Shown here at the default value, so the knob is visible without changing the demo.
     *
     * @param registry the registry to register the restart configuration on
     */
    private static void registerProcessorRestartTimeout(ComponentRegistry registry) {
        registry.registerComponent(MultiTenantStreamingProcessorRestartConfiguration.class,
                                   config -> MultiTenantStreamingProcessorRestartConfiguration.DEFAULT
                                           .restartTimeout(PROCESSOR_RESTART_TIMEOUT));
    }

    /**
     * Turns off preferring a locally subscribed query handler, so a direct query is routed through the
     * per-tenant {@link MultiTenantAxonServerQueryBusConnector} rather than served from the local segment. See
     * {@link DistributedQueryBusConfiguration#preferLocalQueryHandler(boolean)} for what the setting does.
     * <p>
     * This demo runs in one process, where a query handler is always subscribed locally, so without this the
     * connector is never exercised and the routing this demo is about would go unshown. Correctness does not
     * depend on it. A subscription query always routes through the connector regardless.
     *
     * @param registry the registry to register the distributed query bus configuration on
     */
    private static void registerDirectQueryRouting(ComponentRegistry registry) {
        registry.registerComponent(DistributedQueryBusConfiguration.class,
                                   config -> DistributedQueryBusConfiguration.DEFAULT
                                           .preferLocalQueryHandler(false));
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
