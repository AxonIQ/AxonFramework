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

import org.axonframework.examples.university.TestFixtures
import org.axonframework.examples.university.faculty.events.StudentSubscribedToCourse
import org.axonframework.examples.university.faculty.write.create_course.CreateCourse
import org.axonframework.examples.university.faculty.write.create_course.registerCreateCourse
import org.axonframework.examples.university.faculty.write.enroll_student.EnrollStudent
import org.axonframework.examples.university.faculty.write.enroll_student.registerEnrollStudent
import org.axonframework.examples.university.faculty.write.subscribe_student.SubscribeStudentToCourse
import org.axonframework.examples.university.faculty.write.subscribe_student.registerSubscribeStudentToCourse
import org.axonframework.examples.university.shared.ids.CourseId
import org.axonframework.examples.university.shared.ids.StudentId
import org.axonframework.extension.kotlin.test.exception
import org.axonframework.extension.kotlin.test.whenever
import org.axonframework.test.extension.AxonFrameworkExtension
import org.axonframework.test.extension.AxonTestFixtureProvider
import org.axonframework.test.extension.ProvidedAxonTestFixture
import org.axonframework.test.fixture.AxonTestFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(AxonFrameworkExtension::class)
class SubscribeStudentToCourseTest {

    @ProvidedAxonTestFixture
    fun fixture(): AxonTestFixtureProvider = TestFixtures.universityFixture {
        registerCreateCourse()
        registerEnrollStudent()
        registerSubscribeStudentToCourse()
    }

    @Test
    fun `successfully subscribe student to course`(fixture: AxonTestFixture) {
        val courseId = CourseId.random()
        val studentId = StudentId.random()
        fixture
            .given()
            .command(CreateCourse(courseId, "Physics", 3))
            .command(EnrollStudent(studentId, "John", "Doe"))
            .whenever()
            .command(SubscribeStudentToCourse(studentId, courseId))
            .then()
            .success()
            .events(StudentSubscribedToCourse(studentId, courseId))
    }

    @Test
    fun `fail to subscribe student to course if it is full`(fixture: AxonTestFixture) {
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
