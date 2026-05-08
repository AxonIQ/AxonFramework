package org.axonframework.examples.demo.university.entity.polymorphism;

import org.axonframework.modelling.annotation.TargetEntityId;

public record UpdatePlatformUrl(
        @TargetEntityId String courseId,
        String newUrl
) {
}
