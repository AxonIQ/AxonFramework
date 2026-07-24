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

package org.axonframework.examples.demo.multitenancy.university.write.enroll;

import io.axoniq.framework.messaging.multitenancy.annotation.TenantScoped;
import org.axonframework.examples.demo.multitenancy.university.component.AuditLog;
import org.axonframework.examples.demo.multitenancy.university.component.CourseStatisticsStore;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;

/**
 * Handles the {@link EnrollStudent} command, recording the enrollment for the tenant the command
 * carries.
 * <p>
 * The handler declares a {@link CourseStatisticsStore} and an {@link AuditLog} parameter, each marked
 * {@link TenantScoped}, and is handed the instances of the command's tenant, each matched by its own
 * type, so it never resolves a tenant itself. This is the whole developer-facing surface of
 * tenant-aware components: register a component per tenant, declare it as a {@link TenantScoped}
 * handler parameter, and the framework injects the right tenant's instance based on the message's
 * metadata.
 */
public class EnrollStudentCommandHandler {

    /**
     * Records the enrollment on the command's tenant's course-statistics store and audit log.
     *
     * @param command               the enrollment command being handled
     * @param courseStatisticsStore the injected course-statistics store of the command's tenant
     * @param auditLog              the injected audit log of the command's tenant
     */
    @CommandHandler
    public void handle(EnrollStudent command,
                       @TenantScoped CourseStatisticsStore courseStatisticsStore,
                       @TenantScoped AuditLog auditLog) {
        courseStatisticsStore.recordEnrollment(command.courseId());
        auditLog.record("Enrolled student [" + command.studentId() + "] in course [" + command.courseId() + "]");
    }
}
