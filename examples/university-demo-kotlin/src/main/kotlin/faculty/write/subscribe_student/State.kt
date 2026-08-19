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

package org.axonframework.examples.university.faculty.write.subscribe_student

import org.axonframework.examples.university.faculty.FacultyTags
import org.axonframework.examples.university.faculty.events.CourseCreated
import org.axonframework.examples.university.faculty.events.StudentEnrolledInFaculty
import org.axonframework.examples.university.faculty.events.StudentSubscribedToCourse
import org.axonframework.examples.university.shared.ids.CourseId
import org.axonframework.examples.university.shared.ids.StudentId
import org.axonframework.examples.university.shared.ids.SubscriptionId
import org.axonframework.eventsourcing.annotation.EventCriteriaBuilder
import org.axonframework.eventsourcing.annotation.EventSourcedEntity
import org.axonframework.eventsourcing.annotation.EventSourcingHandler
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator
import org.axonframework.extension.kotlin.eventsourcing.evolveIf
import org.axonframework.messaging.core.ClassBasedMessageTypeResolver
import org.axonframework.messaging.eventstreaming.EventCriteria
import org.axonframework.messaging.eventstreaming.Tag

@EventSourcedEntity
internal class State @EntityCreator constructor() {
    companion object {

        const val MAX_COURSES_PER_STUDENT = 3
        val CLASS_MESSAGE_TYPE_RESOLVER = ClassBasedMessageTypeResolver()

        @JvmStatic
        @EventCriteriaBuilder
        fun resolveCriteria(id: SubscriptionId): EventCriteria = EventCriteria.either(
            EventCriteria
                .havingTags(Tag.of(FacultyTags.COURSE, id.courseId.toString()))
                .andBeingOneOfTypes(
                    CLASS_MESSAGE_TYPE_RESOLVER,
                    CourseCreated::class.java,
                    StudentSubscribedToCourse::class.java,
                ),
            EventCriteria
                .havingTags(Tag.of(FacultyTags.STUDENT, id.studentId.toString()))
                .andBeingOneOfTypes(
                    CLASS_MESSAGE_TYPE_RESOLVER,
                    StudentEnrolledInFaculty::class.java,
                    StudentSubscribedToCourse::class.java,
                )
        )
    }

    var courseId: CourseId? = null
    var capacity: Int = 0
    var studentsInCourse: Int = 0

    var studentId: StudentId? = null
    var coursesForStudent: Int = 0
    var alreadySubscribed: Boolean = false

    fun decide(cmd: SubscribeStudentToCourse): List<Any> {
        check(this.studentId != null) { "Student with given id never enrolled the faculty" }
        check(this.courseId != null) { "Course with given id does not exist" }
        check(this.capacity > this.studentsInCourse) { "Course is fully booked" }
        check(this.coursesForStudent < MAX_COURSES_PER_STUDENT) { "Student subscribed to too many courses" }
        check(!this.alreadySubscribed) { "Student already subscribed to this course" }
        return listOf(StudentSubscribedToCourse(cmd.studentId, cmd.courseId))
    }

    @EventSourcingHandler
    fun evolve(event: CourseCreated) = apply {
        courseId = event.courseId
        capacity = event.capacity
    }

    @EventSourcingHandler
    fun evolve(event: StudentEnrolledInFaculty) = apply {
        studentId = event.studentId
    }

    @EventSourcingHandler
    fun evolve(event: StudentSubscribedToCourse) =
        apply { alreadySubscribed = event.studentId == studentId && event.courseId == courseId }
            .evolveIf(event.courseId == courseId) {
                apply { studentsInCourse = studentsInCourse + 1 }
            }
            .evolveIf(event.studentId == studentId) {
                apply { coursesForStudent = coursesForStudent + 1 }
            }
}
