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

import io.axoniq.framework.axonserver.connector.configuration.AxonServerConfigurationEnhancer;
import io.axoniq.framework.messaging.multitenancy.api.MetadataBasedTenantResolver;
import io.axoniq.framework.messaging.multitenancy.api.TenantComponentProvider;
import io.axoniq.framework.messaging.multitenancy.api.TenantDescriptor;
import io.axoniq.framework.messaging.multitenancy.api.TenantProvider;
import io.axoniq.framework.messaging.multitenancy.axonserver.configuration.AxonServerMultiTenancyConfigurationDefaults;
import io.axoniq.framework.messaging.multitenancy.configuration.MultiTenancyConfigurationUtils.MultiTenancyEnabled;
import org.awaitility.Awaitility;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.eventsourcing.snapshot.api.Snapshot;
import org.axonframework.eventsourcing.snapshot.inmemory.InMemorySnapshotStore;
import org.axonframework.eventsourcing.snapshot.store.SnapshotStore;
import org.axonframework.examples.demo.multitenancy.shared.audit.AuditLog;
import org.axonframework.examples.demo.multitenancy.shared.messaging.Enrollments;
import org.axonframework.examples.demo.multitenancy.shared.tenant.DemoTenantProvider;
import org.axonframework.examples.demo.multitenancy.shared.tenant.TenantComponents;
import org.axonframework.examples.demo.multitenancy.university.read.statistics.CourseStatistics;
import org.axonframework.examples.demo.multitenancy.university.read.statistics.CourseStatisticsStore;
import org.axonframework.examples.demo.multitenancy.university.read.statistics.TenantStatistics;
import org.axonframework.examples.demo.multitenancy.university.UniversityModuleConfiguration;
import org.axonframework.examples.demo.multitenancy.university.write.enrollstudent.CourseFullException;
import org.axonframework.examples.demo.multitenancy.university.write.enrollstudent.CourseNotOpenException;
import org.axonframework.examples.demo.multitenancy.university.write.enrollstudent.EnrollStudent;
import org.axonframework.examples.demo.multitenancy.university.write.enrollstudent.EnrollStudentConfiguration;
import org.axonframework.examples.demo.multitenancy.university.write.opencourse.OpenCourse;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the course write side through the production path: the enrollment command handler both appends to
 * the tenant's event store (its injected {@code Course} is sourced from there, enforcing the open and
 * capacity decisions) and updates the tenant's injected {@code @TenantScoped} read-model components. Runs
 * in memory, where the event store is a single shared store rather than one per tenant, which is enough to
 * exercise the handler's decisions and the two parameter kinds resolving together on one handler.
 * Per-tenant event-store isolation is exercised against Axon Server by the Spring Boot integration test.
 */
class CourseEnrollmentCompositionTest {

    private static final TenantDescriptor TENANT = TenantDescriptor.tenantWithId("springfield");
    private static final String COURSE_ID = "cs-101";
    private static final int CAPACITY = 2;
    private static final long TIMEOUT_SECONDS = 5;

    private final SnapshotStore snapshotStore = new InMemorySnapshotStore();

    private AxonConfiguration configuration;
    private CommandGateway commandGateway;
    private QueryGateway queryGateway;
    private TenantComponentProvider<CourseStatisticsStore> statisticsProvider;
    private TenantComponentProvider<AuditLog> auditProvider;

    @BeforeEach
    void setUp() {
        statisticsProvider = TenantComponents.courseStatisticsProvider();
        auditProvider = TenantComponents.auditLogProvider();
        TenantProvider tenantProvider = new DemoTenantProvider(TENANT);

        EventSourcingConfigurer configurer = EventSourcingConfigurer.create();
        // A backing without per-tenant event stores keeps the read model written by the command handler, so
        // this test observes an enrollment's
        // full effect without a projection to wait for. It is also the only shape a single shared event store
        // supports, since an event streamed from it cannot be attributed to a tenant.
        UniversityModuleConfiguration.configure(configurer, DemoBacking.IN_MEMORY);
        configurer.componentRegistry(registry -> {
            MultiTenancyEnabled.enableMultiTenancyEnhancer(registry);
            registry.disableEnhancer(AxonServerConfigurationEnhancer.class)
                    .disableEnhancer(AxonServerMultiTenancyConfigurationDefaults.class)
                    .registerComponent(TenantProvider.class, config -> tenantProvider)
                    .registerComponent(TenantComponentProvider.class, "courseStatistics", config -> statisticsProvider)
                    .registerComponent(TenantComponentProvider.class, "auditLog", config -> auditProvider)
                    // The course is snapshotted, so it needs a snapshot store. In memory that is one
                    // shared store. Against Axon Server the multi-tenancy defaults give each tenant its own.
                    .registerComponent(SnapshotStore.class, config -> snapshotStore);
        });
        configuration = configurer.build();
        configuration.start();
        commandGateway = configuration.getComponent(CommandGateway.class);
        queryGateway = configuration.getComponent(QueryGateway.class);
    }

    @AfterEach
    void tearDown() {
        configuration.shutdown();
    }

    @Test
    void enrollmentAppendsEventAndUpdatesTenantScopedComponents() {
        openCourse(COURSE_ID, CAPACITY);

        enroll(COURSE_ID, "alice");
        enroll(COURSE_ID, "bob");

        // The event-sourced course accepted both (its @InjectEntity state was sourced from the appended
        // events), and the @TenantScoped components were injected and updated.
        assertThat(statisticsProvider.componentFor(TENANT).statistics())
                .containsExactly(new CourseStatistics(COURSE_ID, 2));
        assertThat(auditProvider.componentFor(TENANT).entries()).hasSize(2);
    }

    @Test
    void fillingTheCourseSnapshotsItAndTheSnapshotSourcesBackTheFullCourse() {
        openCourse(COURSE_ID, CAPACITY);
        enroll(COURSE_ID, "alice");
        // Crosses the course's snapshot threshold, so this load snapshots the course.
        enroll(COURSE_ID, "bob");

        // The snapshot was really written, under the name the framework stores it with. Whether the entity
        // survives conversion is a separate question, asserted by CourseSnapshotConversionTest: the
        // in-memory store keeps the entity instance as-is, so this path never converts anything.
        assertThat(storedCourseSnapshot()).isNotNull();

        // And sourcing from that snapshot restores the full course rather than a blank one: a blank course
        // would be rejected as not open instead of as full.
        assertThatThrownBy(() -> enroll(COURSE_ID, "carol"))
                .hasRootCauseInstanceOf(CourseFullException.class);
    }

    @Test
    void openingAnAlreadyOpenCourseKeepsItsOriginalCapacity() {
        openCourse(COURSE_ID, CAPACITY);
        // A second open with a larger capacity is idempotent: the course stays at its original capacity.
        openCourse(COURSE_ID, CAPACITY + 3);
        enroll(COURSE_ID, "alice");
        enroll(COURSE_ID, "bob");

        assertThatThrownBy(() -> enroll(COURSE_ID, "carol"))
                .hasRootCauseInstanceOf(CourseFullException.class);
    }

    @Nested
    class Rejections {

        @Test
        void enrollmentBeyondCapacityIsRejectedFromTheSourcedCourse() {
            openCourse(COURSE_ID, CAPACITY);
            enroll(COURSE_ID, "alice");
            enroll(COURSE_ID, "bob");

            assertThatThrownBy(() -> enroll(COURSE_ID, "carol"))
                    .hasRootCauseInstanceOf(CourseFullException.class);

            // The guard runs before the append and the component update, so the rejection left no partial
            // write: statistics still holds only the two accepted enrollments.
            assertThat(statisticsProvider.componentFor(TENANT).statistics())
                    .containsExactly(new CourseStatistics(COURSE_ID, 2));
        }

        @Test
        void enrollmentIntoAnUnopenedCourseIsRejected() {
            assertThatThrownBy(() -> enroll(COURSE_ID, "alice"))
                    .hasRootCauseInstanceOf(CourseNotOpenException.class);
        }
    }

    @Test
    void reEnrollingTheSameStudentDoesNotRecordTwice() {
        openCourse(COURSE_ID, CAPACITY);
        enroll(COURSE_ID, "alice");

        enroll(COURSE_ID, "alice");

        // The second enrollment is idempotent: the sourced course already holds alice, so no further event
        // is appended and the component is not updated again.
        assertThat(statisticsProvider.componentFor(TENANT).statistics())
                .containsExactly(new CourseStatistics(COURSE_ID, 1));
    }

    @Test
    void enrollingAStudentEmitsAnUpdateToTheTenantsOwnSubscription() {
        openCourse(COURSE_ID, CAPACITY);
        List<TenantStatistics> received = subscribeToStatistics();

        enroll(COURSE_ID, "alice");

        // The subscription's initial result is the tenant's statistics at subscribe time, empty here since
        // the course was only just opened, followed by the update the enrollment's handler emits.
        Awaitility.await("the enrollment's update is received")
                  .atMost(Duration.ofSeconds(TIMEOUT_SECONDS))
                  .untilAsserted(() -> assertThat(received).containsExactly(
                          new TenantStatistics(List.of(), 0),
                          new TenantStatistics(List.of(new CourseStatistics(COURSE_ID, 1)), 1)));
    }

    // Reads the snapshot straight from the store the configuration was given, under the same name the
    // entity's repository stores it with.
    @Nullable
    private Snapshot storedCourseSnapshot() {
        QualifiedName courseSnapshotName =
                EnrollStudentConfiguration.courseSnapshotName(configuration.getComponent(MessageTypeResolver.class));
        return snapshotStore.load(courseSnapshotName, COURSE_ID, null)
                            .orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .join();
    }

    private void openCourse(String courseId, int capacity) {
        send(new OpenCourse(courseId, capacity));
    }

    private void enroll(String courseId, String studentId) {
        send(new EnrollStudent(courseId, studentId));
    }

    private void send(Object command) {
        Metadata tenantMetadata = Metadata.with(MetadataBasedTenantResolver.DEFAULT_TENANT_METADATA_KEY,
                                                TENANT.tenantId());
        commandGateway.send(command, tenantMetadata)
                      .getResultMessage()
                      .orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                      .join();
    }

    // Subscribes to the tenant's statistics and collects every update received, including the initial
    // result, requesting an unbounded number of updates up front so the demo's own Subscriber shape stays
    // out of the assertion.
    private List<TenantStatistics> subscribeToStatistics() {
        List<TenantStatistics> received = new CopyOnWriteArrayList<>();
        Enrollments.subscribeToStatistics(queryGateway, TENANT).subscribe(new Subscriber<>() {
            @Override
            public void onSubscribe(Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(TenantStatistics statistics) {
                received.add(statistics);
            }

            @Override
            public void onError(Throwable throwable) {
                // Not expected in this test; a missing update speaks for itself in the assertion.
            }

            @Override
            public void onComplete() {
                // Not expected in this test: the subscription stays open until the test ends.
            }
        });
        return received;
    }
}
