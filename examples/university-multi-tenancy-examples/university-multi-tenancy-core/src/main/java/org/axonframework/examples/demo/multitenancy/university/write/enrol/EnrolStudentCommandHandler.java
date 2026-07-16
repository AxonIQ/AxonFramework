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

package org.axonframework.examples.demo.multitenancy.university.write.enrol;

import org.axonframework.examples.demo.multitenancy.university.component.AuditLog;
import org.axonframework.examples.demo.multitenancy.university.component.CourseStatsStore;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;

/**
 * Handles the {@link EnrolStudent} command, recording the enrolment for the tenant the command
 * carries.
 * <p>
 * The handler declares a {@link CourseStatsStore} and an {@link AuditLog} parameter and is
 * handed the instances of the command's tenant, each matched by its own type, so it never resolves a
 * tenant itself. This is the whole developer-facing surface of tenant-aware components: register a
 * component per tenant, declare it as a handler parameter, and the framework injects the right
 * tenant's instance based on the message's metadata.
 */
public class EnrolStudentCommandHandler {

    /**
     * Records the enrolment on the command's tenant's course-statistics store and audit log.
     *
     * @param command    the enrolment command being handled
     * @param statistics the injected course-statistics store of the command's tenant
     * @param auditLog   the injected audit log of the command's tenant
     */
    @CommandHandler
    public void handle(EnrolStudent command, CourseStatsStore statistics, AuditLog auditLog) {
        statistics.recordEnrolment(command.courseId());
        auditLog.record("Enrolled student [" + command.studentId() + "] in course [" + command.courseId() + "]");
    }
}
