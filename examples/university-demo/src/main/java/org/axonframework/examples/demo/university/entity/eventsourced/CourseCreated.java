package org.axonframework.examples.demo.university.entity.eventsourced;

import org.axonframework.eventsourcing.annotation.EventTag;

public record CourseCreated(
        @EventTag(key = "courseId") String courseId,
        String title,
        int capacity
) {
}
