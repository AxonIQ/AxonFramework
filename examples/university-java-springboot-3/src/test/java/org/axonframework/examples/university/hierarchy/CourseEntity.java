package org.axonframework.examples.university.hierarchy;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.entity.annotation.EntityMember;

import java.util.ArrayList;
import java.util.List;

@EventSourcedEntity(tagKey = "courseId")
public class CourseEntity {

    private String courseId;
    private int capacity;

    @EntityMember(routingKey = "studentId")
    private final List<EnrollmentEntity> enrollments = new ArrayList<>();

    @EntityCreator
    protected CourseEntity() {
    }

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
    private void on(CourseCreated e) {
        courseId = e.courseId();
        capacity = e.capacity();
    }

    @EventSourcingHandler
    private void on(StudentEnrolled e) {
        enrollments.add(new EnrollmentEntity(e.studentId()));
    }
}
