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
