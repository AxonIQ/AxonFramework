/*
 * Copyright (c) 2010-2026. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
