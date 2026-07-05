package tuning.snapshotting.programmatic;

import org.axonframework.eventsourcing.EventSourcedEntityFactory;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.snapshot.api.SnapshotPolicy;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;

/**
 * Shows how a {@link SnapshotPolicy} is plugged into the declarative {@link EventSourcedEntityModule} builder on the
 * snapshotting page.
 */
class SnapshotPolicyConfiguration {

    static EventSourcedEntityModule<CourseId, Course> configure() {
        // tag::declarative-snapshot-policy[]
        SnapshotPolicy snapshotPolicy = SnapshotPolicy.afterEvents(5)
                .or(SnapshotPolicy.whenEventMatches(
                        msg -> msg.type().qualifiedName().equals(
                                new QualifiedName(CourseRenamed.class)
                        )
                ));

        EventSourcedEntityModule<CourseId, Course> courseModule =
                EventSourcedEntityModule.declarative(CourseId.class, Course.class)
                                        // other entity configuration omitted
        // end::declarative-snapshot-policy[]
                                        .messagingModel((config, model) -> model.build())
                                        .entityFactory(c -> EventSourcedEntityFactory.fromNoArgument(Course::new))
                                        .criteriaResolver(c -> (id, ctx) -> EventCriteria.havingTags(
                                                Tag.of("courseId", id.value())
                                        ))
        // tag::declarative-snapshot-policy[]
                                        .snapshotPolicy(c -> snapshotPolicy)
                                        .build();
        // end::declarative-snapshot-policy[]
        return courseModule;
    }
}

/**
 * Identifier for the {@link Course} entity used by this sample.
 */
record CourseId(String value) {
}

/**
 * Placeholder entity used by this sample; its command handling and event sourcing behavior is not relevant here.
 */
class Course {
}

/**
 * Placeholder event used to demonstrate {@link SnapshotPolicy#whenEventMatches(java.util.function.Predicate)}.
 */
record CourseRenamed(CourseId courseId, String newName) {
}
