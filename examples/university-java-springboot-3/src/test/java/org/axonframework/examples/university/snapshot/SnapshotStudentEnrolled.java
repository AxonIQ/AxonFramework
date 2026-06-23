package org.axonframework.examples.university.snapshot;

import org.axonframework.eventsourcing.annotation.EventTag;

public record SnapshotStudentEnrolled(
        @EventTag String courseId,
        String studentId
) {
}
