package org.axonframework.examples.demo.university.entity.polymorphism;

import org.axonframework.modelling.annotation.TargetEntityId;

public record UpdateLocation(
        @TargetEntityId String courseId,
        String newLocation
) {
}
