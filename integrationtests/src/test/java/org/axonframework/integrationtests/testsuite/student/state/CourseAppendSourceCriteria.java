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

package org.axonframework.integrationtests.testsuite.student.state;

import org.axonframework.eventsourcing.annotation.AppendCriteriaBuilder;
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.SourceCriteriaBuilder;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.eventsourcing.annotation.reflection.InjectEntityId;
import org.axonframework.integrationtests.testsuite.student.events.StudentEnrolledEvent;
import org.axonframework.integrationtests.testsuite.student.events.StudentUnenrolledEvent;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;

import java.util.ArrayList;
import java.util.List;

/**
 * Entity demonstrating Dynamic Consistency Boundaries (DCB) using separate source and append criteria.
 * <p>
 * This entity loads both {@link StudentEnrolledEvent} and {@link StudentUnenrolledEvent} events for sourcing
 * (to calculate the current enrollment state), but only checks for conflicts on {@link StudentEnrolledEvent}
 * events when appending (allowing concurrent unenrollments without conflict).
 * <p>
 * Use case: Concurrent transactions that unenroll students should NOT conflict with transactions
 * that enroll students.
 *
 * @author Mateusz Nowak
 * @since 5.0.0
 */
@EventSourcedEntity(tagKey = "Course")
public class CourseAppendSourceCriteria {

    private String id;
    private List<String> studentsEnrolled = new ArrayList<>();

    @EntityCreator
    public CourseAppendSourceCriteria(@InjectEntityId String id) {
        this.id = id;
    }

    @EventSourcingHandler
    public void handle(StudentEnrolledEvent event) {
        studentsEnrolled.add(event.studentId());
    }

    @EventSourcingHandler
    public void handle(StudentUnenrolledEvent event) {
        studentsEnrolled.remove(event.studentId());
    }

    /**
     * Source criteria: Load both enroll AND unenroll events to calculate state.
     * <p>
     * This criteria is used when loading the entity to build its current state.
     * Both event types are needed to accurately represent the enrollment list.
     *
     * @param courseId The course identifier.
     * @return The criteria for sourcing events.
     */
    @SourceCriteriaBuilder
    public static EventCriteria sourceCriteria(String courseId) {
        return EventCriteria.havingTags(new Tag("Course", courseId))
                .andBeingOneOfTypes(
                        StudentEnrolledEvent.class.getName(),
                        StudentUnenrolledEvent.class.getName()
                );
    }

    /**
     * Append criteria: Only check for conflicts on enrollment events.
     * <p>
     * This criteria is used for consistency checking when appending new events.
     * By only checking for conflicts on {@link StudentEnrolledEvent}, we allow
     * concurrent unenrollments without triggering a conflict.
     *
     * @param courseId The course identifier.
     * @return The criteria for append conflict checking.
     */
    @AppendCriteriaBuilder
    public static EventCriteria appendCriteria(String courseId) {
        return EventCriteria.havingTags(new Tag("Course", courseId))
                .andBeingOneOfTypes(StudentEnrolledEvent.class.getName());
    }

    public String getId() {
        return id;
    }

    public List<String> getStudentsEnrolled() {
        return studentsEnrolled;
    }

    @Override
    public String toString() {
        return "CourseAppendSourceCriteria{" +
                "id='" + id + '\'' +
                ", studentsEnrolled=" + studentsEnrolled +
                '}';
    }
}
