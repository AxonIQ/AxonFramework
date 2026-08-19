package org.axonframework.examples.demo.university.faculty.write.enrollstudent;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.examples.demo.university.faculty.FacultyTags;
import org.axonframework.examples.demo.university.faculty.Ids;
import org.axonframework.examples.demo.university.faculty.events.StudentEnrolledInFaculty;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;
import org.jspecify.annotations.Nullable;

public class EnrollStudentInFacultyCommandHandler {

    @EventSourcedEntity(tagKey = FacultyTags.STUDENT_ID)
    public static class Student {

        private boolean exists;

        @EntityCreator
        private Student() {
        }

        @EventSourcingHandler
        void evolve(StudentEnrolledInFaculty enrolledInFaculty) {
            exists = true;
        }
    }

    @CommandHandler
    void handle(
            EnrollStudentInFaculty command,
            @Nullable @InjectEntity(idProperty = "studentId") Student student,
            EventAppender eventAppender
    ) {
        if (student == null) {
            eventAppender.append(new StudentEnrolledInFaculty(
                    Ids.FACULTY_ID, command.studentId(), command.firstName(), command.lastName()
            ));
        }
    }
}
