package org.axonframework.examples.university.snapshot;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;

@EventSourcedEntity(tagKey = "courseId")
public class SnapshotCourseEntity {

    private String courseId;
    private int capacity;
    private int enrolledCount;

    @EntityCreator
    public SnapshotCourseEntity() {
    }

    @CommandHandler
    public static String handle(CreateSnapshotCourse cmd, EventAppender appender) {
        if (cmd.capacity() <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        appender.append(new SnapshotCourseCreated(cmd.courseId(), cmd.title(), cmd.capacity()));
        return cmd.courseId();
    }

    @CommandHandler
    public void handle(EnrollSnapshotStudent cmd, EventAppender appender) {
        if (enrolledCount >= capacity) {
            throw new IllegalStateException("Course is full");
        }
        appender.append(new SnapshotStudentEnrolled(courseId, cmd.studentId()));
    }

    @EventSourcingHandler
    private void on(SnapshotCourseCreated event) {
        this.courseId = event.courseId();
        this.capacity = event.capacity();
    }

    @EventSourcingHandler
    private void on(SnapshotStudentEnrolled event) {
        this.enrolledCount++;
    }
}
