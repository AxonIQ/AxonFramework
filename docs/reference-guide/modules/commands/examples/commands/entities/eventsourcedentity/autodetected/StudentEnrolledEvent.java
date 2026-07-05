package commands.entities.eventsourcedentity.autodetected;

import org.axonframework.eventsourcing.annotation.EventTag;

record StudentEnrolledEvent(@EventTag String courseId, String studentId) {
}
