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

import io.axoniq.framework.messaging.multitenancy.api.MetadataBasedTenantResolver;
import io.axoniq.framework.messaging.multitenancy.api.TenantDescriptor;
import io.axoniq.framework.messaging.multitenancy.api.TenantNotResolvedException;
import org.axonframework.examples.demo.multitenancy.university.read.statistics.GetTenantStatistics;
import org.axonframework.examples.demo.multitenancy.university.read.statistics.TenantStatistics;
import org.axonframework.examples.demo.multitenancy.university.write.enrol.EnrolStudent;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.queryhandling.GenericQueryMessage;
import org.axonframework.messaging.queryhandling.QueryMessage;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Drives the university through its command and query gateways, hiding the demo's messaging behind a
 * few verbs. Each enrolment is an {@link EnrolStudent} command and each read a {@link
 * GetTenantStatistics} query, both carrying their tenant in metadata under {@link
 * MetadataBasedTenantResolver#DEFAULT_TENANT_METADATA_KEY}. That metadata is how the framework routes
 * the message to the right tenant's components, so neither the payloads nor this class ever name a
 * tenant field.
 */
public final class Enrolments {

    private static final long TIMEOUT_SECONDS = 5;

    private Enrolments() {
        // Utility class, not meant to be instantiated.
    }

    /**
     * Enrols a student by sending an {@link EnrolStudent} command for the given {@code tenant}, and
     * blocks until it has been handled. The command handler receives that tenant's components, so the
     * enrolment lands in the right tenant's read model.
     *
     * @param commandGateway the gateway to send the command on
     * @param tenant         the tenant the enrolment belongs to
     * @param courseId       the course enrolled in
     * @param studentId      the student enrolling
     */
    public static void enrol(CommandGateway commandGateway,
                             TenantDescriptor tenant,
                             String courseId,
                             String studentId) {
        commandGateway.send(new EnrolStudent(courseId, studentId), tenantMetadata(tenant))
                      .getResultMessage()
                      .orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                      .join();
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
        QueryMessage query = new GenericQueryMessage(new MessageType(GetTenantStatistics.class),
                                                     new GetTenantStatistics())
                .andMetadata(Map.of(MetadataBasedTenantResolver.DEFAULT_TENANT_METADATA_KEY, tenant.tenantId()));
        return queryGateway.query(query, TenantStatistics.class)
                           .orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                           .join();
    }

    /**
     * Returns {@code true} if the given {@code throwable} was caused by a
     * {@link TenantNotResolvedException}, the failure the framework raises for an unknown tenant.
     * <p>
     * In memory the exception travels as itself, so the type is matched directly. Over Axon Server the
     * failure crosses the wire and is reconstructed as a generic execution exception that only carries
     * the original type and message as text, so the exception name is matched in the message as well.
     *
     * @param throwable the throwable to inspect
     * @return {@code true} if a {@link TenantNotResolvedException} is in its cause chain
     */
    public static boolean causedByTenantNotResolved(Throwable throwable) {
        String exceptionName = TenantNotResolvedException.class.getSimpleName();
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof TenantNotResolvedException
                    || cause.getClass().getSimpleName().equals(exceptionName)) {
                return true;
            }
            String message = cause.getMessage();
            if (message != null && message.contains(exceptionName)) {
                return true;
            }
        }
        return false;
    }

    private static Metadata tenantMetadata(TenantDescriptor tenant) {
        return Metadata.with(MetadataBasedTenantResolver.DEFAULT_TENANT_METADATA_KEY, tenant.tenantId());
    }
}
