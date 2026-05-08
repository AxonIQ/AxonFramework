package org.axonframework.examples.demo.university.entity.hierarchy;

import org.axonframework.modelling.annotation.TargetEntityId;

public record EnrollStudent(
        @TargetEntityId String courseId,
        String studentId
) {
}
