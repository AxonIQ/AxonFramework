package commands.commandhandlers;

import org.axonframework.messaging.commandhandling.annotation.Command;

class DeclaringCommandType {

    // tag::declaring-command-type[]
    @Command(
        namespace = "faculty",            // <1>
        name = "IssueStudentTranscript",  // <2>
        version = "1.0.0",                // <3>
        routingKey = "studentId"          // <4>
    )
    public record IssueStudentTranscript(StudentId studentId, CourseId courseId) {
    }
    // end::declaring-command-type[]
}
