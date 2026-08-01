package org.axonframework.examples.demo.university.faculty.write.createcourse;

import org.axonframework.examples.demo.university.faculty.FacultyTags;
import org.axonframework.examples.demo.university.faculty.Ids;
import org.axonframework.examples.demo.university.faculty.events.CourseCreated;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.modelling.annotation.InjectEntity;
import org.jspecify.annotations.Nullable;

import java.util.List;

class CreateCourseCommandHandler {

    @CommandHandler
    void handle(
            CreateCourse command,
            @Nullable @InjectEntity(idProperty = FacultyTags.COURSE_ID) State state,
            EventAppender eventAppender
    ) {
        var events = decide(command, state);
        eventAppender.append(events);
    }

    private List<CourseCreated> decide(CreateCourse command, @Nullable State state) {
        if (state != null) {
            return List.of();
        }
        return List.of(new CourseCreated(Ids.FACULTY_ID, command.courseId(), command.name(), command.capacity()));
    }

    @EventSourcedEntity(tagKey = FacultyTags.COURSE_ID)
    static final class State {

        private boolean created;

        @EntityCreator
        private State() {
            this.created = false;
        }

        @EventSourcingHandler
        private State apply(CourseCreated event) {
            this.created = true;
            return this;
        }
    }
}
