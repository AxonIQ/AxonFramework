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

import io.axoniq.framework.messaging.multitenancy.api.TenantComponentProvider;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.examples.demo.multitenancy.shared.DemoBacking;
import org.axonframework.examples.demo.multitenancy.shared.audit.AuditLog;
import org.axonframework.examples.demo.multitenancy.shared.tenant.DemoTenantProvider;
import org.axonframework.examples.demo.multitenancy.shared.tenant.TenantProvisioning;
import org.axonframework.examples.demo.multitenancy.shared.tenant.TenantSnapshots;
import org.axonframework.examples.demo.multitenancy.university.read.statistics.CourseStatisticsStore;
import org.axonframework.examples.demo.multitenancy.university.write.enrollstudent.CourseSnapshot;
import org.axonframework.examples.demo.multitenancy.university.write.enrollstudent.EnrollStudentConfiguration;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.eventhandling.processing.EventProcessor;
import org.axonframework.messaging.eventhandling.processing.streaming.StreamingEventProcessor;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;

import java.util.List;
import java.util.Objects;

/**
 * The started application {@link DemoLifecycle} drives, and everything it needs to drive it. Pick the factory
 * matching the backing the application was configured for.
 *
 * @param commandGateway     the gateway enrollments are sent on
 * @param queryGateway       the gateway statistics are read on
 * @param statisticsProvider the provider of the per-tenant course-statistics stores
 * @param auditProvider      the provider of the per-tenant audit logs
 * @param provisioning       how this run adds and removes tenants, and what backs it
 * @param snapshots          reads a single tenant's own snapshot store
 * @param processorNames     the names of every streaming event processor the application registered
 * @param shutdown           shuts the started application down
 * @author Laura Devriendt
 * @since 5.3.0
 */
public record DemoApplication(CommandGateway commandGateway,
                              QueryGateway queryGateway,
                              TenantComponentProvider<CourseStatisticsStore> statisticsProvider,
                              TenantComponentProvider<AuditLog> auditProvider,
                              TenantProvisioning provisioning,
                              TenantSnapshots<CourseSnapshot> snapshots,
                              List<String> processorNames,
                              Runnable shutdown) {

    /** Rejects a missing part, and copies the processor names so the record stays immutable. */
    public DemoApplication {
        Objects.requireNonNull(commandGateway, "The command gateway must not be null");
        Objects.requireNonNull(queryGateway, "The query gateway must not be null");
        Objects.requireNonNull(statisticsProvider, "The course-statistics provider must not be null");
        Objects.requireNonNull(auditProvider, "The audit-log provider must not be null");
        Objects.requireNonNull(provisioning, "The tenant provisioning must not be null");
        Objects.requireNonNull(snapshots, "The tenant snapshots must not be null");
        Objects.requireNonNull(shutdown, "The shutdown action must not be null");
        processorNames = List.copyOf(processorNames);
    }

    /**
     * The in-memory application, whose tenants are entries in the given {@code tenantProvider}.
     *
     * @param configuration      the started configuration to resolve the framework components from
     * @param tenantProvider     the in-memory provider supplying the tenants
     * @param statisticsProvider the provider of the per-tenant course-statistics stores
     * @param auditProvider      the provider of the per-tenant audit logs
     * @return the in-memory application to drive
     */
    public static DemoApplication inMemory(AxonConfiguration configuration,
                                           DemoTenantProvider tenantProvider,
                                           TenantComponentProvider<CourseStatisticsStore> statisticsProvider,
                                           TenantComponentProvider<AuditLog> auditProvider) {
        requireConfiguredFor(configuration, DemoBacking.IN_MEMORY);
        return new DemoApplication(configuration.getComponent(CommandGateway.class),
                                   configuration.getComponent(QueryGateway.class),
                                   statisticsProvider,
                                   auditProvider,
                                   TenantProvisioning.inMemory(tenantProvider),
                                   TenantSnapshots.inMemory(),
                                   registeredProcessorNames(configuration),
                                   configuration::shutdown);
    }

    /**
     * The Axon Server backed application, whose tenants are real contexts and which therefore gives each tenant
     * its own event store and snapshot store.
     *
     * @param configuration      the started configuration to resolve the framework components from
     * @param statisticsProvider the provider of the per-tenant course-statistics stores
     * @param auditProvider      the provider of the per-tenant audit logs
     * @param shutdown           shuts the started application down, which differs per entry point
     * @return the Axon Server backed application to drive
     */
    public static DemoApplication axonServer(AxonConfiguration configuration,
                                             TenantComponentProvider<CourseStatisticsStore> statisticsProvider,
                                             TenantComponentProvider<AuditLog> auditProvider,
                                             Runnable shutdown) {
        requireConfiguredFor(configuration, DemoBacking.AXON_SERVER);
        QualifiedName courseSnapshots = EnrollStudentConfiguration.courseSnapshotName(
                configuration.getComponent(MessageTypeResolver.class));
        return new DemoApplication(configuration.getComponent(CommandGateway.class),
                                   configuration.getComponent(QueryGateway.class),
                                   statisticsProvider,
                                   auditProvider,
                                   TenantProvisioning.axonServer(configuration, DemoLifecycle.KNOWN_TENANTS),
                                   TenantSnapshots.axonServer(configuration, courseSnapshots, CourseSnapshot.class),
                                   registeredProcessorNames(configuration),
                                   shutdown);
    }

    /**
     * The names of every streaming event processor the given {@code configuration} registered, sorted so a run
     * reports them predictably. A per-tenant implementation would have registered its processors here too, so
     * reading them shows whether one processor really served every tenant.
     */
    private static List<String> registeredProcessorNames(AxonConfiguration configuration) {
        return configuration.getComponents(StreamingEventProcessor.class)
                            .values()
                            .stream()
                            .map(EventProcessor::name)
                            .sorted()
                            .toList();
    }

    /**
     * Rejects assembling a run for one backing on a configuration built for the other, which would otherwise
     * surface as a puzzling outcome much later. A configuration built without a backing, as the Spring Boot
     * demo's beans are, is left alone.
     */
    private static void requireConfiguredFor(AxonConfiguration configuration, DemoBacking expected) {
        DemoBacking configured = configuration.getOptionalComponent(DemoBacking.class).orElse(expected);
        if (configured != expected) {
            throw new IllegalStateException(
                    "This configuration was built for the " + configured + " backing, so it cannot be driven as "
                            + expected + ". Configure and assemble the run for the same backing.");
        }
    }
}
