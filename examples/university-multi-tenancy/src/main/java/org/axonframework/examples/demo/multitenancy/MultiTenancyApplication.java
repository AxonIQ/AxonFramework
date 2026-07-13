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
import io.axoniq.framework.messaging.multitenancy.api.MetadataBasedTenantResolver;
import io.axoniq.framework.messaging.multitenancy.api.TenantComponentProvider;
import io.axoniq.framework.messaging.multitenancy.api.TenantDescriptor;
import io.axoniq.framework.messaging.multitenancy.api.TenantNotResolvedException;
import io.axoniq.framework.messaging.multitenancy.api.TenantProvider;
import org.awaitility.Awaitility;
import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.examples.demo.multitenancy.university.UniversityModuleConfiguration;
import org.axonframework.examples.demo.multitenancy.university.audit.AuditLog;
import org.axonframework.examples.demo.multitenancy.university.events.StudentEnrolledInCourse;
import org.axonframework.examples.demo.multitenancy.university.read.coursestats.CourseStatistics;
import org.axonframework.examples.demo.multitenancy.university.read.coursestats.CourseStatsProjection;
import org.axonframework.examples.demo.multitenancy.university.read.coursestats.CourseStatsRepository;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.SimpleEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Bootstraps the multi-tenancy demo.
 * <p>
 * A SaaS platform hosts several universities, each an isolated tenant. Two per-tenant components
 * are registered once (a {@link CourseStatsRepository} and an {@link AuditLog}), and enrolments are
 * published as events. The framework routes each event to the {@link CourseStatsProjection} with
 * both of the event's tenant instances injected, each matched by type, so no handler ever resolves
 * a tenant itself. This bootstrap walks the whole tenant lifecycle in one run and also shows the
 * two guardrails: an unknown tenant is rejected, and registering two providers for one type is
 * rejected at configuration time.
 * <p>
 * The demo runs entirely in memory. The Axon Server backed path, where each tenant is a real
 * context and enrolments arrive as routed messages, arrives with the command routing this demo
 * will grow into.
 */
public class MultiTenancyApplication {

    private static final Logger logger = LoggerFactory.getLogger(MultiTenancyApplication.class);

    private static final TenantDescriptor SPRINGFIELD = TenantDescriptor.tenantWithId("springfield");
    private static final TenantDescriptor SHELBYVILLE = TenantDescriptor.tenantWithId("shelbyville");
    private static final TenantDescriptor OGDENVILLE = TenantDescriptor.tenantWithId("ogdenville");
    private static final TenantDescriptor UNKNOWN = TenantDescriptor.tenantWithId("atlantis");

    private static final String COURSE_CS_101 = "cs-101";
    private static final String COURSE_LAW_200 = "law-200";

    /**
     * Entry point running the in-memory demo end to end.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        ConfigurationProperties props = ConfigurationProperties.load();
        if (props.axonServerEnabled()) {
            logger.info("Axon Server mode is not wired on this branch yet. Running the in-memory demo instead.");
        }
        new MultiTenancyApplication().run();
    }

    /**
     * Runs the in-memory demo end to end and returns what it observed, so callers (and the smoke
     * test) can assert the outcome through the same path a user runs. Reads as the sequence of steps
     * the demo performs; each step is a method below.
     *
     * @return the observed outcome of the demo run
     */
    public DemoOutcome run() {
        boolean ambiguousProvidersRejected = demonstrateAmbiguousProvidersRejected();

        SimpleEventBus eventBus = new SimpleEventBus();
        DemoTenantProvider tenantProvider = new DemoTenantProvider(SPRINGFIELD, SHELBYVILLE);
        TenantComponentProvider<CourseStatsRepository> statsProvider = UniversityModuleConfiguration.courseStatsProvider();
        TenantComponentProvider<AuditLog> auditProvider = UniversityModuleConfiguration.auditLogProvider();
        AxonConfiguration configuration = startConfiguration(eventBus, tenantProvider, statsProvider, auditProvider);

        boolean shutDown = false;
        try {
            logReplayedTenants(statsProvider);

            enrolStudents(eventBus, statsProvider);
            printTenantView("Springfield University", statsProvider, auditProvider, SPRINGFIELD);
            printTenantView("Shelbyville University", statsProvider, auditProvider, SHELBYVILLE);

            enrolInTenantAddedAtRuntime(eventBus, tenantProvider, statsProvider);
            printTenantView("Ogdenville University (added at runtime)", statsProvider, auditProvider, OGDENVILLE);

            boolean unknownTenantRejected = unknownTenantIsRejected(eventBus);
            boolean shelbyvilleClosedOnRemoval =
                    removingTenantClosesItsInstances(tenantProvider, statsProvider, auditProvider, SHELBYVILLE);

            int springfieldEnrolments = totalEnrolments(statsProvider.componentFor(SPRINGFIELD));
            int springfieldAuditEntries = auditProvider.componentFor(SPRINGFIELD).entries().size();
            boolean allClosedOnShutdown =
                    shutdownClosesRemainingInstances(configuration, statsProvider, SPRINGFIELD, OGDENVILLE);
            shutDown = true;

            return new DemoOutcome(springfieldEnrolments,
                                   springfieldAuditEntries,
                                   unknownTenantRejected,
                                   ambiguousProvidersRejected,
                                   shelbyvilleClosedOnRemoval,
                                   allClosedOnShutdown);
        } finally {
            if (!shutDown) {
                configuration.shutdown();
            }
        }
    }

    private static AxonConfiguration startConfiguration(SimpleEventBus eventBus,
                                                        DemoTenantProvider tenantProvider,
                                                        TenantComponentProvider<CourseStatsRepository> statsProvider,
                                                        TenantComponentProvider<AuditLog> auditProvider) {
        MessagingConfigurer configurer = MessagingConfigurer.create();
        UniversityModuleConfiguration.configure(configurer, eventBus, tenantProvider, statsProvider, auditProvider);
        AxonConfiguration configuration = configurer.build();
        configuration.start();
        return configuration;
    }

    private static void logReplayedTenants(TenantComponentProvider<CourseStatsRepository> statsProvider) {
        logger.info("Providers subscribed at startup. Tenants replayed to the provider: {}",
                    tenantIds(statsProvider.tenants()));
    }

    /**
     * Enrols students in the tenants known at startup. Each event is routed to the projection with
     * both the tenant's {@link CourseStatsRepository} and its {@link AuditLog} injected, matched by
     * parameter type.
     */
    private static void enrolStudents(SimpleEventBus eventBus,
                                      TenantComponentProvider<CourseStatsRepository> statsProvider) {
        enrol(eventBus, SPRINGFIELD, COURSE_CS_101, "alice");
        enrol(eventBus, SPRINGFIELD, COURSE_CS_101, "bob");
        enrol(eventBus, SHELBYVILLE, COURSE_LAW_200, "carol");
        awaitEnrolments(statsProvider, SPRINGFIELD, 2);
        awaitEnrolments(statsProvider, SHELBYVILLE, 1);
    }

    /**
     * Adds a tenant at runtime and enrols a student in it, showing that its instances materialize on
     * the first event without any configuration change.
     */
    private static void enrolInTenantAddedAtRuntime(SimpleEventBus eventBus,
                                                    DemoTenantProvider tenantProvider,
                                                    TenantComponentProvider<CourseStatsRepository> statsProvider) {
        tenantProvider.addTenant(OGDENVILLE);
        enrol(eventBus, OGDENVILLE, COURSE_CS_101, "dan");
        awaitEnrolments(statsProvider, OGDENVILLE, 1);
    }

    /**
     * Publishes an enrolment for a tenant the application does not know, and confirms it is rejected
     * with a {@link TenantNotResolvedException} so that no instance is ever built for it.
     */
    private static boolean unknownTenantIsRejected(SimpleEventBus eventBus) {
        boolean rejected = enrolExpectingRejection(eventBus, UNKNOWN, COURSE_CS_101, "eve");
        logger.info("Enrolment for an unknown tenant rejected with TenantNotResolvedException: {}", rejected);
        return rejected;
    }

    /**
     * Removes a tenant and confirms both of its {@link AutoCloseable} instances were closed.
     */
    private static boolean removingTenantClosesItsInstances(DemoTenantProvider tenantProvider,
                                                            TenantComponentProvider<CourseStatsRepository> statsProvider,
                                                            TenantComponentProvider<AuditLog> auditProvider,
                                                            TenantDescriptor tenant) {
        CourseStatsRepository statistics = statsProvider.componentFor(tenant);
        AuditLog auditLog = auditProvider.componentFor(tenant);
        tenantProvider.removeTenant(tenant);
        boolean closed = statistics.isClosed() && auditLog.isClosed();
        logger.info("Tenant [{}] removed. Its instances are closed: {}", tenant.tenantId(), closed);
        return closed;
    }

    /**
     * Shuts the configuration down and confirms every still-registered tenant's instance was closed,
     * as the cancelled provider subscriptions destroy them.
     */
    private static boolean shutdownClosesRemainingInstances(AxonConfiguration configuration,
                                                            TenantComponentProvider<CourseStatsRepository> statsProvider,
                                                            TenantDescriptor... tenants) {
        List<CourseStatsRepository> repositories = Arrays.stream(tenants).map(statsProvider::componentFor).toList();
        configuration.shutdown();
        Awaitility.await("shutdown cleanup")
                  .atMost(Duration.ofSeconds(5))
                  .until(() -> repositories.stream().allMatch(CourseStatsRepository::isClosed));
        boolean allClosed = repositories.stream().allMatch(CourseStatsRepository::isClosed);
        logger.info("Shutdown complete. All remaining tenant instances closed: {}", allClosed);
        return allClosed;
    }

    /**
     * Builds a throwaway configuration that registers two providers for the same component type and
     * checks that resolving a handler parameter of that type is rejected, since the framework cannot
     * know which instance to inject.
     *
     * @return {@code true} if the ambiguity was rejected with an {@link AxonConfigurationException}
     */
    private boolean demonstrateAmbiguousProvidersRejected() {
        DemoTenantProvider tenantProvider = new DemoTenantProvider(SPRINGFIELD);
        MessagingConfigurer configurer = MessagingConfigurer.create();
        configurer.componentRegistry(registry -> registry
                .disableEnhancer(AxonServerConfigurationEnhancer.class)
                .registerComponent(TenantProvider.class, config -> tenantProvider)
                // Two providers for CourseStatsRepository make that handler parameter ambiguous.
                .registerComponent(TenantComponentProvider.class, "courseStatsA",
                                   config -> UniversityModuleConfiguration.courseStatsProvider())
                .registerComponent(TenantComponentProvider.class, "courseStatsB",
                                   config -> UniversityModuleConfiguration.courseStatsProvider()));
        AxonConfiguration configuration = configurer.build();
        configuration.start();
        try {
            // Ask the parameter resolver to match the CourseStatsRepository parameter, exactly as
            // handler inspection does. With two providers of that type, it cannot choose one.
            ParameterResolverFactory resolverFactory = configuration.getComponent(ParameterResolverFactory.class);
            Method handler = CourseStatsProjection.class.getDeclaredMethod(
                    "on", StudentEnrolledInCourse.class, CourseStatsRepository.class, AuditLog.class);
            resolverFactory.createInstance(handler, handler.getParameters(), 1);
            logger.warn("Expected ambiguous providers to be rejected, but resolution succeeded.");
            return false;
        } catch (AxonConfigurationException e) {
            logger.info("Two providers for one component type rejected: {}", e.getMessage());
            return true;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Demo projection handler method not found.", e);
        } finally {
            configuration.shutdown();
        }
    }

    private static void enrol(SimpleEventBus eventBus, TenantDescriptor tenant, String courseId, String studentId) {
        // No active ProcessingContext: the event is published standalone, not from within a handler.
        eventBus.publish(null, enrolmentEvent(tenant, courseId, studentId))
                .orTimeout(5, TimeUnit.SECONDS)
                .join();
    }

    private static boolean enrolExpectingRejection(SimpleEventBus eventBus,
                                                   TenantDescriptor tenant,
                                                   String courseId,
                                                   String studentId) {
        try {
            // No active ProcessingContext: the event is published standalone, not from within a handler.
            eventBus.publish(null, enrolmentEvent(tenant, courseId, studentId))
                    .orTimeout(5, TimeUnit.SECONDS)
                    .join();
            return false;
        } catch (RuntimeException e) {
            return causedByTenantNotResolved(e);
        }
    }

    private static EventMessage enrolmentEvent(TenantDescriptor tenant, String courseId, String studentId) {
        return new GenericEventMessage(new MessageType(StudentEnrolledInCourse.class),
                                       new StudentEnrolledInCourse(courseId, studentId))
                .andMetadata(Map.of(MetadataBasedTenantResolver.DEFAULT_TENANT_KEY, tenant.tenantId()));
    }

    private static void awaitEnrolments(TenantComponentProvider<CourseStatsRepository> provider,
                                        TenantDescriptor tenant,
                                        int expected) {
        Awaitility.await("enrolments for " + tenant.tenantId())
                  .atMost(Duration.ofSeconds(5))
                  .until(() -> totalEnrolments(provider.componentFor(tenant)) >= expected);
    }

    /**
     * What a demo run observed, used to assert the outcome from the demo's own entry point.
     *
     * @param springfieldEnrolments      the enrolments recorded in Springfield's course-statistics repository
     * @param springfieldAuditEntries    the entries recorded in Springfield's audit log
     * @param unknownTenantRejected      whether an event for an unknown tenant was rejected
     * @param ambiguousProvidersRejected whether two providers for one type were rejected at configuration time
     * @param shelbyvilleClosedOnRemoval whether Shelbyville's instances were closed when its tenant was removed
     * @param allClosedOnShutdown        whether every remaining tenant's instances were closed on shutdown
     */
    public record DemoOutcome(int springfieldEnrolments,
                              int springfieldAuditEntries,
                              boolean unknownTenantRejected,
                              boolean ambiguousProvidersRejected,
                              boolean shelbyvilleClosedOnRemoval,
                              boolean allClosedOnShutdown) {

    }

    private static void printTenantView(String label,
                                        TenantComponentProvider<CourseStatsRepository> statsProvider,
                                        TenantComponentProvider<AuditLog> auditProvider,
                                        TenantDescriptor tenant) {
        CourseStatsRepository statistics = statsProvider.componentFor(tenant);
        AuditLog auditLog = auditProvider.componentFor(tenant);
        StringBuilder report = new StringBuilder("\n").append(label).append(":\n");
        List<CourseStatistics> statisticsPerCourse = statistics.statistics();
        if (statisticsPerCourse.isEmpty()) {
            report.append("  (no enrolments)\n");
        } else {
            statisticsPerCourse.forEach(statistic -> report.append("  - ")
                                                           .append(statistic.courseId())
                                                           .append(": ")
                                                           .append(statistic.enrolments())
                                                           .append(" enrolments\n"));
        }
        report.append("  audit entries: ").append(auditLog.entries().size()).append('\n');
        logger.info("{}", report);
    }

    private static List<String> tenantIds(List<TenantDescriptor> tenants) {
        return tenants.stream().map(TenantDescriptor::tenantId).toList();
    }

    private static int totalEnrolments(CourseStatsRepository repository) {
        return repository.statistics().stream().mapToInt(CourseStatistics::enrolments).sum();
    }

    private static boolean causedByTenantNotResolved(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof TenantNotResolvedException) {
                return true;
            }
        }
        return false;
    }
}
