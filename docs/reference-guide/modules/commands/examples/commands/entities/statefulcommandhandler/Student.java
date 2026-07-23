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

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;

import java.util.ArrayList;
import java.util.List;

@EventSourcedEntity(tagKey = "studentId")
class Student {

    private String studentId;
    private final List<String> subscribedCourses = new ArrayList<>();

    @EntityCreator
    public Student() {}

    @EventSourcingHandler
    void on(StudentEnrolledInFaculty event) {
        this.studentId = event.studentId();
    }

    @EventSourcingHandler
    void on(StudentSubscribedToCourse event) {
        this.subscribedCourses.add(event.courseId());
    }

    String studentId() { return studentId; }
    List<String> subscribedCourses() { return subscribedCourses; }
}
