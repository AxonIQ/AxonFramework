package commands.entities.statefulcommandhandler;

import java.util.ArrayList;
import java.util.List;

// tag::course-entity[]
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;

@EventSourcedEntity(tagKey = "courseId")
public class Course {

    private String courseId;
    private String name;
    private int capacity;

    @EntityCreator
    public Course() {}

    @EventSourcingHandler
    void on(CourseCreated event) {
        this.courseId = event.courseId();
        this.name = event.name();
        this.capacity = event.capacity();
    }

    @EventSourcingHandler
    void on(CourseRenamed event) {
        this.name = event.name();
    }

    String courseId() { return courseId; }
    String name() { return name; }
    int capacity() { return capacity; }
// end::course-entity[]

    private final List<String> studentsSubscribed = new ArrayList<>();

    @EventSourcingHandler
    void on(StudentSubscribedToCourse event) {
        this.studentsSubscribed.add(event.studentId());
    }

    List<String> studentsSubscribed() { return studentsSubscribed; }
// tag::course-entity[]
}
// end::course-entity[]
