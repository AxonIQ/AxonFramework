package org.axonframework.examples.university.hierarchy;

import org.axonframework.modelling.annotation.TargetEntityId;

public record DropEnrollment(
        @TargetEntityId String courseId,
        String studentId,
        String reason
) {
}
