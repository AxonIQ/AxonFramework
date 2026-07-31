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

package org.axonframework.examples.demo.multitenancy.shared.messaging;

import io.axoniq.framework.messaging.multitenancy.api.MetadataBasedTenantResolver;
import io.axoniq.framework.messaging.multitenancy.api.TenantDescriptor;
import org.axonframework.examples.demo.multitenancy.university.write.enrollstudent.CourseFullException;
import org.axonframework.examples.demo.multitenancy.university.write.enrollstudent.EnrollStudent;
import org.axonframework.examples.demo.multitenancy.university.write.opencourse.OpenCourse;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;

import java.util.concurrent.TimeUnit;

/**
 * Drives the university's command side, hiding the demo's messaging behind a few verbs. Each enrollment is an
 * {@link EnrollStudent} command carrying its tenant in metadata under
 * {@link MetadataBasedTenantResolver#DEFAULT_TENANT_METADATA_KEY}. That metadata is how the framework routes the
 * command to the right tenant's components, so neither the payloads nor this class ever name a tenant field.
 * <p>
 * The read side is {@link StatisticsQueries}.
 */
public final class Enrollments {

    private static final long TIMEOUT_SECONDS = 5;

    private Enrollments() {
        // Utility class, not meant to be instantiated.
    }

    /**
     * Opens a course with the given {@code capacity} for the given {@code tenant}, and blocks until it has
     * been handled. The resulting event lands in that tenant's own event store.
     *
     * @param commandGateway the gateway to send the command on
     * @param tenant         the tenant the course belongs to
     * @param courseId       the course to open
     * @param capacity       the number of seats the course offers
     */
    public static void openCourse(CommandGateway commandGateway,
                                  TenantDescriptor tenant,
                                  String courseId,
                                  int capacity) {
        commandGateway.send(new OpenCourse(courseId, capacity), TenantMetadataFactory.forTenant(tenant))
                      .getResultMessage()
                      .orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                      .join();
    }

    /**
     * Enrolls a student by sending an {@link EnrollStudent} command for the given {@code tenant}, and
     * blocks until it has been handled. The handler appends to that tenant's own event store, so the
     * enrollment lands in the right tenant's event stream, and the tenant's read model follows from that
     * event.
     *
     * @param commandGateway the gateway to send the command on
     * @param tenant         the tenant the enrollment belongs to
     * @param courseId       the course enrolled in
     * @param studentId      the student enrolling
     */
    public static void enroll(CommandGateway commandGateway,
                              TenantDescriptor tenant,
                              String courseId,
                              String studentId) {
        commandGateway.send(new EnrollStudent(courseId, studentId), TenantMetadataFactory.forTenant(tenant))
                      .getResultMessage()
                      .orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                      .join();
    }

    /**
     * Enrolls a student, returning {@code true} when accepted and {@code false} when rejected because the
     * course was full. Any other failure propagates. The full-seat decision reads the course sourced from
     * the tenant's own event store, so a course full in one tenant says nothing about the same course
     * identifier in another.
     *
     * @param commandGateway the gateway to send the command on
     * @param tenant         the tenant the enrollment belongs to
     * @param courseId       the course enrolled in
     * @param studentId      the student enrolling
     * @return {@code true} if the enrollment was accepted, {@code false} if the course was full
     */
    public static boolean tryEnroll(CommandGateway commandGateway,
                                    TenantDescriptor tenant,
                                    String courseId,
                                    String studentId) {
        try {
            enroll(commandGateway, tenant, courseId, studentId);
            return true;
        } catch (RuntimeException failure) {
            if (RemoteExceptions.causedBy(failure, CourseFullException.class)) {
                return false;
            }
            throw failure;
        }
    }

}
