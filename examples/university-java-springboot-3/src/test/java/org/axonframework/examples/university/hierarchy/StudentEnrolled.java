package org.axonframework.examples.university.hierarchy;

import org.axonframework.eventsourcing.annotation.EventTag;

public record StudentEnrolled(
        @EventTag(key = "courseId") String courseId,
        String studentId
) {
}
