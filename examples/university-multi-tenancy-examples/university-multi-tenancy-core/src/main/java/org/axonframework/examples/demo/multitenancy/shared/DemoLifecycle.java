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
import io.axoniq.framework.messaging.multitenancy.api.TenantDescriptor;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.axonframework.examples.demo.multitenancy.university.component.AuditLog;
import org.axonframework.examples.demo.multitenancy.university.component.CourseStatsStore;
import org.axonframework.examples.demo.multitenancy.university.component.InMemoryAuditLog;
import org.axonframework.examples.demo.multitenancy.university.component.InMemoryCourseStatsStore;
import org.axonframework.examples.demo.multitenancy.university.read.statistics.TenantStatistics;
import org.axonframework.examples.demo.multitenancy.university.read.statistics.TenantStatisticsQueryHandler;
import org.axonframework.examples.demo.multitenancy.university.write.enrol.EnrolStudentCommandHandler;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * The tenant lifecycle both demos walk through, once the application has been configured and started.
 * This is "what the app does", identical whether the application was assembled through the declarative
 * Configuration API or Spring Boot auto-configuration. Only the configuration around it differs, so it
 * lives here and each demo calls it with its own gateways, providers, provisioning, and shutdown.
 * <p>
 * A platform hosts several universities, each an isolated tenant. Enrolling a student is an
 * {@link EnrolStudentCommandHandler} command and reading a tenant's statistics is a
 * {@link TenantStatisticsQueryHandler} query; each carries its tenant in message metadata, and the
 * framework injects that tenant's {@link CourseStatsStore} and {@link AuditLog} into the handler,
 * matched by type. {@link #run} reads top to bottom as the story: tenants known at startup, a tenant
 * added at runtime, an unknown tenant rejected, a tenant removed, and shutdown.
 */
public final class DemoLifecycle {

    private static final Logger logger = LoggerFactory.getLogger(DemoLifecycle.class);

    /** Springfield University, a tenant known before the application starts. */
    public static final TenantDescriptor SPRINGFIELD = TenantDescriptor.tenantWithId("springfield");
    /** Shelbyville University, a tenant known before the application starts. */
    public static final TenantDescriptor SHELBYVILLE = TenantDescriptor.tenantWithId("shelbyville");
    /** Ogdenville University, the tenant added while the application is running. */
    public static final TenantDescriptor OGDENVILLE = TenantDescriptor.tenantWithId("ogdenville");
    /** A tenant the application never registers, used to show unknown tenants are rejected. */
    public static final TenantDescriptor UNKNOWN = TenantDescriptor.tenantWithId("atlantis");

    /** The tenants known before the application starts. */
    public static final List<TenantDescriptor> KNOWN_TENANTS = List.of(SPRINGFIELD, SHELBYVILLE);

    private static final String COURSE_CS_101 = "cs-101";
    private static final String COURSE_LAW_200 = "law-200";

    private DemoLifecycle() {
        // Utility class, not meant to be instantiated.
    }

    /**
     * Walks the whole tenant lifecycle against an already-started application and returns what it
     * observed, so a smoke test can assert the outcome through the same entry point a user runs. The
     * per-tenant components it reads for the cleanup checks are {@link AutoCloseable}, but the framework
     * closes them on tenant removal and shutdown, so this only reads their state.
     *
     * @param commandGateway the gateway enrolments are sent on
     * @param queryGateway   the gateway statistics are read on
     * @param statsProvider  the provider of the per-tenant course-statistics stores
     * @param auditProvider  the provider of the per-tenant audit logs
     * @param provisioning   how this run adds and removes tenants (in memory or against Axon Server)
     * @param shutdown       shuts the started application down, which is where the framework closes
     *                       every remaining tenant's instances (the configuration or Spring context)
     * @return the observed outcome of the demo run
     */
    public static DemoOutcome run(CommandGateway commandGateway,
                                  QueryGateway queryGateway,
                                  TenantComponentProvider<CourseStatsStore> statsProvider,
                                  TenantComponentProvider<AuditLog> auditProvider,
                                  TenantProvisioning provisioning,
                                  Runnable shutdown) {
        Objects.requireNonNull(commandGateway, "The command gateway must not be null");
        Objects.requireNonNull(queryGateway, "The query gateway must not be null");
        Objects.requireNonNull(statsProvider, "The course-statistics provider must not be null");
        Objects.requireNonNull(auditProvider, "The audit-log provider must not be null");
        Objects.requireNonNull(provisioning, "The tenant provisioning must not be null");
        Objects.requireNonNull(shutdown, "The shutdown action must not be null");

        provisioning.prepareKnownTenants();
        logger.info("Providers subscribed at startup. Known tenants: {}", tenantIds(statsProvider));

        // 1. Enrol students in the tenants known at startup and show each tenant sees only its own.
        enrolStudents(commandGateway);
        logTenantView("Springfield University", queryGateway, SPRINGFIELD);
        logTenantView("Shelbyville University", queryGateway, SHELBYVILLE);

        // 2. Add a tenant at runtime. Its instances materialize on its first command, no config change.
        provisioning.addTenant(OGDENVILLE);
        Enrolments.enrol(commandGateway, OGDENVILLE, COURSE_CS_101, "dan");
        logTenantView("Ogdenville University (added at runtime)", queryGateway, OGDENVILLE);

        // 3. A command for a tenant the application does not know is rejected.
        boolean unknownTenantRejected = unknownTenantIsRejected(commandGateway);

        // 4. Removing a tenant closes its per-tenant instances.
        boolean shelbyvilleClosedOnRemoval =
                removingTenantClosesItsInstances(provisioning, statsProvider, auditProvider);

        // 5. Shutting down closes every remaining tenant's instances.
        return shutDownAndBuildOutcome(shutdown, queryGateway, statsProvider, auditProvider,
                                       unknownTenantRejected, shelbyvilleClosedOnRemoval);
    }

    /**
     * Enrols students in the tenants known at startup. Each command is routed to the handler with both
     * the tenant's {@link CourseStatsStore} and its {@link AuditLog} injected, matched by type.
     */
    private static void enrolStudents(CommandGateway commandGateway) {
        Enrolments.enrol(commandGateway, SPRINGFIELD, COURSE_CS_101, "alice");
        Enrolments.enrol(commandGateway, SPRINGFIELD, COURSE_CS_101, "bob");
        Enrolments.enrol(commandGateway, SHELBYVILLE, COURSE_LAW_200, "carol");
    }

    /**
     * Logs the given tenant's isolated view, read back through a tenant-scoped query. Guarded on the
     * log level, so the query and rendering only happen when info logging is on.
     */
    private static void logTenantView(String label, QueryGateway queryGateway, TenantDescriptor tenant) {
        if (logger.isInfoEnabled()) {
            logger.info("{}", TenantView.render(label, Enrolments.statistics(queryGateway, tenant)));
        }
    }

    /**
     * Sends an enrolment command for a tenant the application does not know and confirms it is
     * rejected, so that no instance is ever built for it.
     */
    private static boolean unknownTenantIsRejected(CommandGateway commandGateway) {
        boolean rejected;
        try {
            Enrolments.enrol(commandGateway, UNKNOWN, COURSE_CS_101, "eve");
            rejected = false;
        } catch (RuntimeException e) {
            rejected = Enrolments.causedByTenantNotResolved(e);
        }
        logger.info("Command for an unknown tenant rejected: {}", rejected);
        return rejected;
    }

    /**
     * Removes Shelbyville through the {@code provisioning} and confirms both of its instances were
     * closed. The per-tenant components are {@link AutoCloseable} and closed by the framework on
     * removal, so this reads their state before and after removing the tenant.
     */
    // Holds the components to check isClosed() after removal. The framework, not this method, closes them.
    // The provider always yields the in-memory implementations, so their demo-only isClosed() is reachable.
    private static boolean removingTenantClosesItsInstances(TenantProvisioning provisioning,
                                                            TenantComponentProvider<CourseStatsStore> statsProvider,
                                                            TenantComponentProvider<AuditLog> auditProvider) {
        InMemoryCourseStatsStore statistics = (InMemoryCourseStatsStore) statsProvider.componentFor(SHELBYVILLE);
        InMemoryAuditLog auditLog = (InMemoryAuditLog) auditProvider.componentFor(SHELBYVILLE);
        provisioning.removeTenant(SHELBYVILLE);
        boolean closed = statistics.isClosed() && auditLog.isClosed();
        logger.info("Tenant [{}] removed. Its instances are closed: {}", SHELBYVILLE.tenantId(), closed);
        return closed;
    }

    /**
     * Reads the remaining tenants' totals back through queries, shuts the application down, and gathers
     * what the run observed into a {@link DemoOutcome}, confirming shutdown closed every still-registered
     * tenant's instances (the canceled provider subscriptions destroy them).
     */
    // Holds the components to check isClosed() after shutdown. The framework, not this method, closes them.
    @SuppressWarnings("resource")
    private static DemoOutcome shutDownAndBuildOutcome(Runnable shutdown,
                                                       QueryGateway queryGateway,
                                                       TenantComponentProvider<CourseStatsStore> statsProvider,
                                                       TenantComponentProvider<AuditLog> auditProvider,
                                                       boolean unknownTenantRejected,
                                                       boolean shelbyvilleClosedOnRemoval) {
        // Read the totals through queries while the application is still running.
        TenantStatistics springfield = Enrolments.statistics(queryGateway, SPRINGFIELD);
        int ogdenvilleEnrolments = Enrolments.statistics(queryGateway, OGDENVILLE).totalEnrolments();

        // Both components of every still-registered tenant should be closed once shutdown cancels the
        // provider subscriptions. The provider always yields the in-memory implementations.
        List<InMemoryCourseStatsStore> stores = List.of(
                (InMemoryCourseStatsStore) statsProvider.componentFor(SPRINGFIELD),
                (InMemoryCourseStatsStore) statsProvider.componentFor(OGDENVILLE));
        List<InMemoryAuditLog> auditLogs = List.of(
                (InMemoryAuditLog) auditProvider.componentFor(SPRINGFIELD),
                (InMemoryAuditLog) auditProvider.componentFor(OGDENVILLE));
        shutdown.run();
        boolean allClosedOnShutdown = awaitClosed(() ->
                stores.stream().allMatch(InMemoryCourseStatsStore::isClosed)
                        && auditLogs.stream().allMatch(InMemoryAuditLog::isClosed));
        logger.info("Shutdown complete. All remaining tenant instances closed: {}", allClosedOnShutdown);

        return new DemoOutcome(springfield.totalEnrolments(),
                               springfield.auditEntries(),
                               ogdenvilleEnrolments,
                               unknownTenantRejected,
                               shelbyvilleClosedOnRemoval,
                               allClosedOnShutdown);
    }

    /**
     * Waits until the given {@code closed} condition holds, returning whether it did within the timeout.
     * Unlike a bare {@code await(...).until(...)}, this returns {@code false} on timeout rather than
     * throwing, so the demo can report the cleanup outcome instead of failing opaquely.
     */
    private static boolean awaitClosed(BooleanSupplier closed) {
        try {
            Awaitility.await("shutdown cleanup")
                      .atMost(Duration.ofSeconds(5))
                      .until(closed::getAsBoolean);
            return true;
        } catch (ConditionTimeoutException e) {
            return false;
        }
    }

    private static List<String> tenantIds(TenantComponentProvider<CourseStatsStore> statsProvider) {
        return statsProvider.tenants().stream().map(TenantDescriptor::tenantId).toList();
    }
}
