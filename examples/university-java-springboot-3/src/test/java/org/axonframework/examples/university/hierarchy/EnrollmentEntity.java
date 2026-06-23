package org.axonframework.examples.university.hierarchy;

import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;

public class EnrollmentEntity {

    private final String studentId;
    private boolean dropped;

    public EnrollmentEntity(String studentId) {
        this.studentId = studentId;
    }

    public String getStudentId() {
        return studentId;
    }

    @CommandHandler
    public void handle(DropEnrollment cmd, EventAppender appender) {
        if (dropped) {
            throw new IllegalStateException("Enrollment already dropped");
        }
        appender.append(new EnrollmentDropped(cmd.courseId(), studentId, cmd.reason()));
    }

    @EventSourcingHandler
    private void on(EnrollmentDropped e) {
        dropped = true;
    }
}
