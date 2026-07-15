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
import io.axoniq.framework.messaging.multitenancy.api.TenantDescriptor;
import io.axoniq.framework.messaging.multitenancy.axonserver.AxonServerTenantProvider;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.examples.demo.multitenancy.scaffolding.ConfigurationProperties;
import org.axonframework.examples.demo.multitenancy.scaffolding.DemoOutcome;
import org.axonframework.examples.demo.multitenancy.scaffolding.DemoTenantProvider;
import org.axonframework.examples.demo.multitenancy.scaffolding.Enrolments;
import org.axonframework.examples.demo.multitenancy.scaffolding.ProviderAmbiguityGuardrail;
import org.axonframework.examples.demo.multitenancy.scaffolding.TenantProvisioning;
import org.axonframework.examples.demo.multitenancy.scaffolding.TenantView;
import org.axonframework.examples.demo.multitenancy.university.UniversityModuleConfiguration;
import org.axonframework.examples.demo.multitenancy.university.audit.AuditLog;
import org.axonframework.examples.demo.multitenancy.university.read.coursestats.CourseStatsProjection;
import org.axonframework.examples.demo.multitenancy.university.read.coursestats.CourseStatsRepository;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.eventhandling.EventSink;
import org.axonframework.messaging.eventhandling.SimpleEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Bootstraps the multi-tenancy demo.
 * <p>
 * A platform hosts several universities, each an isolated tenant. The feature this demonstrates
 * is that a per-tenant component is registered once and the framework hands each message handler the
 * instance belonging to the tenant of the message it is handling, so no handler ever resolves a tenant
 * itself. Here two such parts are registered (a {@link CourseStatsRepository} and an
 * {@link AuditLog}), enrolments are published as events carrying their tenant in metadata, and the
 * framework routes each event to the {@link CourseStatsProjection} with both of that tenant's instances
 * injected, matched by type.
 * <p>
 * {@link #runLifecycle} reads top to bottom as the story the demo tells: tenants known at startup,
 * a tenant added at runtime, an unknown tenant rejected, a tenant removed, and shutdown. The
 * supporting cast lives in small classes next to this one, so this file stays the lesson:
 * <ul>
 *     <li>{@link TenantProvisioning} is where the tenants come from, and the only thing that differs between
 *     the two runs.</li>
 *     <li>{@link Enrolments} publishes the enrolment events and reads them back.</li>
 *     <li>{@link TenantView} renders one tenant's isolated view.</li>
 *     <li>{@link ProviderAmbiguityGuardrail} demonstrates the configuration-time guardrail.</li>
 * </ul>
 * <p>
 * The same lifecycle runs two ways, selected by the {@code axon.server.enabled} toggle: in memory by
 * default (tenants from a {@link DemoTenantProvider}), or against Axon Server (tenants are real
 * contexts the {@link AxonServerTenantProvider} sources). Either way the enrolments flow through the
 * same {@link SimpleEventBus}, so only the {@link TenantProvisioning} changes.
 */
public class MultiTenancyApplication {

    private static final Logger logger = LoggerFactory.getLogger(MultiTenancyApplication.class);

    private static final TenantDescriptor SPRINGFIELD = TenantDescriptor.tenantWithId("springfield");
    private static final TenantDescriptor SHELBYVILLE = TenantDescriptor.tenantWithId("shelbyville");
    private static final TenantDescriptor OGDENVILLE = TenantDescriptor.tenantWithId("ogdenville");
    private static final TenantDescriptor UNKNOWN = TenantDescriptor.tenantWithId("atlantis");

    // The tenants known before the application starts.
    private static final List<TenantDescriptor> KNOWN_TENANTS = List.of(SPRINGFIELD, SHELBYVILLE);
    // On the Axon Server run these are the predefined context names the AxonServerTenantProvider uses.
    private static final String PREDEFINED_CONTEXTS = String.join(",", SPRINGFIELD.tenantId(), SHELBYVILLE.tenantId());

    private static final String COURSE_CS_101 = "cs-101";
    private static final String COURSE_LAW_200 = "law-200";

    /**
     * Entry point running the demo end to end, in memory by default or against Axon Server when the
     * {@code axon.server.enabled} property is set.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        ConfigurationProperties properties = ConfigurationProperties.load();
        MultiTenancyApplication demo = new MultiTenancyApplication();
        DemoOutcome outcome = properties.axonServerEnabled() ? demo.runWithAxonServer() : demo.run();
        logger.info("Demo finished. Outcome: {}", outcome);
    }

    /**
     * Runs the in-memory demo end to end, with the tenants supplied by an in-memory
     * {@link DemoTenantProvider}, and returns what it observed so the smoke test can assert the outcome
     * through the same entry point a user runs.
     *
     * @return the observed outcome of the demo run
     */
    public DemoOutcome run() {
        SimpleEventBus eventBus = new SimpleEventBus();
        DemoTenantProvider tenantProvider = new DemoTenantProvider(SPRINGFIELD, SHELBYVILLE);
        TenantComponentProvider<CourseStatsRepository> statsProvider = UniversityModuleConfiguration.courseStatsProvider();
        TenantComponentProvider<AuditLog> auditProvider = UniversityModuleConfiguration.auditLogProvider();

        MessagingConfigurer configurer = MessagingConfigurer.create();
        UniversityModuleConfiguration.configure(configurer, eventBus, tenantProvider, statsProvider, auditProvider);
        AxonConfiguration configuration = configurer.build();

        return runLifecycle(eventBus, configuration, statsProvider, auditProvider,
                            config -> TenantProvisioning.inMemory(tenantProvider));
    }

    /**
     * Runs the demo end to end against Axon Server, sourcing the tenants from Axon Server contexts
     * rather than from an in-memory provider. The lifecycle is identical to {@link #run()}. Only the
     * {@link TenantProvisioning} differs. This path needs a running multi-context (Enterprise Edition) Axon
     * Server, reachable on its default {@code localhost} address.
     *
     * @return the observed outcome of the demo run
     */
    public DemoOutcome runWithAxonServer() {
        SimpleEventBus eventBus = new SimpleEventBus();
        TenantComponentProvider<CourseStatsRepository> statsProvider = UniversityModuleConfiguration.courseStatsProvider();
        TenantComponentProvider<AuditLog> auditProvider = UniversityModuleConfiguration.auditLogProvider();

        MessagingConfigurer configurer = MessagingConfigurer.create();
        UniversityModuleConfiguration.configureForAxonServer(configurer, eventBus, PREDEFINED_CONTEXTS,
                                                             statsProvider, auditProvider);
        AxonConfiguration configuration = configurer.build();

        return runLifecycle(eventBus, configuration, statsProvider, auditProvider,
                            config -> TenantProvisioning.axonServer(config, KNOWN_TENANTS));
    }

    /**
     * Starts the given {@code configuration} and walks the whole tenant lifecycle, returning what it
     * observed. This is the story both runs share. Only the {@code provisioning} differs. The per-tenant
     * components it reads are {@link AutoCloseable}, but the framework closes them on tenant removal and
     * shutdown, so this only reads their state.
     *
     * @param eventSink      the sink enrolments are published on
     * @param configuration  the built, not-yet-started configuration to run
     * @param statsProvider  the provider of the per-tenant course-statistics repositories
     * @param auditProvider  the provider of the per-tenant audit logs
     * @param provisioningFactory builds the tenant provisioning for this run once the configuration is
     *                       started (the Axon Server provisioning resolves its components from it)
     * @return the observed outcome of the demo run
     */
    private DemoOutcome runLifecycle(EventSink eventSink,
                                     AxonConfiguration configuration,
                                     TenantComponentProvider<CourseStatsRepository> statsProvider,
                                     TenantComponentProvider<AuditLog> auditProvider,
                                     Function<AxonConfiguration, TenantProvisioning> provisioningFactory) {
        // Guardrail, shown up front and independent of the run: two providers for one component type
        // are rejected at configuration time.
        boolean ambiguousProvidersRejected = ProviderAmbiguityGuardrail.rejectsTwoProvidersForOneType();

        configuration.start();
        TenantProvisioning provisioning = provisioningFactory.apply(configuration);

        boolean shutDown = false;
        try {
            provisioning.prepareKnownTenants();
            logger.info("Providers subscribed at startup. Known tenants: {}", tenantIds(statsProvider));

            // 1. Enrol students in the tenants known at startup and show each tenant sees only its own.
            enrolStudents(eventSink, statsProvider);
            logTenantView("Springfield University", statsProvider, auditProvider, SPRINGFIELD);
            logTenantView("Shelbyville University", statsProvider, auditProvider, SHELBYVILLE);

            // 2. Add a tenant at runtime. Its instances materialize on its first event, no config change.
            provisioning.addTenant(OGDENVILLE);
            Enrolments.enrol(eventSink, OGDENVILLE, COURSE_CS_101, "dan");
            Enrolments.awaitEnrolments(statsProvider, OGDENVILLE, 1);
            logTenantView("Ogdenville University (added at runtime)", statsProvider, auditProvider, OGDENVILLE);

            // 3. An enrolment for a tenant the application does not know is rejected.
            boolean unknownTenantRejected = unknownTenantIsRejected(eventSink);

            // 4. Removing a tenant closes its per-tenant instances.
            boolean shelbyvilleClosedOnRemoval =
                    removingTenantClosesItsInstances(provisioning, statsProvider, auditProvider);

            // 5. Shutting down closes every remaining tenant's instances. Capture the outcome and stop.
            DemoOutcome outcome = shutDownAndBuildOutcome(configuration, statsProvider, auditProvider,
                                                          unknownTenantRejected, ambiguousProvidersRejected,
                                                          shelbyvilleClosedOnRemoval);
            shutDown = true;
            return outcome;
        } finally {
            if (!shutDown) {
                configuration.shutdown();
            }
        }
    }

    /**
     * Enrols students in the tenants known at startup. Each event is routed to the projection with both
     * the tenant's {@link CourseStatsRepository} and its {@link AuditLog} injected, matched by type.
     */
    private static void enrolStudents(EventSink eventSink,
                                      TenantComponentProvider<CourseStatsRepository> statsProvider) {
        Enrolments.enrol(eventSink, SPRINGFIELD, COURSE_CS_101, "alice");
        Enrolments.enrol(eventSink, SPRINGFIELD, COURSE_CS_101, "bob");
        Enrolments.enrol(eventSink, SHELBYVILLE, COURSE_LAW_200, "carol");
        Enrolments.awaitEnrolments(statsProvider, SPRINGFIELD, 2);
        Enrolments.awaitEnrolments(statsProvider, SHELBYVILLE, 1);
    }

    /**
     * Logs the given tenant's isolated view. Guarded on the log level, so the view (which walks the
     * tenant's components) is only rendered when info logging is on.
     */
    private static void logTenantView(String label,
                                      TenantComponentProvider<CourseStatsRepository> statsProvider,
                                      TenantComponentProvider<AuditLog> auditProvider,
                                      TenantDescriptor tenant) {
        if (logger.isInfoEnabled()) {
            logger.info("{}", TenantView.render(label, statsProvider, auditProvider, tenant));
        }
    }

    /**
     * Publishes an enrolment for a tenant the application does not know and confirms it is rejected, so
     * that no instance is ever built for it.
     */
    private static boolean unknownTenantIsRejected(EventSink eventSink) {
        boolean rejected;
        try {
            Enrolments.enrol(eventSink, UNKNOWN, COURSE_CS_101, "eve");
            rejected = false;
        } catch (RuntimeException e) {
            rejected = Enrolments.causedByTenantNotResolved(e);
        }
        logger.info("Enrolment for an unknown tenant rejected: {}", rejected);
        return rejected;
    }

    /**
     * Removes Shelbyville through the {@code provisioning} and confirms both of its instances were closed.
     * The per-tenant components are {@link AutoCloseable} and closed by the framework on removal, so
     * this reads their state before and after removing the tenant.
     */
    // Holds the components to check isClosed() after removal. The framework, not this method, closes them.
    private static boolean removingTenantClosesItsInstances(TenantProvisioning provisioning,
                                                            TenantComponentProvider<CourseStatsRepository> statsProvider,
                                                            TenantComponentProvider<AuditLog> auditProvider) {
        CourseStatsRepository statistics = statsProvider.componentFor(SHELBYVILLE);
        AuditLog auditLog = auditProvider.componentFor(SHELBYVILLE);
        provisioning.removeTenant(SHELBYVILLE);
        boolean closed = statistics.isClosed() && auditLog.isClosed();
        logger.info("Tenant [{}] removed. Its instances are closed: {}", SHELBYVILLE.tenantId(), closed);
        return closed;
    }

    /**
     * Shuts the configuration down and gathers what the run observed into a {@link DemoOutcome}, reading
     * Springfield's totals and confirming shutdown closed every still-registered tenant's instances (the
     * canceled provider subscriptions destroy them).
     */
    // Holds the components to check isClosed() after shutdown. The framework, not this method, closes them.
    @SuppressWarnings("resource")
    private static DemoOutcome shutDownAndBuildOutcome(AxonConfiguration configuration,
                                                       TenantComponentProvider<CourseStatsRepository> statsProvider,
                                                       TenantComponentProvider<AuditLog> auditProvider,
                                                       boolean unknownTenantRejected,
                                                       boolean ambiguousProvidersRejected,
                                                       boolean shelbyvilleClosedOnRemoval) {
        int springfieldEnrolments = Enrolments.totalEnrolments(statsProvider.componentFor(SPRINGFIELD));
        int springfieldAuditEntries = auditProvider.componentFor(SPRINGFIELD).entries().size();
        int ogdenvilleEnrolments = Enrolments.totalEnrolments(statsProvider.componentFor(OGDENVILLE));

        // Both components of every still-registered tenant should be closed once shutdown cancels the
        // provider subscriptions.
        List<CourseStatsRepository> repositories =
                List.of(statsProvider.componentFor(SPRINGFIELD), statsProvider.componentFor(OGDENVILLE));
        List<AuditLog> auditLogs =
                List.of(auditProvider.componentFor(SPRINGFIELD), auditProvider.componentFor(OGDENVILLE));
        configuration.shutdown();
        boolean allClosedOnShutdown = awaitClosed(() ->
                repositories.stream().allMatch(CourseStatsRepository::isClosed)
                        && auditLogs.stream().allMatch(AuditLog::isClosed));
        logger.info("Shutdown complete. All remaining tenant instances closed: {}", allClosedOnShutdown);

        return new DemoOutcome(springfieldEnrolments,
                               springfieldAuditEntries,
                               ogdenvilleEnrolments,
                               unknownTenantRejected,
                               ambiguousProvidersRejected,
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

    private static List<String> tenantIds(TenantComponentProvider<CourseStatsRepository> statsProvider) {
        return statsProvider.tenants().stream().map(TenantDescriptor::tenantId).toList();
    }
}
