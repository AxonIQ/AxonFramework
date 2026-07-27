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
import io.axoniq.framework.messaging.multitenancy.axonserver.AxonServerMultiTenancyConfigurationDefaults;
import io.axoniq.framework.messaging.multitenancy.configuration.MultiTenancyConfigurationUtils.MultiTenancyEnabled;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.examples.demo.multitenancy.shared.audit.AuditLog;
import org.axonframework.examples.demo.multitenancy.shared.tenant.DemoTenantProvider;
import org.axonframework.examples.demo.multitenancy.shared.tenant.TenantComponents;
import org.axonframework.examples.demo.multitenancy.university.read.statistics.CourseStatistics;
import org.axonframework.examples.demo.multitenancy.university.read.statistics.CourseStatisticsStore;
import org.axonframework.examples.demo.multitenancy.university.UniversityModuleConfiguration;
import org.axonframework.examples.demo.multitenancy.university.write.enrollstudent.CourseFullException;
import org.axonframework.examples.demo.multitenancy.university.write.enrollstudent.CourseNotOpenException;
import org.axonframework.examples.demo.multitenancy.university.write.enrollstudent.EnrollStudent;
import org.axonframework.examples.demo.multitenancy.university.write.opencourse.OpenCourse;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.core.Metadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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

    private AxonConfiguration configuration;
    private CommandGateway commandGateway;
    private TenantComponentProvider<CourseStatisticsStore> statisticsProvider;
    private TenantComponentProvider<AuditLog> auditProvider;

    @BeforeEach
    void setUp() {
        statisticsProvider = TenantComponents.courseStatisticsProvider();
        auditProvider = TenantComponents.auditLogProvider();
        TenantProvider tenantProvider = new DemoTenantProvider(TENANT);

        EventSourcingConfigurer configurer = EventSourcingConfigurer.create();
        UniversityModuleConfiguration.configure(configurer);
        configurer.componentRegistry(registry -> {
            MultiTenancyEnabled.enableMultiTenancyEnhancer(registry);
            registry.disableEnhancer(AxonServerConfigurationEnhancer.class)
                    .disableEnhancer(AxonServerMultiTenancyConfigurationDefaults.class)
                    .registerComponent(TenantProvider.class, config -> tenantProvider)
                    .registerComponent(TenantComponentProvider.class, "courseStatistics", config -> statisticsProvider)
                    .registerComponent(TenantComponentProvider.class, "auditLog", config -> auditProvider);
        });
        configuration = configurer.build();
        configuration.start();
        commandGateway = configuration.getComponent(CommandGateway.class);
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
}
