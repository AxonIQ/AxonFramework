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

package org.axonframework.examples.demo.multitenancy.scaffolding;

import io.axoniq.framework.messaging.multitenancy.api.MetadataBasedTenantResolver;
import io.axoniq.framework.messaging.multitenancy.api.TenantComponentProvider;
import io.axoniq.framework.messaging.multitenancy.api.TenantDescriptor;
import io.axoniq.framework.messaging.multitenancy.api.TenantNotResolvedException;
import org.awaitility.Awaitility;
import org.axonframework.examples.demo.multitenancy.university.events.StudentEnrolledInCourse;
import org.axonframework.examples.demo.multitenancy.university.read.coursestats.CourseStatistics;
import org.axonframework.examples.demo.multitenancy.university.read.coursestats.CourseStatsRepository;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventSink;
import org.axonframework.messaging.eventhandling.GenericEventMessage;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Publishes enrolments as events and reads them back, hiding the demo's event plumbing behind a few
 * verbs. Each enrolment is a {@link StudentEnrolledInCourse} event carrying its tenant in metadata
 * under {@link MetadataBasedTenantResolver#DEFAULT_TENANT_KEY}. That metadata is how the framework
 * routes the event to the right tenant's components.
 */
public final class Enrolments {

    private Enrolments() {
        // Utility class, not meant to be instantiated.
    }

    /**
     * Enrols a student by publishing an enrolment event for the given {@code tenant}, and blocks until
     * the projection has handled it.
     *
     * @param eventSink the sink to publish on
     * @param tenant    the tenant the enrolment belongs to
     * @param courseId  the course enrolled in
     * @param studentId the student enrolling
     */
    public static void enrol(EventSink eventSink, TenantDescriptor tenant, String courseId, String studentId) {
        // No active ProcessingContext: the event is published standalone, not from within a handler.
        eventSink.publish(null, enrolmentEvent(tenant, courseId, studentId))
                .orTimeout(5, TimeUnit.SECONDS)
                .join();
    }

    /**
     * Waits until the given {@code tenant}'s repository has recorded at least {@code expected}
     * enrolments, since events are handled asynchronously.
     *
     * @param provider the provider of the per-tenant course-statistics repositories
     * @param tenant   the tenant whose enrolments to wait for
     * @param expected the number of enrolments to wait for
     */
    // Reads the tenant's repository state. The framework, not this method, closes that repository.
    public static void awaitEnrolments(TenantComponentProvider<CourseStatsRepository> provider,
                                       TenantDescriptor tenant,
                                       int expected) {
        Awaitility.await("enrolments for " + tenant.tenantId())
                  .atMost(Duration.ofSeconds(5))
                  .until(() -> totalEnrolments(provider.componentFor(tenant)) >= expected);
    }

    /**
     * Returns the total number of enrolments recorded across all courses in the given {@code repository}.
     *
     * @param repository the repository to total the enrolments of
     * @return the total number of enrolments
     */
    public static int totalEnrolments(CourseStatsRepository repository) {
        return repository.statistics().stream().mapToInt(CourseStatistics::enrolments).sum();
    }

    /**
     * Returns {@code true} if the given {@code throwable} was caused by a
     * {@link TenantNotResolvedException}, the failure the framework raises for an unknown tenant.
     *
     * @param throwable the throwable to inspect
     * @return {@code true} if a {@link TenantNotResolvedException} is in its cause chain
     */
    public static boolean causedByTenantNotResolved(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof TenantNotResolvedException) {
                return true;
            }
        }
        return false;
    }

    private static EventMessage enrolmentEvent(TenantDescriptor tenant, String courseId, String studentId) {
        return new GenericEventMessage(new MessageType(StudentEnrolledInCourse.class),
                                       new StudentEnrolledInCourse(courseId, studentId))
                .andMetadata(Map.of(MetadataBasedTenantResolver.DEFAULT_TENANT_KEY, tenant.tenantId()));
    }
}
