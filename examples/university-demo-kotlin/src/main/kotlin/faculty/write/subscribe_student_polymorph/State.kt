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

package org.axonframework.examples.university.faculty.write.subscribe_student_polymorph

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

@EventSourcedEntity(
    concreteTypes = [
        State.InitialState::class,
        State.CourseCreatedState::class,
        State.StudentEnrolledState::class,
        State.SubscriptionState::class
    ]
)
internal sealed interface State {
    companion object {

        const val MAX_COURSES_PER_STUDENT = 3
        val CLASS_MESSAGE_TYPE_RESOLVER = ClassBasedMessageTypeResolver()

        @JvmStatic
        @EventCriteriaBuilder
        fun resolveCriteria(id: SubscriptionId): EventCriteria = EventCriteria.either(
            EventCriteria
                .havingTags(id.courseIdTag())
                .andBeingOneOfTypes(
                    CLASS_MESSAGE_TYPE_RESOLVER,
                    CourseCreated::class.java,
                    StudentSubscribedToCourse::class.java,
                ),
            EventCriteria
                .havingTags(id.studentTag())
                .andBeingOneOfTypes(
                    CLASS_MESSAGE_TYPE_RESOLVER,
                    StudentEnrolledInFaculty::class.java,
                    StudentSubscribedToCourse::class.java,
                )
        )

        @JvmStatic
        @EntityCreator
        fun initialState(): State {
            return InitialState
        }
    }

    /**
     * By default, we reject the command. It has to be implemented in the subclass.
     */
    fun decide(cmd: SubscribeStudentToCourse): List<Any> {
        throw UnsupportedOperationException("Command dispatched to incorrect state. Current type is '${this::class.simpleName}'.")
    }

    object InitialState : State {

        @EventSourcingHandler
        fun evolve(event: CourseCreated): State = CourseCreatedState(
            courseId = event.courseId,
            capacity = event.capacity
        )

        @EventSourcingHandler
        fun evolve(event: StudentEnrolledInFaculty): State = StudentEnrolledState(
            studentId = event.studentId
        )
    }

    /**
     * Course but no student.
     */
    data class CourseCreatedState(
        val courseId: CourseId,
        val capacity: Int,
        val subscribedStudents: Int = 0
    ) : State {

        fun checkCourseHasMoreSpace() {
            check(capacity > subscribedStudents) { "Course is fully booked" }
        }

        fun evolveStudentSubscribed() = copy(subscribedStudents = subscribedStudents + 1)

        @EventSourcingHandler
        fun evolve(event: StudentEnrolledInFaculty): State = SubscriptionState(
            courseState = this,
            studentState = StudentEnrolledState(studentId = event.studentId)
        )
    }

    /**
     * Student but no course.
     */
    data class StudentEnrolledState(
        val studentId: StudentId,
        val coursesForStudent: Int = 0
    ) : State {

        fun checkStudentCanSubscribeMoreCourses() {
            check(this.coursesForStudent < MAX_COURSES_PER_STUDENT) { "Student subscribed to too many courses" }
        }

        fun evolveSubscribedToCourse(): StudentEnrolledState =
            copy(coursesForStudent = coursesForStudent + 1)

        @EventSourcingHandler
        fun evolve(event: CourseCreated): State = SubscriptionState(
            courseState = CourseCreatedState(
                courseId = event.courseId,
                capacity = event.capacity
            ),
            studentState = this
        )

    }

    /**
     * Subscription (student and course).
     */
    data class SubscriptionState(
        val courseState: CourseCreatedState,
        val studentState: StudentEnrolledState,
        val alreadySubscribed: Boolean = false
    ) : State {

        override fun decide(cmd: SubscribeStudentToCourse): List<Any> {
            courseState.checkCourseHasMoreSpace()
            studentState.checkStudentCanSubscribeMoreCourses()
            checkAlreadySubscribed()
            return listOf(StudentSubscribedToCourse(cmd.studentId, cmd.courseId))
        }

        fun checkAlreadySubscribed() {
            check(!alreadySubscribed) { "Student already subscribed to this course" }
        }

        @EventSourcingHandler
        fun evolve(event: StudentSubscribedToCourse): State =
            evolveIf(event.courseId == courseState.courseId) {
                copy(courseState = courseState.evolveStudentSubscribed())
            }
                .evolveIf(event.studentId == studentState.studentId) {
                    copy(studentState = studentState.evolveSubscribedToCourse())
                }
                .evolveIf(event.studentId == studentState.studentId && event.courseId == courseState.courseId) {
                    copy(alreadySubscribed = true)
                }

    }
}
