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

import commands.commandhandlers.PayloadConversionExample.IssueStudentTranscript;
import commands.commandhandlers.PayloadConversionExample.IssueStudentTranscriptDto;
import org.axonframework.messaging.commandhandling.annotation.Command;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;

class PayloadConversionExample {

    // tag::payload-conversion-types[]
    // Original command class
    @Command(namespace = "faculty", name = "IssueStudentTranscript", version = "1.0.0")
    public record IssueStudentTranscript(StudentId studentId, CourseId courseId) {
    }

    // Alternative representation for a different handler
    public record IssueStudentTranscriptDto(String studentId, String courseId) {
    }

    // end::payload-conversion-types[]
}

class PayloadConversionOriginalTypeHandler {

    // tag::payload-conversion-original-handler[]
    // Handler using the original type
    @CommandHandler
    public void handle(IssueStudentTranscript command) {
        // Receives IssueStudentTranscript with typed StudentId / CourseId
    }

    // end::payload-conversion-original-handler[]
}

class PayloadConversionDtoHandler {

    // tag::payload-conversion-dto-handler[]
    // Handler in a different component AND different JVM using the alternative representation
    @CommandHandler(commandName = "faculty.IssueStudentTranscript")
    public void handle(IssueStudentTranscriptDto dto) {
        // Same command, but converted to the DTO representation
        // Axon converts the payload automatically
    }
    // end::payload-conversion-dto-handler[]
}
