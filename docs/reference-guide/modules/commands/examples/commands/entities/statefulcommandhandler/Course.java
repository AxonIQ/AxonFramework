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
package commands.entities.statefulcommandhandler;

import java.util.ArrayList;
import java.util.List;

// tag::course-entity[]
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;

@EventSourcedEntity(tagKey = "courseId")
public class Course {

    private String courseId;
    private String name;
    private int capacity;

    @EntityCreator
    public Course() {}

    @EventSourcingHandler
    void on(CourseCreated event) {
        this.courseId = event.courseId();
        this.name = event.name();
        this.capacity = event.capacity();
    }

    @EventSourcingHandler
    void on(CourseRenamed event) {
        this.name = event.name();
    }

    String courseId() { return courseId; }
    String name() { return name; }
    int capacity() { return capacity; }
// end::course-entity[]

    private final List<String> studentsSubscribed = new ArrayList<>();

    @EventSourcingHandler
    void on(StudentSubscribedToCourse event) {
        this.studentsSubscribed.add(event.studentId());
    }

    List<String> studentsSubscribed() { return studentsSubscribed; }
// tag::course-entity[]
}
// end::course-entity[]
