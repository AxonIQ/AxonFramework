package commands.entities.eventsourcedentity.autodetected;

import org.axonframework.eventsourcing.annotation.EventTag;

record CourseCreatedEvent(@EventTag String courseId, String title, int capacity) {
}
