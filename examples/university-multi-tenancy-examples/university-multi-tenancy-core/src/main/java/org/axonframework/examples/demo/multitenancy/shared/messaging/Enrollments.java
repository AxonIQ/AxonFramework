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
import io.axoniq.framework.messaging.multitenancy.api.TenantNotResolvedException;
import org.axonframework.examples.demo.multitenancy.university.read.statistics.GetTenantStatistics;
import org.axonframework.examples.demo.multitenancy.university.read.statistics.ReadModelWrites;
import org.axonframework.examples.demo.multitenancy.university.read.statistics.TenantStatistics;
import org.axonframework.examples.demo.multitenancy.university.write.enrollstudent.CourseFullException;
import org.axonframework.examples.demo.multitenancy.university.write.enrollstudent.EnrollStudent;
import org.axonframework.examples.demo.multitenancy.university.write.opencourse.OpenCourse;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.queryhandling.GenericQueryMessage;
import org.axonframework.messaging.queryhandling.QueryMessage;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.reactivestreams.Publisher;

import java.util.concurrent.TimeUnit;

/**
 * Drives the university through its command and query gateways, hiding the demo's messaging behind a
 * few verbs. Each enrollment is an {@link EnrollStudent} command and each read a {@link
 * GetTenantStatistics} query, both carrying their tenant in metadata under {@link
 * MetadataBasedTenantResolver#DEFAULT_TENANT_METADATA_KEY}. That metadata is how the framework routes
 * the message to the right tenant's components, so neither the payloads nor this class ever name a
 * tenant field.
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

    /**
     * Reads the given {@code tenant}'s statistics by sending a {@link GetTenantStatistics} query
     * carrying that tenant in metadata, and blocks for the response. The query handler is handed that
     * tenant's components, so the result holds only that tenant's data.
     *
     * @param queryGateway the gateway to send the query on
     * @param tenant       the tenant whose statistics to read
     * @return the tenant's isolated statistics
     */
    public static TenantStatistics statistics(QueryGateway queryGateway, TenantDescriptor tenant) {
        QueryMessage query = statisticsQuery().andMetadata(TenantMetadataFactory.forTenant(tenant));
        return queryGateway.query(query, TenantStatistics.class)
                           .orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                           .join();
    }

    /**
     * Reads statistics without naming a tenant at all, by sending a {@link GetTenantStatistics} query that
     * carries no tenant metadata, and blocks for the response.
     * <p>
     * There is no tenant to serve such a query for, so the framework rejects it at dispatch rather than
     * letting it reach a handler that would have nothing to resolve its components from. The tenant is the
     * only thing missing here, which is what separates this from a query naming a tenant the application
     * does not know.
     *
     * @param queryGateway the gateway to send the query on
     * @throws RuntimeException always, carrying the framework's refusal to serve a query without a tenant
     */
    public static void statisticsWithoutTenant(QueryGateway queryGateway) {
        queryGateway.query(statisticsQuery(), TenantStatistics.class)
                    .orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .join();
    }

    /**
     * Subscribes to the given {@code tenant}'s statistics by sending a {@link GetTenantStatistics}
     * subscription query carrying that tenant in metadata. The returned publisher emits the tenant's
     * current statistics first, and every fresh update {@link ReadModelWrites} emits after. It completes once
     * the tenant's course has no seats left, since there is no further enrollment to report.
     * <p>
     * The tenant travels only in the query's metadata, resolved once from this initial query. Emitting a
     * later update never names a tenant either, and still only this subscription's own tenant receives it:
     * the framework isolates emission by the tenant it resolves for the update, not by anything this
     * subscription's own query says.
     *
     * @param queryGateway the gateway to send the subscription query on
     * @param tenant       the tenant whose statistics to subscribe to
     * @return a publisher of the tenant's statistics, starting with its current value
     */
    public static Publisher<TenantStatistics> subscribeToStatistics(QueryGateway queryGateway,
                                                                    TenantDescriptor tenant) {
        QueryMessage query = statisticsQuery().andMetadata(TenantMetadataFactory.forTenant(tenant));
        return queryGateway.subscriptionQuery(query, TenantStatistics.class);
    }

    // The statistics query itself, carrying no tenant. Callers add the tenant metadata that routes it, except
    // the one that deliberately leaves it off.
    private static QueryMessage statisticsQuery() {
        return new GenericQueryMessage(new MessageType(GetTenantStatistics.class), new GetTenantStatistics());
    }

    /**
     * Returns {@code true} if the given {@code throwable} was caused by a
     * {@link TenantNotResolvedException}, the failure the framework raises for an unknown tenant, whether
     * it reaches the caller as itself or reconstructed over Axon Server.
     *
     * @param throwable the throwable to inspect
     * @return {@code true} if a {@link TenantNotResolvedException} is in its cause chain
     */
    public static boolean causedByTenantNotResolved(Throwable throwable) {
        return RemoteExceptions.causedBy(throwable, TenantNotResolvedException.class);
    }

    /**
     * Returns {@code true} if the given {@code throwable} indicates a tenant that is not ready for commands
     * yet, so a caller adding a tenant at runtime can retry until it is. That is either its tenant not being
     * resolved, or its Axon Server context still propagating so a command routed to it is briefly rejected
     * as an unknown context. Both are transient while a runtime-added tenant spins up.
     *
     * @param throwable the throwable to inspect
     * @return {@code true} if the failure is a transient not-ready-yet condition
     */
    public static boolean causedByTenantNotReady(Throwable throwable) {
        return causedByTenantNotResolved(throwable) || causedByUnknownContext(throwable);
    }

    // The tenant's Axon Server context is created before the command routing to it is in place, so a command
    // sent in that window comes back as an "Unknown Context" failure. Matched by message, as it crosses the
    // wire as a generic execution exception carrying only the original text.
    private static boolean causedByUnknownContext(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains("Unknown Context")) {
                return true;
            }
        }
        return false;
    }
}
