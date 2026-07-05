package commands.entities.entityhierarchies;

import org.axonframework.eventsourcing.annotation.EventTag;

// tag::tagged-event[]
public record StudentEnrolled(@EventTag String courseId, String studentId) {}
// end::tagged-event[]
