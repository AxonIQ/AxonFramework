package commands.entities.entitycreator.noargument;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;

// tag::no-arg-entity-creator[]
@EventSourcedEntity(tagKey = "courseId")
public class CourseEntity {

    // state fields, command handlers, event sourcing handlers...

    @EntityCreator // <1>
    protected CourseEntity() {
    }
}
// end::no-arg-entity-creator[]
