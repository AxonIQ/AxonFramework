package org.axonframework.examples.demo.university.faculty.write.changecoursecapacity;

import org.axonframework.examples.demo.university.faculty.FacultyTags;
import org.axonframework.examples.demo.university.faculty.Ids;
import org.axonframework.examples.demo.university.faculty.events.CourseCapacityChanged;
import org.axonframework.examples.demo.university.faculty.events.CourseCreated;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.modelling.annotation.InjectEntity;
import org.jspecify.annotations.Nullable;

import java.util.List;

class ChangeCourseCapacityCommandHandler {

    @CommandHandler
    void handle(
            ChangeCourseCapacity command,
            @Nullable @InjectEntity State state,
            EventAppender eventAppender
    ) {
        var events = decide(command, state);
        eventAppender.append(events);
    }

    private List<CourseCapacityChanged> decide(ChangeCourseCapacity command, @Nullable State state) {
        if (state == null) {
            throw new IllegalStateException("Course with given id does not exist");
        }
        if (command.capacity() == state.capacity) {
            return List.of();
        }
        return List.of(new CourseCapacityChanged(Ids.FACULTY_ID, command.courseId(), command.capacity()));
    }

    @EventSourcedEntity(tagKey = FacultyTags.COURSE_ID)
    static class State {

        private boolean created = false;
        private int capacity;

        @EntityCreator
        public State() {
        }

        @EventSourcingHandler
        void evolve(CourseCreated event) {
            this.created = true;
            this.capacity = event.capacity();
        }

        @EventSourcingHandler
        void evolve(CourseCapacityChanged event) {
            this.capacity = event.capacity();
        }
    }
}