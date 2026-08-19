package org.axonframework.examples.demo.university.faculty.write.renamecourse;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.examples.demo.university.faculty.FacultyTags;
import org.axonframework.examples.demo.university.faculty.Ids;
import org.axonframework.examples.demo.university.faculty.events.CourseCreated;
import org.axonframework.examples.demo.university.faculty.events.CourseRenamed;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;

import java.util.List;
import java.util.Optional;

class RenameCourseCommandHandler {

    @CommandHandler
    void handle(
            RenameCourse command,
            @InjectEntity Optional<State> state,
            EventAppender eventAppender
    ) {
        var events = decide(command, state.orElse(State.initial()));
        eventAppender.append(events);
    }

    private List<CourseRenamed> decide(RenameCourse command, State state) {
        if (!state.created) {
            throw new IllegalStateException("Course with given id does not exist");
        }
        if (command.name().equals(state.name)) {
            return List.of();
        }
        return List.of(new CourseRenamed(Ids.FACULTY_ID, command.courseId(), command.name()));
    }

    @EventSourcedEntity(tagKey = FacultyTags.COURSE_ID)
    record State(
            boolean created,
            String name
    ) {

        @EntityCreator
        static State initial() {
            return new State(false, null);
        }

        @EventSourcingHandler
        State evolve(CourseCreated event) {
            return new State(true, event.name());
        }

        @EventSourcingHandler
        State evolve(CourseRenamed event) {
            return new State(this.created, event.name());
        }

    }

// Alternative State implementation based on mutable class
//    @EventSourcedEntity(tagKey = FacultyTags.COURSE_ID)
//    static class State {
//
//        private boolean created = false;
//        private String name;
//
//        @EntityCreator
//        public State() {
//        }
//
//        @EventSourcingHandler
//        void evolve(CourseCreated event) {
//            this.created = true;
//            this.name = event.name();
//        }
//
//        @EventSourcingHandler
//        void evolve(CourseRenamed event) {
//            this.name = event.name();
//        }
//    }
}