package events.eventstoreinternals.criteria;

// tag::custom-criteria-resolver[]
import org.axonframework.eventsourcing.CriteriaResolver;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;

public class CustomCriteriaResolver implements CriteriaResolver<SubscriptionId> {

    @Override
    public EventCriteria resolve(SubscriptionId id, ProcessingContext context) {
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
}
// end::custom-criteria-resolver[]
