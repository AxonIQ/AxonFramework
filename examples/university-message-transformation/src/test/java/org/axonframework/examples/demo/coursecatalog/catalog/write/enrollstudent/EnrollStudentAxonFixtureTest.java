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

package org.axonframework.examples.demo.coursecatalog.catalog.write.enrollstudent;

import org.axonframework.examples.demo.coursecatalog.catalog.CourseCatalogAxonTestFixture;
import org.axonframework.examples.demo.coursecatalog.catalog.Ids;
import org.axonframework.examples.demo.coursecatalog.catalog.events.CoursePublished;
import org.axonframework.examples.demo.coursecatalog.catalog.events.RegistrationClosed;
import org.axonframework.examples.demo.coursecatalog.catalog.events.StudentEnrolledInCourse;
import org.axonframework.examples.demo.coursecatalog.catalog.events.StudentRegistered;
import org.axonframework.examples.demo.coursecatalog.catalog.values.CapacityRange;
import org.axonframework.examples.demo.coursecatalog.shared.ids.CourseId;
import org.axonframework.examples.demo.coursecatalog.shared.ids.StudentId;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EnrollStudentAxonFixtureTest {

    private AxonTestFixture fixture;

    @BeforeEach
    void beforeEach() {
        fixture = CourseCatalogAxonTestFixture.slice(EnrollStudentConfiguration::configure);
    }

    @AfterEach
    void afterEach() {
        fixture.stop();
    }

    @Test
    void enrolsStudentWhenCourseIsOpenAndStudentRegistered() {
        CourseId courseId = CourseId.random();
        StudentId studentId = StudentId.random();
        fixture.given()
               .event(new CoursePublished(Ids.CATALOG_ID, courseId, "ES in Practice", new CapacityRange(0, 30)))
               .event(new StudentRegistered(Ids.CATALOG_ID, studentId, "Alice Hopper"))
               .when()
               .command(new EnrollStudent(courseId, studentId))
               .then()
               .success()
               .events(new StudentEnrolledInCourse(courseId, studentId));
    }

    @Test
    void rejectsWhenCourseNotPublished() {
        StudentId studentId = StudentId.random();
        fixture.given()
               .event(new StudentRegistered(Ids.CATALOG_ID, studentId, "Alice Hopper"))
               .when()
               .command(new EnrollStudent(CourseId.random(), studentId))
               .then()
               .exceptionSatisfies(ex -> assertThat(ex).hasMessageContaining("Course is not published"));
    }

    @Test
    void rejectsWhenStudentNotRegistered() {
        CourseId courseId = CourseId.random();
        fixture.given()
               .event(new CoursePublished(Ids.CATALOG_ID, courseId, "ES in Practice", new CapacityRange(0, 30)))
               .when()
               .command(new EnrollStudent(courseId, StudentId.random()))
               .then()
               .exceptionSatisfies(ex -> assertThat(ex)
                       .hasMessageContaining("Student is not registered with the catalog"));
    }

    @Test
    void rejectsWhenRegistrationClosed() {
        CourseId courseId = CourseId.random();
        StudentId studentId = StudentId.random();
        fixture.given()
               .event(new CoursePublished(Ids.CATALOG_ID, courseId, "ES in Practice", new CapacityRange(0, 30)))
               .event(new RegistrationClosed(courseId))
               .event(new StudentRegistered(Ids.CATALOG_ID, studentId, "Alice Hopper"))
               .when()
               .command(new EnrollStudent(courseId, studentId))
               .then()
               .exceptionSatisfies(ex -> assertThat(ex).hasMessageContaining("Registration is closed"));
    }

    @Test
    void rejectsWhenCourseFull() {
        CourseId courseId = CourseId.random();
        StudentId studentId = StudentId.random();
        fixture.given()
               .event(new CoursePublished(Ids.CATALOG_ID, courseId, "Tiny Course", new CapacityRange(0, 2)))
               .event(new StudentEnrolledInCourse(courseId, StudentId.random()))
               .event(new StudentEnrolledInCourse(courseId, StudentId.random()))
               .event(new StudentRegistered(Ids.CATALOG_ID, studentId, "Alice Hopper"))
               .when()
               .command(new EnrollStudent(courseId, studentId))
               .then()
               .exceptionSatisfies(ex -> assertThat(ex).hasMessageContaining("Course is full"));
    }

    @Test
    void enrollingAnAlreadyEnrolledStudentIsANoOp() {
        CourseId courseId = CourseId.random();
        StudentId studentId = StudentId.random();
        fixture.given()
               .event(new CoursePublished(Ids.CATALOG_ID, courseId, "ES in Practice", new CapacityRange(0, 30)))
               .event(new StudentRegistered(Ids.CATALOG_ID, studentId, "Alice Hopper"))
               .event(new StudentEnrolledInCourse(courseId, studentId))
               .when()
               .command(new EnrollStudent(courseId, studentId))
               .then()
               .success()
               .noEvents();
    }
}
