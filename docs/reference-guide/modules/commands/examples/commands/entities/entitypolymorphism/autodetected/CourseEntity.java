package commands.entities.entitypolymorphism.autodetected;

// tag::autodetected-entity[]
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;

@EventSourcedEntity(tagKey = "courseId", concreteTypes = {OnlineCourse.class, InPersonCourse.class}) // <1>
public abstract class CourseEntity {

    // omitted: state, command handlers, event sourcing handlers
    // see xref:commands:entities/event-sourced-entity.adoc[Event-sourced entities] for the structure of these members.

    @EntityCreator
    public static CourseEntity create(CourseCreated event) { // <2>
        return switch (event.courseType()) {
            case ONLINE    -> new OnlineCourse(event);
            case IN_PERSON -> new InPersonCourse(event);
        };
    }
}
// end::autodetected-entity[]
