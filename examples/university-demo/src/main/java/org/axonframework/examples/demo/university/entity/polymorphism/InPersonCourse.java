package org.axonframework.examples.demo.university.entity.polymorphism;

import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;

public class InPersonCourse extends CourseEntity {

    private String location;

    public InPersonCourse(CourseCreated event) {
        courseId = event.courseId();
        title = event.title();
        capacity = event.capacity();
        location = event.typeData();
    }

    public String getLocation() {
        return location;
    }

    @CommandHandler
    public void handle(UpdateLocation cmd, EventAppender appender) {
        appender.append(new LocationUpdated(courseId, cmd.newLocation()));
    }

    @EventSourcingHandler
    private void on(LocationUpdated e) {
        location = e.newLocation();
    }
}
