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

package org.axonframework.examples.demo.university.faculty.write.mixed;

import org.axonframework.examples.demo.university.faculty.Ids;
import org.axonframework.examples.demo.university.faculty.events.StudentEnrolledInFaculty;
import org.axonframework.examples.demo.university.faculty.write.enrollstudent.EnrollStudentInFaculty;
import org.axonframework.examples.demo.university.faculty.write.renamecourse.RenameCourse;
import org.axonframework.examples.demo.university.shared.ids.CourseId;
import org.axonframework.examples.demo.university.shared.ids.StudentId;
import org.axonframework.test.extension.AxonFrameworkExtension;
import org.axonframework.test.extension.ProvidedAxonTestFixture;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.*;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(AxonFrameworkExtension.class)
@ProvidedAxonTestFixture(RenameCourseFixtureProvider.class) // default, used by rename course
class MultiSliceExtensionTest {

    @Test
    void givenNotExistingCourse_WhenRenameCourse_ThenException(final AxonTestFixture fixture) {
        var courseId = CourseId.random();

        fixture.given()
               .noPriorActivity()
               .when()
               .command(new RenameCourse(courseId, "Event Sourcing in Practice"))
               .then()
               .noEvents()
               .exceptionSatisfies(thrown -> assertThat(thrown)
                       .hasMessageContaining("Course with given id does not exist")
               );
    }

    @Test
    @ProvidedAxonTestFixture(EnrollStudentInFacultyFixtureProvider.class)
    public void givenStudentNotEnrolledInFaculty_WhenEnrollStudentInFaculty_ThenStudentEnrolledInFaculty(
            final AxonTestFixture fixture) {
        var studentId = StudentId.random();
        var firstName = "First Name";
        var lastName = "Last Name";

        fixture.given()
               .when()
               .command(new EnrollStudentInFaculty(studentId, firstName, lastName))
               .then()
               .success()
               .events(new StudentEnrolledInFaculty(Ids.FACULTY_ID, studentId, firstName, lastName));
    }
}

