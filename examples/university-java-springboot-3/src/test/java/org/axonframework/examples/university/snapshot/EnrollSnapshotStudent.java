package org.axonframework.examples.university.snapshot;

import org.axonframework.modelling.annotation.TargetEntityId;

public record EnrollSnapshotStudent(
        @TargetEntityId String courseId,
        String studentId
) {
}
