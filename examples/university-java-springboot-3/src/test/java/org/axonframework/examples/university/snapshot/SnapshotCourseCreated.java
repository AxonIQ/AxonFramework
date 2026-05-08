package org.axonframework.examples.university.snapshot;

import org.axonframework.eventsourcing.annotation.EventTag;

public record SnapshotCourseCreated(
        @EventTag String courseId,
        String title,
        int capacity
) {
}
