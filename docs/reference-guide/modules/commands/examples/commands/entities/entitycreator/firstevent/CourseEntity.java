package commands.entities.entitycreator.firstevent;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;

// tag::first-event-entity-creator[]
@EventSourcedEntity(tagKey = "courseId")
public class CourseEntity {

    private final String courseId;
    private final int initialCapacity;
    // other final fields, command handlers, event sourcing handlers...

    @EntityCreator // <1>
    protected CourseEntity(CourseCreatedEvent event) {
        this.courseId = event.courseId();
        this.initialCapacity = event.capacity();
    }
// end::first-event-entity-creator[]

    CourseEntity(String courseId, int capacity) {
        this.courseId = courseId;
        this.initialCapacity = capacity;
    }
// tag::first-event-entity-creator[]
}
// end::first-event-entity-creator[]
