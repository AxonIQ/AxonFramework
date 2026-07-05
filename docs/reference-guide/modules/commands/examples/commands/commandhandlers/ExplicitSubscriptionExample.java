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
