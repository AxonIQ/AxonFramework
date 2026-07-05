package commands.entities.eventsourcedentity.declarative;

import org.axonframework.modelling.annotation.TargetEntityId;

record EnrollStudentCommand(@TargetEntityId String courseId, String studentId) {
}
