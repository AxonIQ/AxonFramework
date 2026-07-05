package commands.entities.entityhierarchies.autodetected;

import org.axonframework.eventsourcing.annotation.EventTag;

record CourseCreated(@EventTag String courseId, String title, int capacity) {
}
