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

package org.axonframework.examples.university.faculty.write

import org.axonframework.examples.university.UniversityKotlinApplication
import org.axonframework.examples.university.faculty.events.CourseCreated
import org.axonframework.examples.university.faculty.events.StudentEnrolledInFaculty
import org.axonframework.examples.university.faculty.events.StudentSubscribedToCourse
import org.axonframework.examples.university.faculty.write.create_course.CreateCourse
import org.axonframework.examples.university.faculty.write.create_course.registerCreateCourse
import org.axonframework.examples.university.faculty.write.enroll_student.EnrollStudent
import org.axonframework.examples.university.faculty.write.enroll_student.registerEnrollStudent
import org.axonframework.examples.university.faculty.write.subscribe_student_polymorph.SubscribeStudentToCourse
import org.axonframework.examples.university.faculty.write.subscribe_student_polymorph.registerSubscribeStudentToCoursePolymorph
import org.axonframework.examples.university.shared.ids.CourseId
import org.axonframework.examples.university.shared.ids.StudentId
import org.axonframework.extension.kotlin.test.exception
import org.axonframework.extension.kotlin.test.whenever
import org.axonframework.test.fixture.AxonTestFixture
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

@Disabled("Polymorphic state implementation is not allowing free state (type) evolution.")
class SubscribeStudentToCoursePolymorphTest {

    private lateinit var fixture: AxonTestFixture

    /**
     * 	The @BeforeEach marks this method to be called before any test is executed in our test class.
     * 	Adding the code to create the AxonTestFixture here will ensure that we have a fresh fixture for
     * 	each test case, and thus we make our different tests independent.
     */
    @BeforeEach
    fun beforeEach() {
        fixture = AxonTestFixture.with(
            UniversityKotlinApplication.configurer()
                .registerCreateCourse()
                .registerEnrollStudent()
                .registerSubscribeStudentToCoursePolymorph()
        )
    }

    /**
     * Remember after each test to stop the fixture, so all resources (like Axon Server connections) are properly released.
     */
    @AfterEach
    fun afterEach() {
        fixture.stop()
    }

    @Test
    fun `successfully subscribe student to course`() {
        val courseId = CourseId.random()
        val studentId = StudentId.random()
        fixture
            .given()
            .event(CourseCreated(courseId, "Physics", 3))
            .event(StudentEnrolledInFaculty(studentId, "John", "Doe"))
            .whenever()
            .command(SubscribeStudentToCourse(studentId, courseId))
            .then()
            .success()
            .events(StudentSubscribedToCourse(studentId, courseId))
    }

    @Test
    fun `fail to subscribe student to course if it is full`() {
        val courseId = CourseId.random()
        val studentId = StudentId.random()
        val studentId2 = StudentId.random()
        fixture
            .given()
            .command(CreateCourse(courseId, "Physics", 1))
            .command(EnrollStudent(studentId, "John", "Doe"))
            .command(EnrollStudent(studentId2, "Kermit", "The Frog"))
            .command(SubscribeStudentToCourse(studentId, courseId))
            .whenever()
            .command(SubscribeStudentToCourse(studentId2, courseId))
            .then()
            .exception(IllegalStateException::class)
    }

}
