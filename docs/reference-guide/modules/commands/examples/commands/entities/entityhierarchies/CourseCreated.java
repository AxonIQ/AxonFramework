package commands.entities.entityhierarchies;

import org.axonframework.eventsourcing.annotation.EventTag;

// tag::tagged-event[]
public record CourseCreated(@EventTag String courseId, String title, int capacity) {}
// end::tagged-event[]
