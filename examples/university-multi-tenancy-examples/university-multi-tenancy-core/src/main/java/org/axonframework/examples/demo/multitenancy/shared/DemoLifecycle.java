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
import org.axonframework.examples.demo.multitenancy.university.component.CourseStatisticsStore;
import org.axonframework.examples.demo.multitenancy.university.read.statistics.TenantStatistics;
import org.axonframework.examples.demo.multitenancy.university.read.statistics.TenantStatisticsQueryHandler;
import org.axonframework.examples.demo.multitenancy.university.write.course.EnrollStudent;
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
 * Configuration API or Spring Boot autoconfiguration. Only the surrounding configuration differs, so it
 * lives here, and each demo calls it with its own gateways, providers, provisioning, and shutdown.
 * <p>
 * A platform hosts several universities, each an isolated tenant. Enrolling a student is an
 * {@link EnrollStudent} command and reading a tenant's statistics is a
 * {@link TenantStatisticsQueryHandler} query. Enrolling both appends to the tenant's own event store and
 * updates that tenant's {@link CourseStatisticsStore} and {@link AuditLog}, injected by type, so one
 * command shows per-tenant event storage and per-tenant component injection together. {@link #run} reads
 * top to bottom as the story: tenants known at startup, a tenant added at runtime, an unknown tenant
 * rejected, a tenant removed, and shutdown.
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

    // Springfield always uses this identifier, and against Axon Server Shelbyville opens a course under the
    // same identifier to show the two event stores are isolated. Its capacity is the smallest that shows
    // fill-then-reject.
    private static final String SHARED_COURSE_ID = "cs-101";
    private static final int COURSE_CAPACITY = 2;
    // In memory there is one shared event store, so Shelbyville uses a distinct identifier to avoid
    // colliding with Springfield's course on that shared store.
    private static final String SHELBYVILLE_COURSE_ID = "law-200";
    // The runtime-added tenant uses its own identifier as well.
    private static final String OGDENVILLE_COURSE_ID = "econ-300";

    private DemoLifecycle() {
        // Utility class, not meant to be instantiated.
    }

    /**
     * Walks the whole tenant lifecycle against an already-started application and returns what it
     * observed, so a smoke test can assert the outcome through the same entry point a user runs. The
     * per-tenant components it reads for the cleanup checks are {@link AutoCloseable}, but the framework
     * closes them on tenant removal and shutdown, so this only reads their state.
     *
     * @param commandGateway     the gateway enrollments are sent on
     * @param queryGateway       the gateway statistics are read on
     * @param statisticsProvider the provider of the per-tenant course-statistics stores
     * @param auditProvider      the provider of the per-tenant audit logs
     * @param provisioning       how this run adds and removes tenants, and whether it has a per-tenant
     *                           event store (in memory or against Axon Server)
     * @param shutdown           shuts the started application down, which is where the framework closes
     *                           every remaining tenant's instances (the configuration or Spring context)
     * @return the observed outcome of the demo run
     */
    public static DemoOutcome run(CommandGateway commandGateway,
                                  QueryGateway queryGateway,
                                  TenantComponentProvider<CourseStatisticsStore> statisticsProvider,
                                  TenantComponentProvider<AuditLog> auditProvider,
                                  TenantProvisioning provisioning,
                                  Runnable shutdown) {
        Objects.requireNonNull(commandGateway, "The command gateway must not be null");
        Objects.requireNonNull(queryGateway, "The query gateway must not be null");
        Objects.requireNonNull(statisticsProvider, "The course-statistics provider must not be null");
        Objects.requireNonNull(auditProvider, "The audit-log provider must not be null");
        Objects.requireNonNull(provisioning, "The tenant provisioning must not be null");
        Objects.requireNonNull(shutdown, "The shutdown action must not be null");

        provisioning.prepareKnownTenants();
        logger.info("Providers subscribed at startup. Known tenants: {}", tenantIds(statisticsProvider));

        // 1. Enroll students in the tenants known at startup. Each enrollment both appends to the tenant's
        // own event store and updates that tenant's components, and against Axon Server the same course
        // identifier in two tenants shows the event stores are isolated.
        EventStorageOutcome eventStorage = enrollStudents(commandGateway, provisioning.hasPerTenantEventStore());
        logTenantView("Springfield University", queryGateway, SPRINGFIELD);
        logTenantView("Shelbyville University", queryGateway, SHELBYVILLE);

        // 2. Add a tenant at runtime. Its instances materialize on its first command, no config change.
        provisioning.addTenant(OGDENVILLE);
        enrollWhenTenantReady(commandGateway);
        logTenantView("Ogdenville University (added at runtime)", queryGateway, OGDENVILLE);

        // 3. A command for a tenant the application does not know is rejected.
        boolean unknownTenantRejected = unknownTenantIsRejected(commandGateway);

        // 4. Removing a tenant closes its per-tenant instances.
        boolean shelbyvilleClosedOnRemoval =
                removingTenantClosesItsInstances(provisioning, statisticsProvider, auditProvider);

        // 5. Shutting down closes every remaining tenant's instances.
        return shutDownAndBuildOutcome(shutdown, queryGateway, statisticsProvider, auditProvider,
                                       unknownTenantRejected, shelbyvilleClosedOnRemoval, eventStorage);
    }

    /**
     * Enrolls students in the tenants known at startup and returns what the per-tenant event storage
     * showed. Each enrollment is one command that both appends to the tenant's own event store, through the
     * event-sourced course it sources, and updates that tenant's {@link CourseStatisticsStore} and
     * {@link AuditLog}, injected by type.
     * <p>
     * Springfield fills its course to capacity. Shelbyville enrolls into its own course. Against Axon
     * Server that course carries the same identifier as Springfield's, so it demonstrates event-store
     * isolation: Shelbyville's enrollment is accepted even though Springfield's identically-named course
     * is full, and a further enrollment into Springfield's course is rejected as full. In memory there is
     * no per-tenant event store, so Shelbyville uses a distinct identifier and this isolation is not shown.
     *
     * @param commandGateway           the gateway enrollments are sent on
     * @param hasPerTenantEventStore   whether each tenant has its own event store (only against Axon Server)
     * @return what the event-storage isolation showed, or {@link EventStorageOutcome#notDemonstrated()} in memory
     */
    private static EventStorageOutcome enrollStudents(CommandGateway commandGateway, boolean hasPerTenantEventStore) {
        String shelbyvilleCourseId = hasPerTenantEventStore ? SHARED_COURSE_ID : SHELBYVILLE_COURSE_ID;

        Enrollments.openCourse(commandGateway, SPRINGFIELD, SHARED_COURSE_ID, COURSE_CAPACITY);
        Enrollments.enroll(commandGateway, SPRINGFIELD, SHARED_COURSE_ID, "alice");
        Enrollments.enroll(commandGateway, SPRINGFIELD, SHARED_COURSE_ID, "bob");

        Enrollments.openCourse(commandGateway, SHELBYVILLE, shelbyvilleCourseId, COURSE_CAPACITY);
        boolean shelbyvilleAccepted = Enrollments.tryEnroll(commandGateway, SHELBYVILLE, shelbyvilleCourseId, "carol");

        return hasPerTenantEventStore
                ? provePerTenantEventStoreIsolation(commandGateway, shelbyvilleAccepted)
                : EventStorageOutcome.notDemonstrated();
    }

    /**
     * Proves per-tenant event-store isolation, only against Axon Server where each tenant has its own
     * store. Springfield's course was just filled to capacity, so a further enrollment is rejected,
     * decided from Springfield's own events, while Shelbyville's identically-named course still accepted
     * its student. That the same course identifier behaves differently in the two tenants shows their
     * event stores are isolated.
     *
     * @param commandGateway                  the gateway enrollments are sent on
     * @param shelbyvilleAcceptedSameCourseId whether Shelbyville accepted an enrollment into the same
     *                                        course identifier Springfield filled
     * @return the observed event-storage isolation outcome
     */
    private static EventStorageOutcome provePerTenantEventStoreIsolation(CommandGateway commandGateway,
                                                                        boolean shelbyvilleAcceptedSameCourseId) {
        boolean springfieldRejectedWhenFull =
                !Enrollments.tryEnroll(commandGateway, SPRINGFIELD, SHARED_COURSE_ID, "frank");
        return EventStorageOutcome.demonstratedWith(springfieldRejectedWhenFull, shelbyvilleAcceptedSameCourseId);
    }

    /**
     * Enrolls in a tenant added at runtime, retrying until it is ready. Creating a tenant's context and
     * command bus connector is asynchronous, so the first command can arrive before the connector exists.
     * A failed attempt fails at dispatch without enrolling, and opening and enrolling are idempotent, so
     * the enrollment still lands exactly once.
     */
    private static void enrollWhenTenantReady(CommandGateway commandGateway) {
        Awaitility.await("tenant [" + DemoLifecycle.OGDENVILLE.tenantId() + "] ready for commands")
                  .atMost(Duration.ofSeconds(15))
                  .ignoreExceptionsMatching(Enrollments::causedByTenantNotReady)
                  .until(() -> {
                      Enrollments.openCourse(commandGateway, OGDENVILLE, OGDENVILLE_COURSE_ID, COURSE_CAPACITY);
                      Enrollments.enroll(commandGateway, OGDENVILLE, OGDENVILLE_COURSE_ID, "dan");
                      return true;
                  });
    }

    /**
     * Logs the given tenant's isolated view, read back through a tenant-scoped query. Guarded on the
     * log level, so the query and rendering only happen when info logging is on.
     */
    private static void logTenantView(String label, QueryGateway queryGateway, TenantDescriptor tenant) {
        if (logger.isInfoEnabled()) {
            logger.info("{}", TenantView.render(label, Enrollments.statistics(queryGateway, tenant)));
        }
    }

    /**
     * Sends an enrollment command for a tenant the application does not know and confirms it is
     * rejected, so that no instance is ever built for it.
     */
    private static boolean unknownTenantIsRejected(CommandGateway commandGateway) {
        boolean rejected;
        try {
            Enrollments.enroll(commandGateway, UNKNOWN, SHARED_COURSE_ID, "eve");
            rejected = false;
        } catch (RuntimeException e) {
            rejected = Enrollments.causedByTenantNotResolved(e);
        }
        logger.info("Command for an unknown tenant rejected: {}", rejected);
        return rejected;
    }

    /**
     * Removes Shelbyville through the {@code provisioning} and confirms both of its instances were
     * closed. The per-tenant components are {@link AutoCloseable} and closed by the framework on
     * removal, so this reads their state before and after removing the tenant.
     */
    private static boolean removingTenantClosesItsInstances(TenantProvisioning provisioning,
                                                            TenantComponentProvider<CourseStatisticsStore> statisticsProvider,
                                                            TenantComponentProvider<AuditLog> auditProvider) {
        CourseStatisticsStore courseStatisticsStore = statisticsProvider.componentFor(SHELBYVILLE);
        AuditLog auditLog = auditProvider.componentFor(SHELBYVILLE);
        provisioning.removeTenant(SHELBYVILLE);
        boolean closed = courseStatisticsStore.isClosed() && auditLog.isClosed();
        logger.info("Tenant [{}] removed. Its instances are closed: {}", SHELBYVILLE.tenantId(), closed);
        return closed;
    }

    /**
     * Reads the remaining tenants' totals back through queries, shuts the application down, and gathers
     * what the run observed into a {@link DemoOutcome}, confirming shutdown closed every still-registered
     * tenant's instances (the canceled provider subscriptions destroy them).
     */
    private static DemoOutcome shutDownAndBuildOutcome(Runnable shutdown,
                                                       QueryGateway queryGateway,
                                                       TenantComponentProvider<CourseStatisticsStore> statisticsProvider,
                                                       TenantComponentProvider<AuditLog> auditProvider,
                                                       boolean unknownTenantRejected,
                                                       boolean shelbyvilleClosedOnRemoval,
                                                       EventStorageOutcome eventStorage) {
        // Read the totals through queries while the application is still running.
        TenantStatistics springfield = Enrollments.statistics(queryGateway, SPRINGFIELD);
        int ogdenvilleEnrollments = Enrollments.statistics(queryGateway, OGDENVILLE).totalEnrollments();

        // Both components of every still-registered tenant should be closed once shutdown cancels the
        // provider subscriptions.
        List<CourseStatisticsStore> stores = List.of(
                statisticsProvider.componentFor(SPRINGFIELD),
                statisticsProvider.componentFor(OGDENVILLE));
        List<AuditLog> auditLogs = List.of(
                auditProvider.componentFor(SPRINGFIELD),
                auditProvider.componentFor(OGDENVILLE));
        shutdown.run();
        boolean allClosedOnShutdown = awaitClosed(() ->
                stores.stream().allMatch(CourseStatisticsStore::isClosed)
                        && auditLogs.stream().allMatch(AuditLog::isClosed));
        logger.info("Shutdown complete. All remaining tenant instances closed: {}", allClosedOnShutdown);

        return new DemoOutcome(springfield.totalEnrollments(),
                               springfield.auditEntries(),
                               ogdenvilleEnrollments,
                               unknownTenantRejected,
                               shelbyvilleClosedOnRemoval,
                               allClosedOnShutdown,
                               eventStorage);
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

    private static List<String> tenantIds(TenantComponentProvider<CourseStatisticsStore> statisticsProvider) {
        return statisticsProvider.tenants().stream().map(TenantDescriptor::tenantId).toList();
    }
}
