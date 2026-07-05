package commands.entities.statefulcommandhandler;

import commands.entities.statefulcommandhandler.CourseCommands.CreateCourse;
import commands.entities.statefulcommandhandler.CourseCommands.RenameCourse;

// tag::stateful-command-handler[]
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;
import org.springframework.stereotype.Component;

@Component
public class CourseCommandHandler {

    @CommandHandler // <1>
    public String handle(CreateCourse command, EventAppender eventAppender) {
        if (command.capacity() <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        eventAppender.append(new CourseCreated(command.courseId(), command.name(), command.capacity()));
        return command.courseId();
    }

    @CommandHandler
    void handle(RenameCourse command,
                @InjectEntity Course course, // <2>
                EventAppender eventAppender) {
        if (course.courseId() == null) {
            throw new IllegalStateException("Course does not exist");
        }
        if (!command.name().equals(course.name())) { // <3>
            eventAppender.append(new CourseRenamed(command.courseId(), command.name()));
        }
    }
}
// end::stateful-command-handler[]
