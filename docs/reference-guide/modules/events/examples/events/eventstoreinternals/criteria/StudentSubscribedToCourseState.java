package events.eventstoreinternals.criteria;

// tag::event-criteria-builder[]
import org.axonframework.eventsourcing.annotation.EventCriteriaBuilder;
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;

@EventSourcedEntity
public class StudentSubscribedToCourseState {

    @EventCriteriaBuilder
    private static EventCriteria resolveCriteria(SubscriptionId id) {
        String courseId = id.courseId().toString();
        String studentId = id.studentId().toString();
        return EventCriteria.either(
                EventCriteria
                        .havingTags(Tag.of("courseID", courseId))
                        .andBeingOneOfTypes(
                                CourseCreated.class.getName(),
                                CourseCapacityChanged.class.getName(),
                                StudentSubscribedToCourse.class.getName(),
                                StudentUnsubscribedFromCourse.class.getName()
                        ),
                EventCriteria
                        .havingTags(Tag.of("studentId", studentId))
                        .andBeingOneOfTypes(
                                StudentEnrolledInFaculty.class.getName(),
                                StudentSubscribedToCourse.class.getName(),
                                StudentUnsubscribedFromCourse.class.getName()
                        )
        );
    }

    // Entity fields and event sourcing handlers omitted...
}
// end::event-criteria-builder[]

record SubscriptionId(String courseId, String studentId) {

}

record CourseCreated() {

}

record CourseCapacityChanged() {

}

record StudentSubscribedToCourse() {

}

record StudentUnsubscribedFromCourse() {

}

record StudentEnrolledInFaculty() {

}
