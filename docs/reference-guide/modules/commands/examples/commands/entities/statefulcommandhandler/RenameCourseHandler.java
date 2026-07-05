package commands.entities.statefulcommandhandler;

import commands.entities.statefulcommandhandler.CourseCommands.RenameCourse;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;
import org.springframework.stereotype.Component;

// tag::vertical-slice-handler[]
@Component
public class RenameCourseHandler {

    @CommandHandler
    void handle(RenameCourse command,
                @InjectEntity Course course,
                EventAppender eventAppender) {
        if (course.courseId() == null) {
            throw new IllegalStateException("Course does not exist");
        }
        if (!command.name().equals(course.name())) {
            eventAppender.append(new CourseRenamed(command.courseId(), command.name()));
        }
    }
}
// end::vertical-slice-handler[]
