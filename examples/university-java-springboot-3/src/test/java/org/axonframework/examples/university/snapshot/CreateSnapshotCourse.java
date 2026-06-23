package org.axonframework.examples.university.snapshot;

import org.axonframework.modelling.annotation.TargetEntityId;

public record CreateSnapshotCourse(
        @TargetEntityId String courseId,
        String title,
        int capacity
) {
}
