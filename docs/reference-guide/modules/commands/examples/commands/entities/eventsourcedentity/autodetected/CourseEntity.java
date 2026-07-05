package commands.entities.eventsourcedentity.autodetected;

// tag::autodetected-entity[]
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;

@EventSourcedEntity(tagKey = "courseId") // <1>
public class CourseEntity {

    private String courseId;
    private String title;
    private int capacity;
    private int enrolledCount;

    @CommandHandler
    public static String handle(CreateCourseCommand cmd, EventAppender eventAppender) {
        if (cmd.capacity() <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        eventAppender.append(new CourseCreatedEvent(cmd.courseId(), cmd.title(), cmd.capacity()));
        return cmd.courseId();
    }

    @CommandHandler
    public void handle(EnrollStudentCommand cmd, EventAppender eventAppender) {
        if (enrolledCount >= capacity) {
            throw new IllegalStateException("Course is full");
        }
        eventAppender.append(new StudentEnrolledEvent(courseId, cmd.studentId()));
    }

    @EventSourcingHandler
    private void on(CourseCreatedEvent event) {
        this.courseId = event.courseId();
        this.title = event.title();
        this.capacity = event.capacity();
    }

    @EventSourcingHandler
    private void on(StudentEnrolledEvent event) {
        this.enrolledCount++;
    }

    @EntityCreator
    protected CourseEntity() {
    }
}
// end::autodetected-entity[]
