package commands.entities.statefulcommandhandler;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;

import java.util.ArrayList;
import java.util.List;

@EventSourcedEntity(tagKey = "studentId")
class Student {

    private String studentId;
    private final List<String> subscribedCourses = new ArrayList<>();

    @EntityCreator
    public Student() {}

    @EventSourcingHandler
    void on(StudentEnrolledInFaculty event) {
        this.studentId = event.studentId();
    }

    @EventSourcingHandler
    void on(StudentSubscribedToCourse event) {
        this.subscribedCourses.add(event.courseId());
    }

    String studentId() { return studentId; }
    List<String> subscribedCourses() { return subscribedCourses; }
}
