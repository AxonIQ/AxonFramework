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
import org.axonframework.examples.university.faculty.events.StudentEnrolledInFaculty
import org.axonframework.examples.university.faculty.write.enroll_student.EnrollStudent
import org.axonframework.examples.university.faculty.write.enroll_student.registerEnrollStudent
import org.axonframework.examples.university.shared.ids.StudentId
import org.axonframework.extension.kotlin.test.whenever
import org.axonframework.test.extension.AxonFrameworkExtension
import org.axonframework.test.extension.AxonTestFixtureProvider
import org.axonframework.test.extension.ProvidedAxonTestFixture
import org.axonframework.test.fixture.AxonTestFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(AxonFrameworkExtension::class)
internal class EnrollStudentTest {

    @ProvidedAxonTestFixture
    fun fixture(): AxonTestFixtureProvider = TestFixtures.universityFixture {
        registerEnrollStudent()
    }

    @Test
    fun `Given noEvents - When ErollStudent - Then StudenEnrolled`(fixture: AxonTestFixture) {
        val studentId = StudentId.random()
        fixture
            .given()
            .noPriorActivity()
            .whenever(EnrollStudent(studentId, "Kermit", "Frog"))
            .then()
            .events(StudentEnrolledInFaculty(studentId, "Kermit", "Frog"))
    }
}
