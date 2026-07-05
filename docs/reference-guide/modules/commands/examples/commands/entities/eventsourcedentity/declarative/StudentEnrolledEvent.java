package commands.entities.eventsourcedentity.declarative;

import org.axonframework.eventsourcing.annotation.EventTag;

record StudentEnrolledEvent(@EventTag String courseId, String studentId) {
}
