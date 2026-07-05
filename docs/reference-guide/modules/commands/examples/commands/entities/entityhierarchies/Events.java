package commands.entities.entityhierarchies;

import org.axonframework.eventsourcing.annotation.EventTag;

class Events {

    // tag::tagged-events[]
    public record CourseCreated(@EventTag String courseId, String title, int capacity) {}
    public record StudentEnrolled(@EventTag String courseId, String studentId) {}
    public record EnrollmentDropped(@EventTag String courseId, String studentId, String reason) {}
    // end::tagged-events[]
}
