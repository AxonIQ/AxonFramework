package commands.entities.entityhierarchies.advancedrouting;

import java.util.ArrayList;
import java.util.List;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;

// tag::custom-event-target-matcher[]
import org.axonframework.modelling.entity.annotation.EntityMember;

@EventSourcedEntity(tagKey = "courseId")
public class CourseEntity {

    @EntityMember(
        routingKey = "studentId",
        eventTargetMatcher = BroadcastToAllChildrenMatcher.class // <1>
    )
    private final List<EnrollmentEntity> enrollments = new ArrayList<>();
}
// end::custom-event-target-matcher[]
