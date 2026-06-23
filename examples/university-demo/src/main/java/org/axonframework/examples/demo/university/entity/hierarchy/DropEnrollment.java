package org.axonframework.examples.demo.university.entity.hierarchy;

import org.axonframework.modelling.annotation.TargetEntityId;

public record DropEnrollment(
        @TargetEntityId String courseId,
        String studentId,
        String reason
) {
}
