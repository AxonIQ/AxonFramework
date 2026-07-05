package commands.entities.entityhierarchies.autodetected;

// tag::autodetected-entity[]
import java.util.ArrayList;
import java.util.List;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.entity.annotation.EntityMember;

@EventSourcedEntity(tagKey = "courseId")
public class CourseEntity {

    private String courseId;
    private int capacity;

    @EntityMember(routingKey = "studentId") // <1>
    private final List<EnrollmentEntity> enrollments = new ArrayList<>();

    @CommandHandler
    public static String handle(CreateCourse cmd, EventAppender appender) {
        appender.append(new CourseCreated(cmd.courseId(), cmd.title(), cmd.capacity()));
        return cmd.courseId();
    }

    @CommandHandler
    public void handle(EnrollStudent cmd, EventAppender appender) {
        if (enrollments.size() >= capacity) {
            throw new IllegalStateException("Course is full");
        }
        appender.append(new StudentEnrolled(courseId, cmd.studentId()));
    }

    @EventSourcingHandler
    private void on(CourseCreated event) {
        this.courseId = event.courseId();
        this.capacity = event.capacity();
    }

    @EventSourcingHandler
    private void on(StudentEnrolled event) {
        enrollments.add(new EnrollmentEntity(event.studentId())); // <2>
    }

    @EntityCreator
    protected CourseEntity() {
    }
}
// end::autodetected-entity[]
