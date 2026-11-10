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
package commands.entities.eventsourcedentity.hierarchy.typechange;

import commands.entities.eventsourcedentity.CourseCreated;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;

// tag::type-change-allowed[]
sealed interface Course permits InitialCourse, CreatedCourse {

    int touches();

    Course withTouches(int touches);

    @EventSourcingHandler
    default Course countTouch(CourseCreated event) {
        return withTouches(touches() + 1);
    }
}

record InitialCourse(int touches) implements Course {

    public Course withTouches(int touches) {
        return new InitialCourse(touches);
    }

    @EventSourcingHandler
    CreatedCourse onCreated(CourseCreated event) {
        return new CreatedCourse(event.name(), touches);
    }
}

record CreatedCourse(String name, int touches) implements Course {

    public Course withTouches(int touches) {
        return new CreatedCourse(name, touches);
    }
}
// end::type-change-allowed[]
