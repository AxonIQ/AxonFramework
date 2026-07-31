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
import io.axoniq.framework.messaging.multitenancy.api.TenantDescriptor;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.axonframework.examples.demo.multitenancy.shared.audit.AuditLog;
import org.axonframework.examples.demo.multitenancy.shared.messaging.Enrollments;
import org.axonframework.examples.demo.multitenancy.shared.tenant.TenantProvisioning;
import org.axonframework.examples.demo.multitenancy.shared.tenant.TenantSnapshots;
import org.axonframework.examples.demo.multitenancy.university.read.statistics.CourseStatisticsProjection;
import org.axonframework.examples.demo.multitenancy.university.read.statistics.CourseStatisticsStore;
import org.axonframework.examples.demo.multitenancy.university.read.statistics.GetTenantStatistics;
import org.axonframework.examples.demo.multitenancy.university.read.statistics.ReadModelWrites;
import org.axonframework.examples.demo.multitenancy.university.read.statistics.StatisticsConfiguration;
import org.axonframework.examples.demo.multitenancy.university.read.statistics.TenantStatistics;
import org.axonframework.examples.demo.multitenancy.university.read.statistics.TenantStatisticsQueryHandler;
import org.axonframework.examples.demo.multitenancy.university.write.enrollstudent.CourseSnapshot;
import org.axonframework.examples.demo.multitenancy.university.write.enrollstudent.EnrollStudent;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

/**
 * The tenant lifecycle both demos walk through, once the application has been configured and started.
 * This is "what the app does", identical whether the application was assembled through the declarative
 * Configuration API or Spring Boot autoconfiguration. Only the surrounding configuration differs, so it
 * lives here, and each demo calls it with its own gateways, providers, provisioning, and shutdown.
 * <p>
 * A platform hosts several universities, each an isolated tenant. Enrolling a student is an
 * {@link EnrollStudent} command and reading a tenant's statistics is a
 * {@link TenantStatisticsQueryHandler} query. An enrollment appends to the tenant's own event store, and
 * enrolling enough students also snapshots the course, into that same tenant's own snapshot store. Each
 * tenant's {@link CourseStatisticsStore} and {@link AuditLog} are injected by type, into the command
 * handler, the query handler, and the {@link CourseStatisticsProjection} alike.
 * <p>
 * Where each tenant has its own event store, the projection is where the tenants come back together: one
 * ordinary pooled streaming event processor consumes every tenant's events and writes each into the read model
 * of the tenant it came from. A read model is then eventually consistent rather than written by the command, so
 * every observation of one below waits for it to catch up. On a shared event store the command handler fills it
 * instead, and those waits return at once.
 * <p>
 * Either way, recording an enrollment is also the one place that emits an update for any of that tenant's open
 * {@link GetTenantStatistics} subscription queries, and that completes them once none of that tenant's courses
 * has a seat left, through {@link ReadModelWrites}. Subscribing, emitting, and completing all name no tenant,
 * and still each subscription only ever receives its own tenant's updates, and only the tenant that ran out of
 * seats sees its subscription completed.
 * <p>
 * {@link #run} reads top to bottom as the story: tenants known at startup, their subscriptions proven isolated,
 * the course snapshotted per tenant, a tenant added at runtime, one processor serving all of them, an unknown
 * tenant rejected on both the command and the query side, a tenant removed, and shutdown.
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
    // same identifier to show the two event stores are isolated.
    private static final String SHARED_COURSE_ID = "cs-101";
    // How many students each tenant known at startup enrolls. Fixed by openCourseAndEnroll, which sends exactly
    // this many, so the capacities below are expressed against it rather than the other way round.
    private static final int STUDENTS_PER_KNOWN_TENANT = 2;
    // Springfield offers exactly the seats it fills, so its course ends up full and a further enrollment is
    // rejected.
    private static final int SPRINGFIELD_COURSE_CAPACITY = STUDENTS_PER_KNOWN_TENANT;
    // Shelbyville offers one seat more, so its course still has room once its students are enrolled. That
    // difference is what makes subscription completion observable as something scoped to one tenant.
    private static final int SHELBYVILLE_COURSE_CAPACITY = STUDENTS_PER_KNOWN_TENANT + 1;
    // In memory there is one shared event store, so Shelbyville uses a distinct identifier to avoid
    // colliding with Springfield's course on that shared store.
    private static final String SHELBYVILLE_COURSE_ID = "law-200";
    // The runtime-added tenant uses its own identifier as well, and keeps a free seat so nothing about it
    // depends on a full course.
    private static final String OGDENVILLE_COURSE_ID = "econ-300";
    private static final int OGDENVILLE_COURSE_CAPACITY = 2;

    // How many enrollments each tenant ends up with, and so how many its read model should show. Both tenants
    // known at startup enroll the same number, so both cross the snapshot threshold, while the runtime-added
    // tenant enrolls one.
    private static final int SPRINGFIELD_ENROLLMENTS = STUDENTS_PER_KNOWN_TENANT;
    private static final int SHELBYVILLE_ENROLLMENTS = STUDENTS_PER_KNOWN_TENANT;
    private static final int OGDENVILLE_ENROLLMENTS = 1;

    // A tenant's context and command bus connector are created asynchronously, so its first command waits.
    private static final Duration TENANT_READY_TIMEOUT = Duration.ofSeconds(15);
    // Storing a snapshot does not hold up the command that triggered it, so the lookup waits for it.
    private static final Duration SNAPSHOT_LOOKUP_TIMEOUT = Duration.ofSeconds(15);
    // A projection trails the command that appended the event, so every read model observation waits for it.
    private static final Duration PROJECTION_TIMEOUT = Duration.ofSeconds(20);
    // A tenant added at runtime waits longer: the processor has to restart before its events are streamed at
    // all, and that restart is deliberately coalesced rather than immediate.
    private static final Duration RUNTIME_TENANT_PROJECTION_TIMEOUT = Duration.ofSeconds(60);
    // Closing every tenant's instances happens as shutdown unwinds, so the check waits for it.
    private static final Duration SHUTDOWN_CLEANUP_TIMEOUT = Duration.ofSeconds(5);

    private DemoLifecycle() {
        // Utility class, not meant to be instantiated.
    }

    /**
     * Walks the whole tenant lifecycle against an already-started application and returns what it
     * observed, so a smoke test can assert the outcome through the same entry point a user runs. The
     * per-tenant components it reads for the cleanup checks are {@link AutoCloseable}, but the framework
     * closes them on tenant removal and shutdown, so this only reads their state.
     *
     * @param application the started application to drive, and everything needed to drive it
     * @return the observed outcome of the demo run
     */
    public static DemoOutcome run(DemoApplication application) {
        Objects.requireNonNull(application, "The demo application must not be null");
        CommandGateway commandGateway = application.commandGateway();
        QueryGateway queryGateway = application.queryGateway();
        TenantComponentProvider<CourseStatisticsStore> statisticsProvider = application.statisticsProvider();
        TenantComponentProvider<AuditLog> auditProvider = application.auditProvider();
        TenantProvisioning provisioning = application.provisioning();
        TenantSnapshots<CourseSnapshot> snapshots = application.snapshots();
        List<String> processorNames = application.processorNames();
        Runnable shutdown = application.shutdown();

        provisioning.prepareKnownTenants();
        logger.info("Providers subscribed at startup. Known tenants: {}", tenantIds(statisticsProvider));

        // 1. Subscribe to both known tenants' statistics before either enrolls a student, so their own
        // subscription receives the updates the enrollments below trigger, not just their final value.
        EventStorageOutcome eventStorage;
        SubscriptionQueryOutcome subscriptionOutcome;
        // Closed however steps 2 and 3 end, so a failure in either does not leave a tenant's subscription query
        // open on its connection.
        try (StatisticsSubscription springfieldSubscription = subscribeToStatistics(queryGateway, SPRINGFIELD);
             StatisticsSubscription shelbyvilleSubscription = subscribeToStatistics(queryGateway, SHELBYVILLE)) {

            // 2. Enroll students in the tenants known at startup. Each enrollment appends to the tenant's own
            // event store, and against Axon Server the same course identifier in two tenants shows the event
            // stores are isolated.
            eventStorage = enrollStudents(commandGateway, provisioning.hasPerTenantEventStore());
            awaitProjected(queryGateway, SPRINGFIELD, SPRINGFIELD_ENROLLMENTS, PROJECTION_TIMEOUT);
            awaitProjected(queryGateway, SHELBYVILLE, SHELBYVILLE_ENROLLMENTS, PROJECTION_TIMEOUT);
            logTenantView("Springfield University", queryGateway, SPRINGFIELD);
            logTenantView("Shelbyville University", queryGateway, SHELBYVILLE);

            // 3. Neither subscription received the other's updates, and only Springfield's, whose course filled,
            // was completed, even though recording an enrollment names no tenant when it emits or completes.
            subscriptionOutcome = proveSubscriptionUpdateIsolation(springfieldSubscription, shelbyvilleSubscription);
        }

        // 4. Filling those courses snapshotted them. Each snapshot went to its own tenant's snapshot store,
        // so the same course identifier yields two snapshots holding two different sets of students.
        SnapshottingOutcome snapshottingOutcome = snapshots.hasPerTenantSnapshotStore()
                ? proveSnapshotIsolation(snapshots)
                : SnapshottingOutcome.notDemonstrated();

        // 5. Add a tenant at runtime. Its instances materialize on its first command, no config change, and
        // the running processor re-opens its stream so this tenant's events are projected too.
        provisioning.addTenant(OGDENVILLE);
        enrollWhenTenantReady(commandGateway);
        awaitProjected(queryGateway, OGDENVILLE, OGDENVILLE_ENROLLMENTS, RUNTIME_TENANT_PROJECTION_TIMEOUT);
        logTenantView("Ogdenville University (added at runtime)", queryGateway, OGDENVILLE);

        // 6. All three tenants were served by one processor, not one processor each. Read before Shelbyville
        // is removed below, while its read model is still there to compare.
        StreamingOutcome streaming = observeStreaming(processorNames, queryGateway);

        // 7. A command or query for a tenant the application does not know is rejected, and so is a query
        // that names no tenant at all.
        boolean unknownTenantRejected = observeUnresolvedTenantRejection(
                "command for an unknown tenant",
                () -> Enrollments.enroll(commandGateway, UNKNOWN, SHARED_COURSE_ID, "eve"));
        boolean unknownTenantQueryRejected = observeUnresolvedTenantRejection(
                "query for an unknown tenant",
                () -> Enrollments.statistics(queryGateway, UNKNOWN));
        // A tenant is what decides which components answer a query, so a query naming none cannot be served.
        boolean queryWithoutTenantRejected = observeUnresolvedTenantRejection(
                "query naming no tenant",
                () -> Enrollments.queryStatisticsWithoutTenant(queryGateway));

        // 8. Removing a tenant closes its per-tenant instances, and its statistics stop being queryable.
        boolean shelbyvilleClosedOnRemoval =
                removingTenantClosesItsInstances(provisioning, statisticsProvider, auditProvider);
        boolean removedTenantQueryRejected = removedTenantQueryIsRejected(queryGateway);
        QueryRejectionOutcome queryRejections = new QueryRejectionOutcome(unknownTenantQueryRejected,
                                                                          queryWithoutTenantRejected,
                                                                          removedTenantQueryRejected);

        // 9. Shutting down closes every remaining tenant's instances.
        return shutDownAndBuildOutcome(shutdown, queryGateway, statisticsProvider, auditProvider,
                                       unknownTenantRejected, queryRejections, shelbyvilleClosedOnRemoval,
                                       eventStorage, snapshottingOutcome, streaming, subscriptionOutcome);
    }

    /**
     * Enrolls students in the tenants known at startup and returns what the per-tenant event storage
     * showed. Each enrollment is one command that appends to the tenant's own event store, through the
     * event-sourced course it sources. Where each tenant has its own event store, the tenant's
     * {@link CourseStatisticsStore} and {@link AuditLog} follow from those appended events by way of the
     * {@link CourseStatisticsProjection}, and otherwise the command handler fills them.
     * <p>
     * Springfield fills its course to capacity, so a further enrollment there is rejected as full, decided from
     * that tenant's own events. Shelbyville opens its course with a seat to spare, so its own course still has
     * room afterwards, which is what lets the subscription-completion step tell the two tenants apart. Against
     * Axon Server both courses carry the same identifier, so the two together demonstrate event-store
     * isolation: Shelbyville's enrollments are accepted even though Springfield's identically-named course is
     * already full. In memory there is no per-tenant event store, so Shelbyville uses a distinct identifier and
     * this isolation is not shown.
     * <p>
     * Filling a course also crosses its snapshot threshold, so the framework snapshots it while the second
     * student enrolls, and the rejected third enrollment sources the course from that snapshot.
     *
     * @param commandGateway           the gateway enrollments are sent on
     * @param hasPerTenantEventStore   whether each tenant has its own event store (only against Axon Server)
     * @return what the event-storage isolation showed, or {@link EventStorageOutcome#notDemonstrated()} in memory
     */
    private static EventStorageOutcome enrollStudents(CommandGateway commandGateway, boolean hasPerTenantEventStore) {
        String shelbyvilleCourse = hasPerTenantEventStore ? SHARED_COURSE_ID : SHELBYVILLE_COURSE_ID;

        // Springfield's course is fresh, so both enrollments are expected to land, and they fill it.
        if (!openCourseAndEnroll(commandGateway, SPRINGFIELD, SHARED_COURSE_ID, SPRINGFIELD_COURSE_CAPACITY,
                                 "alice", "bob")) {
            throw new IllegalStateException("Springfield's course did not accept both of its students");
        }
        // Shelbyville offers a seat more than it fills, so its course still has room afterwards.
        boolean shelbyvilleAccepted = openCourseAndEnroll(commandGateway, SHELBYVILLE, shelbyvilleCourse,
                                                          SHELBYVILLE_COURSE_CAPACITY, "carol", "dave");

        // Springfield's course is full now, so this is rejected from that tenant's own events. It also sources
        // the course once more, which is the load that reads the snapshot back.
        boolean springfieldRejectedWhenFull =
                !Enrollments.tryEnroll(commandGateway, SPRINGFIELD, SHARED_COURSE_ID, "frank");

        return hasPerTenantEventStore
                ? EventStorageOutcome.demonstratedWith(springfieldRejectedWhenFull, shelbyvilleAccepted)
                : EventStorageOutcome.notDemonstrated();
    }

    /**
     * Opens the given tenant's course with the given {@code capacity} and enrolls both students. The first
     * command waits until the tenant accepts commands, and enrolling the second student crosses the course's
     * snapshot threshold.
     * <p>
     * Whether that leaves the course full is up to the {@code capacity} the caller opens it with, which is
     * what lets one tenant end up with a full course and another with room to spare.
     *
     * @param commandGateway the gateway enrollments are sent on
     * @param tenant         the tenant whose course to open
     * @param courseId       the identifier of the course to open and enroll into
     * @param capacity       the number of seats the course offers
     * @param firstStudent   the student enrolled first
     * @param secondStudent  the student enrolled second, whose enrollment snapshots the course
     * @return whether both students were accepted
     */
    private static boolean openCourseAndEnroll(CommandGateway commandGateway,
                                               TenantDescriptor tenant,
                                               String courseId,
                                               int capacity,
                                               String firstStudent,
                                               String secondStudent) {
        whenTenantReady(tenant, () -> Enrollments.openCourse(commandGateway, tenant, courseId, capacity));
        // Both attempts are sent, so a rejected first student does not skip the second.
        boolean firstAccepted = Enrollments.tryEnroll(commandGateway, tenant, courseId, firstStudent);
        boolean secondAccepted = Enrollments.tryEnroll(commandGateway, tenant, courseId, secondStudent);
        return firstAccepted && secondAccepted;
    }

    /**
     * Proves per-tenant snapshot isolation, only against Axon Server where each tenant has its own snapshot
     * store. Both tenants filled a course under the same identifier, so each store holds its own snapshot of
     * that identifier, and each snapshot must hold only that tenant's own student.
     * <p>
     * A snapshot captures the state its triggering load sourced, not the state that command leaves behind,
     * so each holds the one student enrolled before it.
     * <p>
     * Storing a snapshot does not hold up the command that triggered it, so this waits for both to appear.
     *
     * @param snapshots reads a single tenant's own snapshot store
     * @return the observed snapshot isolation outcome
     */
    private static SnapshottingOutcome proveSnapshotIsolation(TenantSnapshots<CourseSnapshot> snapshots) {
        AtomicReference<CourseSnapshot> springfieldSnapshot = new AtomicReference<>();
        AtomicReference<CourseSnapshot> shelbyvilleSnapshot = new AtomicReference<>();
        boolean bothTenantsHoldOwnSnapshot = holdsWithin(
                "both tenants' snapshot stores hold course [" + SHARED_COURSE_ID + "]",
                SNAPSHOT_LOOKUP_TIMEOUT,
                () -> {
                    springfieldSnapshot.set(snapshots.snapshotContentsOf(SPRINGFIELD, SHARED_COURSE_ID));
                    shelbyvilleSnapshot.set(snapshots.snapshotContentsOf(SHELBYVILLE, SHARED_COURSE_ID));
                    return springfieldSnapshot.get() != null && shelbyvilleSnapshot.get() != null;
                });

        boolean snapshotsHoldTheirOwnStudents = bothTenantsHoldOwnSnapshot
                && springfieldSnapshot.get().enrolledStudents().equals(Set.of("alice"))
                && shelbyvilleSnapshot.get().enrolledStudents().equals(Set.of("carol"));

        logger.info("""
                    Course [{}] is snapshotted in both tenants' own snapshot stores: {}. \
                    Each snapshot holds only its own tenant's student, so neither read the other's: {}. \
                    Springfield's snapshot holds {}, Shelbyville's holds {}""",
                    SHARED_COURSE_ID,
                    bothTenantsHoldOwnSnapshot,
                    snapshotsHoldTheirOwnStudents,
                    bothTenantsHoldOwnSnapshot ? springfieldSnapshot.get().enrolledStudents() : Set.of(),
                    bothTenantsHoldOwnSnapshot ? shelbyvilleSnapshot.get().enrolledStudents() : Set.of());
        return SnapshottingOutcome.demonstratedWith(bothTenantsHoldOwnSnapshot, snapshotsHoldTheirOwnStudents);
    }

    /**
     * Waits until the given {@code tenant}'s read model holds at least {@code expected} enrollments.
     * <p>
     * Against Axon Server the read model is written by the {@link CourseStatisticsProjection}, which trails
     * the command that appended the event, so this is where the demo lets it catch up. In memory the command
     * handler has already written it, so the wait returns immediately.
     *
     * @param queryGateway the gateway the tenant's statistics are read on
     * @param tenant       the tenant whose read model to wait for
     * @param expected     the number of enrollments to wait for
     * @param atMost       how long to wait before giving up
     */
    private static void awaitProjected(QueryGateway queryGateway,
                                       TenantDescriptor tenant,
                                       int expected,
                                       Duration atMost) {
        boolean caughtUp = holdsWithin(
                "tenant [" + tenant.tenantId() + "] projected " + expected + " enrollments",
                atMost,
                () -> Enrollments.statistics(queryGateway, tenant).totalEnrollments() >= expected);
        if (!caughtUp) {
            // Said loudly, so a run that ends with an unexplained count says why rather than leaving the
            // reader to infer it from a missing number.
            logger.warn("Tenant [{}] did not project {} enrollments within {}. Its read model is behind.",
                        tenant.tenantId(), expected, atMost);
        }
    }

    /**
     * Subscribes to the given {@code tenant}'s statistics and collects every update the subscription
     * receives, including its initial result, so {@link #proveSubscriptionUpdateIsolation} can inspect what
     * arrived without anything leaking from the other tenant's own subscription.
     * <p>
     * Returns only once that initial result has arrived. In memory, registering a subscription query is
     * synchronous, so this returns at once. Against Axon Server it is a round trip over the tenant's own
     * connection, and the registration for future updates completes before the initial result does, the same
     * ordering the local query bus uses. An enrollment sent before this returns could otherwise race that
     * registration and never reach this subscription, which would misreport as a leak rather than as what it
     * is: an update missed because nothing was listening for it yet.
     *
     * @param queryGateway the gateway the subscription query is sent on
     * @param tenant       the tenant to subscribe to
     * @return the tenant's open subscription and what it has received so far
     */
    private static StatisticsSubscription subscribeToStatistics(QueryGateway queryGateway,
                                                                TenantDescriptor tenant) {
        List<TenantStatistics> received = new CopyOnWriteArrayList<>();
        AtomicBoolean completed = new AtomicBoolean();
        // Completion is recorded rather than ignored: whether this tenant's subscription was completed, while
        // the other tenant's was not, is what shows completion is scoped to one tenant.
        Disposable subscription = Flux.from(Enrollments.subscribeToStatistics(queryGateway, tenant))
                                      .doOnComplete(() -> completed.set(true))
                                      .subscribe(received::add,
                                                 failure -> logger.warn("Tenant [{}]'s statistics subscription failed.",
                                                                        tenant.tenantId(), failure));
        boolean active = holdsWithin("tenant [" + tenant.tenantId() + "] subscription active",
                                     TENANT_READY_TIMEOUT,
                                     () -> !received.isEmpty());
        if (!active) {
            // Said loudly, so a run whose updates never arrive says the subscription never started rather than
            // leaving the isolation check below to report it as a leak.
            logger.warn("""
                        Tenant [{}]'s statistics subscription produced no initial result within {}. \
                        Anything observed on it below is unreliable.""",
                        tenant.tenantId(), TENANT_READY_TIMEOUT);
        }
        return new StatisticsSubscription(received, subscription, completed);
    }

    /**
     * Proves that recording an enrollment never reaches the other tenant's subscription, even though neither
     * {@link ReadModelWrites} nor {@link Enrollments#subscribeToStatistics} names a tenant when emitting or
     * subscribing. Springfield and Shelbyville each opened their own subscription before either enrolled a
     * student, so each should have received exactly its own initial result plus one update per accepted
     * enrollment, and no more: a leak would add the other tenant's updates on top of that.
     * <p>
     * Filling Springfield's course also completed its subscriptions, while Shelbyville's course kept a free
     * seat and its subscription stayed open. Completing names no tenant either, so that difference is what
     * shows the framework scopes completion to one tenant as well as emission.
     *
     * @param springfield the open subscription of Springfield, whose course fills
     * @param shelbyville the open subscription of Shelbyville, whose course keeps a free seat
     * @return the observed subscription-query isolation outcome
     */
    private static SubscriptionQueryOutcome proveSubscriptionUpdateIsolation(StatisticsSubscription springfield,
                                                                             StatisticsSubscription shelbyville) {
        List<Integer> expectedSpringfieldTotals = expectedRunningTotals(SPRINGFIELD_ENROLLMENTS);
        List<Integer> expectedShelbyvilleTotals = expectedRunningTotals(SHELBYVILLE_ENROLLMENTS);
        boolean springfieldArrived =
                holdsWithin("Springfield's subscription received " + expectedSpringfieldTotals.size() + " update(s)",
                            PROJECTION_TIMEOUT,
                            () -> springfield.receivedCount() >= expectedSpringfieldTotals.size());
        boolean shelbyvilleArrived =
                holdsWithin("Shelbyville's subscription received " + expectedShelbyvilleTotals.size() + " update(s)",
                            PROJECTION_TIMEOUT,
                            () -> shelbyville.receivedCount() >= expectedShelbyvilleTotals.size());
        // Springfield's course filled, so its subscription is completed. Awaited, since the completion trails the
        // update that filled it, and awaited last, so a leak into Shelbyville must have been emitted before it.
        boolean springfieldCompleted =
                holdsWithin("Springfield's subscription completed", PROJECTION_TIMEOUT, springfield::isCompleted);
        if (!springfieldArrived || !shelbyvilleArrived || !springfieldCompleted) {
            // Said loudly, so a run reporting no isolation says which observation never arrived rather than
            // reading as a leak between tenants.
            logger.warn("""
                        Not every subscription observation arrived within {}. Springfield's updates: {}, \
                        Shelbyville's updates: {}, Springfield completed: {}. \
                        What follows understates what the framework did.""",
                        PROJECTION_TIMEOUT, springfieldArrived, shelbyvilleArrived, springfieldCompleted);
        }

        List<Integer> springfieldTotals = springfield.receivedTotals();
        List<Integer> shelbyvilleTotals = shelbyville.receivedTotals();
        // Comparing the whole sequence rather than only its length also catches a leak that replaces an update
        // instead of adding one, which a count on its own would read as correct.
        boolean isolatedByTenant = springfieldTotals.equals(expectedSpringfieldTotals)
                && shelbyvilleTotals.equals(expectedShelbyvilleTotals);
        boolean completionScopedToTenant = springfieldCompleted && !shelbyville.isCompleted();
        logger.info("""
                    Springfield's subscription saw enrollment totals {}, Shelbyville's saw {}. \
                    Neither received the other's: {}. \
                    Springfield's course filled so its subscription completed, while Shelbyville's kept a free \
                    seat and stayed open: {}""",
                    springfieldTotals, shelbyvilleTotals, isolatedByTenant, completionScopedToTenant);
        return new SubscriptionQueryOutcome(springfieldTotals.size(),
                                            shelbyvilleTotals.size(),
                                            isolatedByTenant,
                                            completionScopedToTenant);
    }

    /**
     * The running enrollment totals a subscription should see for a tenant that ends up with the given
     * {@code enrollments}: its initial result of nothing yet, then one entry per enrollment as it lands.
     *
     * @param enrollments the number of enrollments the tenant ends up with
     * @return the totals expected, in the order they should arrive
     */
    private static List<Integer> expectedRunningTotals(int enrollments) {
        return IntStream.rangeClosed(0, enrollments).boxed().toList();
    }

    /**
     * One tenant's open statistics subscription: every {@link TenantStatistics} update it has received so far,
     * whether it was completed, and the means to close it.
     */
    private static final class StatisticsSubscription implements AutoCloseable {

        private final List<TenantStatistics> received;
        private final Disposable subscription;
        private final AtomicBoolean completed;

        private StatisticsSubscription(List<TenantStatistics> received,
                                       Disposable subscription,
                                       AtomicBoolean completed) {
            this.received = received;
            this.subscription = subscription;
            this.completed = completed;
        }

        /**
         * Stops receiving updates on this subscription.
         */
        @Override
        public void close() {
            subscription.dispose();
        }

        /**
         * Returns whether the publisher completed this subscription.
         *
         * @return {@code true} if this subscription was completed
         */
        boolean isCompleted() {
            return completed.get();
        }

        /**
         * The number of updates received so far, including the initial result.
         *
         * @return how many updates arrived
         */
        int receivedCount() {
            return received.size();
        }

        /**
         * The running enrollment total this subscription saw, one entry per update received. Starting from the
         * initial result, each entry should be the tenant's own total at that moment, so the whole list reads
         * as that tenant's enrollments arriving one at a time.
         *
         * @return the running enrollment totals received, in the order they arrived
         */
        List<Integer> receivedTotals() {
            return received.stream().map(TenantStatistics::totalEnrollments).toList();
        }
    }

    /**
     * Reports what served the three tenants' projections, so the demo can show it was one processor rather than
     * one per tenant. Springfield and Shelbyville hold the same course identifier, so a leak between them would
     * show up as a wrong count. Counts are read fresh here rather than reused from the waits above.
     *
     * @param processorNames the names of every streaming event processor the application registered
     * @param queryGateway the gateway the tenants' statistics are read on
     * @return the observed event-processing outcome
     */
    private static StreamingOutcome observeStreaming(List<String> processorNames, QueryGateway queryGateway) {
        if (!processorNames.contains(StatisticsConfiguration.PROCESSOR_NAME)) {
            return StreamingOutcome.notDemonstrated();
        }
        int springfieldProjected = Enrollments.statistics(queryGateway, SPRINGFIELD).totalEnrollments();
        int shelbyvilleProjected = Enrollments.statistics(queryGateway, SHELBYVILLE).totalEnrollments();
        int ogdenvilleProjected = Enrollments.statistics(queryGateway, OGDENVILLE).totalEnrollments();
        logger.info("""
                    Three tenants were served by {} streaming event processor(s) {}. \
                    Projected enrollments per tenant: springfield={}, shelbyville={}, ogdenville={}""",
                    processorNames.size(), processorNames,
                    springfieldProjected, shelbyvilleProjected, ogdenvilleProjected);
        return StreamingOutcome.demonstratedWith(processorNames,
                                                 springfieldProjected,
                                                 shelbyvilleProjected,
                                                 ogdenvilleProjected);
    }

    /**
     * Enrolls in a tenant added at runtime, once that tenant accepts commands.
     */
    private static void enrollWhenTenantReady(CommandGateway commandGateway) {
        whenTenantReady(OGDENVILLE, () -> {
            Enrollments.openCourse(commandGateway, OGDENVILLE, OGDENVILLE_COURSE_ID, OGDENVILLE_COURSE_CAPACITY);
            Enrollments.enroll(commandGateway, OGDENVILLE, OGDENVILLE_COURSE_ID, "dan");
        });
    }

    /**
     * Sends the given tenant's {@code firstCommands}, retrying until that tenant accepts them.
     * <p>
     * Creating a tenant's Axon Server context and command bus connector is asynchronous, and finishes after
     * the tenant provider has discovered the tenant. A command sent in that window is rejected, as an
     * unresolved tenant or as an unknown context, so the first command to any tenant has to tolerate it.
     * That holds for the tenants known at startup as much as for one added at runtime: provisioning waits
     * until the provider lists the tenant, which does not mean the server already routes to its context.
     * <p>
     * A rejected attempt fails at dispatch without appending anything, and opening a course and enrolling a
     * student are both idempotent, so retrying lands the work exactly once.
     *
     * @param tenant        the tenant whose readiness to wait for
     * @param firstCommands the commands to send, retried until the tenant accepts them
     */
    private static void whenTenantReady(TenantDescriptor tenant, Runnable firstCommands) {
        Awaitility.await("tenant [" + tenant.tenantId() + "] ready for commands")
                  .atMost(TENANT_READY_TIMEOUT)
                  .ignoreExceptionsMatching(Enrollments::causedByTenantNotReady)
                  .until(() -> {
                      firstCommands.run();
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
     * Sends the given {@code dispatch} and observes whether the framework refused it because its tenant could
     * not be resolved, so that no instance is ever built for it.
     * <p>
     * The command and query sides resolve tenants against the same known tenants, so a refusal looks identical
     * whichever gateway raised it, which is why all three unknown-tenant checks share this.
     *
     * @param description names what was dispatched, so the log says which refusal was observed
     * @param dispatch    the dispatch expected to be refused
     * @return whether the dispatch was refused for an unresolved tenant
     */
    private static boolean observeUnresolvedTenantRejection(String description, Runnable dispatch) {
        boolean rejected = isRejectedForUnresolvedTenant(description, dispatch);
        logger.info("The {} was rejected: {}", description, rejected);
        return rejected;
    }

    /**
     * Sends the given {@code dispatch} and returns whether the framework refused it because its tenant could not
     * be resolved, without reporting the outcome. Suitable for a caller that polls, where reporting every
     * attempt would read as repeated failure.
     * <p>
     * A failure for any other reason is reported, since it says something other than "the tenant was refused"
     * and would otherwise be indistinguishable from it.
     *
     * @param description names what was dispatched, so a failure says which dispatch it belonged to
     * @param dispatch    the dispatch expected to be refused
     * @return whether the dispatch was refused for an unresolved tenant
     */
    private static boolean isRejectedForUnresolvedTenant(String description, Runnable dispatch) {
        try {
            dispatch.run();
            return false;
        } catch (RuntimeException failure) {
            if (Enrollments.causedByTenantNotResolved(failure)) {
                return true;
            }
            // Said loudly, so a failure for some other reason does not read as the framework declining to reject
            // an unresolved tenant.
            logger.warn("The {} failed, but not because its tenant could not be resolved.", description, failure);
            return false;
        }
    }

    /**
     * Confirms that Shelbyville's statistics stop being queryable once its tenant has been removed, which is
     * the read-side counterpart of the unknown-tenant rejection: a tenant that is no longer served is as
     * unservable as one that never was.
     * <p>
     * This waits rather than asserting once. Removal reaches the tenant provider before the routing to that
     * tenant is torn down, so a query sent in that window still succeeds. That is the mirror image of the
     * window {@link #whenTenantReady} tolerates while a tenant spins up.
     *
     * @param queryGateway the gateway the statistics query is sent on
     * @return whether the removed tenant's statistics stopped being queryable
     */
    private static boolean removedTenantQueryIsRejected(QueryGateway queryGateway) {
        boolean rejected = holdsWithin(
                "removed tenant [" + SHELBYVILLE.tenantId() + "] is no longer queryable",
                TENANT_READY_TIMEOUT,
                () -> isRejectedForUnresolvedTenant(
                        "query for the removed tenant [" + SHELBYVILLE.tenantId() + "]",
                        () -> Enrollments.statistics(queryGateway, SHELBYVILLE)));
        logger.info("The query for the removed tenant [{}] was rejected: {}", SHELBYVILLE.tenantId(), rejected);
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
                                                       QueryRejectionOutcome queryRejections,
                                                       boolean shelbyvilleClosedOnRemoval,
                                                       EventStorageOutcome eventStorage,
                                                       SnapshottingOutcome snapshotting,
                                                       StreamingOutcome streaming,
                                                       SubscriptionQueryOutcome subscriptionQuery) {
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
        boolean allClosedOnShutdown = holdsWithin("shutdown cleanup", SHUTDOWN_CLEANUP_TIMEOUT, () ->
                stores.stream().allMatch(CourseStatisticsStore::isClosed)
                        && auditLogs.stream().allMatch(AuditLog::isClosed));
        logger.info("Shutdown complete. All remaining tenant instances closed: {}", allClosedOnShutdown);

        return new DemoOutcome(springfield.totalEnrollments(),
                               springfield.auditEntries(),
                               ogdenvilleEnrollments,
                               unknownTenantRejected,
                               queryRejections,
                               shelbyvilleClosedOnRemoval,
                               allClosedOnShutdown,
                               eventStorage,
                               snapshotting,
                               streaming,
                               subscriptionQuery);
    }

    /**
     * Waits until the given {@code condition} holds, returning whether it did within {@code atMost}. It
     * returns {@code false} rather than throwing, so the demo reports what it observed and carries on to
     * its remaining steps.
     *
     * @param description names the condition, so a failure says which observation did not hold
     * @param atMost      how long to wait before giving up
     * @param condition   the condition to wait for
     * @return whether the condition held within {@code atMost}
     */
    private static boolean holdsWithin(String description, Duration atMost, BooleanSupplier condition) {
        try {
            Awaitility.await(description)
                      .atMost(atMost)
                      // A failing lookup also counts as "did not hold".
                      .ignoreExceptions()
                      .until(condition::getAsBoolean);
            return true;
        } catch (ConditionTimeoutException timeout) {
            logger.info("Gave up waiting for {} after {}.", description, atMost);
            return false;
        }
    }

    private static List<String> tenantIds(TenantComponentProvider<CourseStatisticsStore> statisticsProvider) {
        return statisticsProvider.tenants().stream().map(TenantDescriptor::tenantId).toList();
    }
}
