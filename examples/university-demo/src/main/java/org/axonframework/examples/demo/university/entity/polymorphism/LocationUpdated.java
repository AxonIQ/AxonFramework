package org.axonframework.examples.demo.university.entity.polymorphism;

import org.axonframework.eventsourcing.annotation.EventTag;

public record LocationUpdated(
        @EventTag(key = "courseId") String courseId,
        String newLocation
) {
}
