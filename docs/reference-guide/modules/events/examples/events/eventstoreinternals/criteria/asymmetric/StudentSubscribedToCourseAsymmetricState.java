package events.eventstoreinternals.criteria.asymmetric;

// tag::asymmetric-criteria-imports[]
import org.axonframework.eventsourcing.annotation.AppendCriteriaBuilder;
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.SourcingCriteriaBuilder;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
// end::asymmetric-criteria-imports[]

// tag::asymmetric-criteria[]
@EventSourcedEntity
public class StudentSubscribedToCourseAsymmetricState {

    @SourcingCriteriaBuilder
    private static EventCriteria resolveSourcingCriteria(SubscriptionId id) {
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

    @AppendCriteriaBuilder
    private static EventCriteria resolveAppendCriteria(SubscriptionId id) {
        String courseId = id.courseId().toString();
        String studentId = id.studentId().toString();
        return EventCriteria.either(
                EventCriteria
                        .havingTags(Tag.of("courseID", courseId))
                        .andBeingOneOfTypes(
                                CourseCapacityChanged.class.getName(),
                                StudentSubscribedToCourse.class.getName()
                        ),
                EventCriteria
                        .havingTags(Tag.of("studentId", studentId))
                        .andBeingOneOfTypes(
                                StudentSubscribedToCourse.class.getName()
                        )
        );
    }

    // Entity fields and event sourcing handlers omitted...
}
// end::asymmetric-criteria[]

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
