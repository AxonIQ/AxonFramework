package commands.entities.entityhierarchies;

import org.axonframework.eventsourcing.annotation.EventTag;

// tag::tagged-event[]
public record EnrollmentDropped(@EventTag String courseId, String studentId, String reason) {}
// end::tagged-event[]
