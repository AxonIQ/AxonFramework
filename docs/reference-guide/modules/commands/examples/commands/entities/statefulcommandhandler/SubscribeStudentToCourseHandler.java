package commands.entities.statefulcommandhandler;

// tag::injecting-multiple-entities[]
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;
import org.springframework.stereotype.Component;

@Component
public class SubscribeStudentToCourseHandler {

    private static final int MAX_COURSES_PER_STUDENT = 3;

    @CommandHandler
    void handle(
            SubscribeStudentToCourse command,
            @InjectEntity(idProperty = "courseId") Course course,     // <1>
            @InjectEntity(idProperty = "studentId") Student student,  // <2>
            EventAppender eventAppender
    ) {
        if (course.courseId() == null) {
            throw new IllegalStateException("Course does not exist");
        }
        if (student.studentId() == null) {
            throw new IllegalStateException("Student is not enrolled in the faculty");
        }
        if (student.subscribedCourses().size() >= MAX_COURSES_PER_STUDENT) {
            throw new IllegalStateException("Student is already subscribed to the maximum number of courses");
        }
        if (course.studentsSubscribed().size() >= course.capacity()) {
            throw new IllegalStateException("Course is fully booked");
        }
        eventAppender.append(new StudentSubscribedToCourse(command.courseId(), command.studentId()));
    }
}
// end::injecting-multiple-entities[]
