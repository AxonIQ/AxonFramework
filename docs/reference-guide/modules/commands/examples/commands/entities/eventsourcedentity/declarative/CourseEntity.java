package commands.entities.eventsourcedentity.declarative;

import org.axonframework.messaging.eventhandling.gateway.EventAppender;

// tag::entity-members[]
public class CourseEntity {

    private String courseId;
    private String title;
    private int capacity;
    private int enrolledCount;

    public static void create(CreateCourseCommand cmd, EventAppender appender) { // <1>
        if (cmd.capacity() <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        appender.append(new CourseCreatedEvent(cmd.courseId(), cmd.title(), cmd.capacity()));
    }

    public void enroll(EnrollStudentCommand cmd, EventAppender appender) { // <2>
        if (enrolledCount >= capacity) {
            throw new IllegalStateException("Course is full");
        }
        appender.append(new StudentEnrolledEvent(courseId, cmd.studentId()));
    }

    void on(CourseCreatedEvent event) { // <3>
        this.courseId = event.courseId();
        this.title = event.title();
        this.capacity = event.capacity();
    }

    void on(StudentEnrolledEvent event) {
        this.enrolledCount++;
    }

    protected CourseEntity() {} // <4>
}
// end::entity-members[]
