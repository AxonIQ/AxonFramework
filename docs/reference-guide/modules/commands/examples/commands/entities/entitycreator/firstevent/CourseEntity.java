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
package commands.entities.entitycreator.firstevent;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;

// tag::first-event-entity-creator[]
@EventSourcedEntity(tagKey = "courseId")
public class CourseEntity {

    private final String courseId;
    private final int initialCapacity;
    // other final fields, command handlers, event sourcing handlers...

    @EntityCreator // <1>
    protected CourseEntity(CourseCreatedEvent event) {
        this.courseId = event.courseId();
        this.initialCapacity = event.capacity();
    }
// end::first-event-entity-creator[]

    CourseEntity(String courseId, int capacity) {
        this.courseId = courseId;
        this.initialCapacity = capacity;
    }
// tag::first-event-entity-creator[]
}
// end::first-event-entity-creator[]
