package commands.entities.entitycreator.identifier;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.eventsourcing.annotation.reflection.InjectEntityId;

// tag::identifier-entity-creator[]
@EventSourcedEntity(tagKey = "courseId")
public class CourseEntity {

    private final String courseId; // <1>
    // other state fields, command handlers, event sourcing handlers...

    @EntityCreator // <2>
    protected CourseEntity(@InjectEntityId String courseId) {
        this.courseId = courseId;
    }
}
// end::identifier-entity-creator[]
