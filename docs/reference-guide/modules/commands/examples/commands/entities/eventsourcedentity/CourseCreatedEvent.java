package commands.entities.eventsourcedentity;

// tag::event-tag[]
import org.axonframework.eventsourcing.annotation.EventTag;

public record CourseCreatedEvent(
        @EventTag String courseId, // <1>
        String title,
        int capacity
) {}
// end::event-tag[]
