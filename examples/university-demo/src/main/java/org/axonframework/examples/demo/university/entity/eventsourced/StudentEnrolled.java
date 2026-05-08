package org.axonframework.examples.demo.university.entity.eventsourced;

import org.axonframework.eventsourcing.annotation.EventTag;

public record StudentEnrolled(
        @EventTag(key = "courseId") String courseId,
        String studentId
) {
}
