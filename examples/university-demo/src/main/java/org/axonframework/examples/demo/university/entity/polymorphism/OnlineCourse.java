package org.axonframework.examples.demo.university.entity.polymorphism;

import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;

public class OnlineCourse extends CourseEntity {

    private String platformUrl;

    public OnlineCourse(CourseCreated event) {
        courseId = event.courseId();
        title = event.title();
        capacity = event.capacity();
        platformUrl = event.typeData();
    }

    public String getPlatformUrl() {
        return platformUrl;
    }

    @CommandHandler
    public void handle(UpdatePlatformUrl cmd, EventAppender appender) {
        appender.append(new PlatformUrlUpdated(courseId, cmd.newUrl()));
    }

    @EventSourcingHandler
    private void on(PlatformUrlUpdated e) {
        platformUrl = e.newUrl();
    }
}
