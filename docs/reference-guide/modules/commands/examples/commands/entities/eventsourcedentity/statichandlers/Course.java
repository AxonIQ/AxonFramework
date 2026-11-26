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
package commands.entities.eventsourcedentity.statichandlers;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.jspecify.annotations.Nullable;

// tag::static-event-sourcing-handlers[]
@EventSourcedEntity(tagKey = "courseId")
record Course(String courseId, String name, int capacity) {

    @EventSourcingHandler
    static Course on(CourseCreated event, @Nullable Course state) {
        return new Course(event.courseId(), event.name(), event.capacity());
    }

    @EventSourcingHandler
    static @Nullable Course on(CourseCapacityChanged event, @Nullable Course state) {
        return state == null ? null : new Course(state.courseId(), state.name(), event.capacity());
    }
}
// end::static-event-sourcing-handlers[]
