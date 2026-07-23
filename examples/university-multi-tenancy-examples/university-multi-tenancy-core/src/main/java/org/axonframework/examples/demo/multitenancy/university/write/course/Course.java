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

package org.axonframework.examples.demo.multitenancy.university.write.course;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A course with a fixed number of seats, sourced from the events tagged with its
 * {@link CourseTags#COURSE_ID course identifier}. The command handler injects it to decide whether a
 * seat is still free before appending a {@link StudentEnrolledInCourse}.
 * <p>
 * The entity carries no tenant. It is sourced from whichever tenant's event store the message being
 * handled resolves to, so the same course identifier in two tenants reconstructs into two independent
 * courses. That is the per-tenant event-storage isolation this slice demonstrates.
 */
@EventSourcedEntity(tagKey = CourseTags.COURSE_ID)
class Course {

    private boolean open;
    private int capacity;
    private final Set<String> enrolledStudents = new LinkedHashSet<>();

    @EntityCreator
    Course() {
        // A fresh course, evolved from its events before the command handler sees it.
    }

    @EventSourcingHandler
    void evolve(CourseOpened event) {
        open = true;
        capacity = event.capacity();
    }

    @EventSourcingHandler
    void evolve(StudentEnrolledInCourse event) {
        enrolledStudents.add(event.studentId());
    }

    boolean isOpen() {
        return open;
    }

    boolean isFull() {
        return enrolledStudents.size() >= capacity;
    }

    boolean isEnrolled(String studentId) {
        return enrolledStudents.contains(studentId);
    }

    int capacity() {
        return capacity;
    }
}
