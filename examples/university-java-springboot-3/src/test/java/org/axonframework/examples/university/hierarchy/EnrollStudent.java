package org.axonframework.examples.university.hierarchy;

import org.axonframework.modelling.annotation.TargetEntityId;

public record EnrollStudent(
        @TargetEntityId String courseId,
        String studentId
) {
}
