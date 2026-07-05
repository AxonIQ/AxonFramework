package commands.entities.entityhierarchies.autodetected;

import org.axonframework.modelling.annotation.TargetEntityId;

record CreateCourse(@TargetEntityId String courseId, String title, int capacity) {
}
