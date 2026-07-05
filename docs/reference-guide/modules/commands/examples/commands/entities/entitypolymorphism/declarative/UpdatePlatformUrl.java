package commands.entities.entitypolymorphism.declarative;

import org.axonframework.modelling.annotation.TargetEntityId;

record UpdatePlatformUrl(@TargetEntityId String courseId, String newUrl) {
}
