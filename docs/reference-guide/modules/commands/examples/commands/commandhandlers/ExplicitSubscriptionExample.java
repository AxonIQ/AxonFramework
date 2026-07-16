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
package commands.commandhandlers;

import org.axonframework.messaging.commandhandling.annotation.CommandHandler;

class ExplicitSubscriptionExample {

    // tag::explicit-subscription[]
    @CommandHandler(commandName = "faculty.IssueStudentTranscript") // <1>
    public void handleIssueTranscript(IssueStudentTranscriptDto dto) {
        // Handle command
    }

    @CommandHandler(
        commandName = "faculty.RenameCourse",
        payloadType = RenameCourseDto.class // <2>
    )
    public void handleRename(RenameCourseDto dto) {
        // Handle command
    }
    // end::explicit-subscription[]
}

record IssueStudentTranscriptDto(String studentId, String courseId) {
}

record RenameCourseDto(String courseId, String name) {
}
