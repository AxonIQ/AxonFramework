package org.axonframework.examples.demo.university.entity.polymorphism;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;

@EventSourcedEntity(tagKey = "courseId", concreteTypes = {OnlineCourse.class, InPersonCourse.class})
public abstract class CourseEntity {

    protected String courseId;
    protected String title;
    protected int capacity;
    protected int enrolledCount;

    @CommandHandler
    public static String handle(CreateCourse cmd, EventAppender appender) {
        appender.append(new CourseCreated(cmd.courseId(), cmd.title(), cmd.capacity(), cmd.courseType(), cmd.typeData()));
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
    protected void on(StudentEnrolled e) {
        enrolledCount++;
    }

    @EntityCreator
    public static CourseEntity create(CourseCreated event) {
        return switch (event.courseType()) {
            case ONLINE -> new OnlineCourse(event);
            case IN_PERSON -> new InPersonCourse(event);
        };
    }
}
