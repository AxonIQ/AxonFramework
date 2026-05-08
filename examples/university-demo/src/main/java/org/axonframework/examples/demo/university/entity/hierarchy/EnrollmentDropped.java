package org.axonframework.examples.demo.university.entity.hierarchy;

import org.axonframework.eventsourcing.annotation.EventTag;

public record EnrollmentDropped(
        @EventTag(key = "courseId") String courseId,
        String studentId,
        String reason
) {
}
