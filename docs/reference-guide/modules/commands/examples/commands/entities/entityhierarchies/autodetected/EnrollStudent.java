package commands.entities.entityhierarchies.autodetected;

import org.axonframework.modelling.annotation.TargetEntityId;

record EnrollStudent(@TargetEntityId String courseId, String studentId) {
}
