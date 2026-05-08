package org.axonframework.examples.demo.university.entity.eventsourced;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;

@EventSourcedEntity(tagKey = "courseId")
public class CourseEntity {

    private String courseId;
    private String title;
    private int capacity;
    private int enrolledCount;

    @EntityCreator
    protected CourseEntity() {
    }

    @CommandHandler
    public static String handle(CreateCourse cmd, EventAppender appender) {
        if (cmd.capacity() <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        appender.append(new CourseCreated(cmd.courseId(), cmd.title(), cmd.capacity()));
        return cmd.courseId();
    }

    @CommandHandler
    public void handle(EnrollStudent cmd, EventAppender appender) {
        if (enrolledCount >= capacity) {
            throw new IllegalStateException("Course is full");
        }
        appender.append(new StudentEnrolled(courseId, cmd.studentId()));
    }

    public String getCourseId() {
        return courseId;
    }

    public String getTitle() {
        return title;
    }

    public int getCapacity() {
        return capacity;
    }

    public int getEnrolledCount() {
        return enrolledCount;
    }

    @EventSourcingHandler
    private void on(CourseCreated e) {
        courseId = e.courseId();
        title = e.title();
        capacity = e.capacity();
    }

    @EventSourcingHandler
    private void on(StudentEnrolled e) {
        enrolledCount++;
    }
}
