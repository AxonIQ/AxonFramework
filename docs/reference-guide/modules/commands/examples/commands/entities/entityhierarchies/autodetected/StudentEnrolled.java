package commands.entities.entityhierarchies.autodetected;

import org.axonframework.eventsourcing.annotation.EventTag;

record StudentEnrolled(@EventTag String courseId, String studentId) {
}
