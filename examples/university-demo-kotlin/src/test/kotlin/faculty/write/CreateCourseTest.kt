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
import org.axonframework.examples.university.faculty.events.CourseCreated
import org.axonframework.examples.university.faculty.write.create_course.CreateCourse
import org.axonframework.examples.university.faculty.write.create_course.registerCreateCourse
import org.axonframework.examples.university.shared.ids.CourseId
import org.axonframework.extension.kotlin.test.whenever
import org.axonframework.test.extension.AxonFrameworkExtension
import org.axonframework.test.extension.AxonTestFixtureProvider
import org.axonframework.test.extension.ProvidedAxonTestFixture
import org.axonframework.test.fixture.AxonTestFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(AxonFrameworkExtension::class)
class CreateCourseTest {

    @ProvidedAxonTestFixture
    fun fixture(): AxonTestFixtureProvider = TestFixtures.universityFixture {
        registerCreateCourse()
    }

    @Test
    fun `given noPriorEvents - when createCourse - then courseCreated`(fixture: AxonTestFixture) {
        val courseId = CourseId.random()
        val courseName = "Event Sourcing in Practice"
        val capacity = 3

        fixture.given()
            .noPriorActivity()
            .whenever(CreateCourse(courseId, courseName, capacity))
            .then()
            .success()
            .events(CourseCreated(courseId, courseName, capacity))
    }

    @Test
    fun `given CourseCreated - When CreateCourse - Then SuccessNoEvents`(fixture: AxonTestFixture) {
        val courseId = CourseId.random()
        val courseName = "Event Sourcing in Practice"
        val capacity = 3

        fixture.given()
            .event(CourseCreated(courseId, courseName, capacity))
            .whenever(CreateCourse(courseId, courseName, capacity))
            .then()
            .success()
            .noEvents()
    }
}
