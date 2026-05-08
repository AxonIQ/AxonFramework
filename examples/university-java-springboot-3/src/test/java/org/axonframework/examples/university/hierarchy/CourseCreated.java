package org.axonframework.examples.university.hierarchy;

import org.axonframework.eventsourcing.annotation.EventTag;

public record CourseCreated(
        @EventTag(key = "courseId") String courseId,
        String title,
        int capacity
) {
}
