package commands.entities.eventsourcedentity.declarative;

import org.axonframework.eventsourcing.annotation.EventTag;

record CourseCreatedEvent(@EventTag String courseId, String title, int capacity) {
}
