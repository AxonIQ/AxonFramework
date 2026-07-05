package commands.entities.eventsourcedentity;

// tag::entity-tag-key[]
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;

@EventSourcedEntity(tagKey = "courseId") // <2>
public class CourseEntity {
    // ...
}
// end::entity-tag-key[]
