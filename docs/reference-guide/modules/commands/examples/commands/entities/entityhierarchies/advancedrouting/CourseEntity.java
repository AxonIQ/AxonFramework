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
