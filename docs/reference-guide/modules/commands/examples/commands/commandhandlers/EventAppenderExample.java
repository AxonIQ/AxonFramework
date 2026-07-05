package commands.commandhandlers;

import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;

class EventAppenderExample {

    // tag::publishing-events-with-eventappender[]
    @CommandHandler
    public static CourseId handle(CreateCourse command, EventAppender eventAppender) { // <1>
        if (command.capacity() <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        eventAppender.append(new CourseCreated(Ids.FACULTY_ID, command.courseId(), command.name(), command.capacity())); // <2>
        return command.courseId();
    }

    @CommandHandler
    public void handle(RenameCourse command, EventAppender eventAppender) {
        eventAppender.append( // <3>
                new CourseRenamed(Ids.FACULTY_ID, command.courseId(), command.name())
        );
    }
    // end::publishing-events-with-eventappender[]
}
