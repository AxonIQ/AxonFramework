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
package commands.entities.entitypolymorphism.autodetected;

// tag::autodetected-entity[]
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;

@EventSourcedEntity(tagKey = "courseId", concreteTypes = {OnlineCourse.class, InPersonCourse.class}) // <1>
public abstract class CourseEntity {

    // omitted: state, command handlers, event sourcing handlers
    // see xref:commands:entities/event-sourced-entity.adoc[Event-sourced entities] for the structure of these members.

    @EntityCreator
    public static CourseEntity create(CourseCreated event) { // <2>
        return switch (event.courseType()) {
            case ONLINE    -> new OnlineCourse(event);
            case IN_PERSON -> new InPersonCourse(event);
        };
    }
}
// end::autodetected-entity[]
