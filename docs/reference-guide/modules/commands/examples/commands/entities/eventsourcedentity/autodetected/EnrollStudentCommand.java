package commands.entities.eventsourcedentity.autodetected;

import org.axonframework.modelling.annotation.TargetEntityId;

record EnrollStudentCommand(@TargetEntityId String courseId, String studentId) {
}
