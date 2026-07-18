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

package org.axonframework.examples.demo.coursecatalog.catalog.write.updatecoursecapacity;

import org.axonframework.examples.demo.coursecatalog.catalog.CourseCatalogAxonTestFixture;
import org.axonframework.examples.demo.coursecatalog.catalog.Ids;
import org.axonframework.examples.demo.coursecatalog.catalog.events.CourseCapacityChanged;
import org.axonframework.examples.demo.coursecatalog.catalog.events.CoursePublished;
import org.axonframework.examples.demo.coursecatalog.catalog.events.StudentEnrolledInCourse;
import org.axonframework.examples.demo.coursecatalog.catalog.values.CapacityRange;
import org.axonframework.examples.demo.coursecatalog.shared.ids.CourseId;
import org.axonframework.examples.demo.coursecatalog.shared.ids.StudentId;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UpdateCourseCapacityAxonFixtureTest {

    private AxonTestFixture fixture;

    @BeforeEach
    void beforeEach() {
        fixture = CourseCatalogAxonTestFixture.slice(UpdateCourseCapacityConfiguration::configure);
    }

    @AfterEach
    void afterEach() {
        fixture.stop();
    }

    @Test
    void updatesCapacityOnPublishedCourse() {
        CourseId courseId = CourseId.random();
        fixture.given()
               .event(new CoursePublished(Ids.CATALOG_ID, courseId, "Existing Course", new CapacityRange(10, 30)))
               .when()
               .command(new UpdateCourseCapacity(courseId, new CapacityRange(15, 35)))
               .then()
               .success()
               .events(new CourseCapacityChanged(courseId, new CapacityRange(15, 35)));
    }

    @Test
    void updatingToTheSameRangeEmitsNoEvent() {
        CourseId courseId = CourseId.random();
        fixture.given()
               .event(new CoursePublished(Ids.CATALOG_ID, courseId, "Existing Course", new CapacityRange(10, 30)))
               .when()
               .command(new UpdateCourseCapacity(courseId, new CapacityRange(10, 30)))
               .then()
               .success()
               .noEvents();
    }

    @Test
    void rejectsWhenCourseNotPublished() {
        CourseId courseId = CourseId.random();
        fixture.given()
               .noPriorActivity()
               .when()
               .command(new UpdateCourseCapacity(courseId, new CapacityRange(15, 35)))
               .then()
               .exceptionSatisfies(ex -> assertThat(ex).hasMessageContaining("Course with given id does not exist"));
    }

    @Test
    void rejectsWhenNewMaxIsBelowCurrentEnrolments() {
        CourseId courseId = CourseId.random();
        fixture.given()
               .event(new CoursePublished(Ids.CATALOG_ID, courseId, "Existing Course", new CapacityRange(0, 40)))
               .event(new StudentEnrolledInCourse(courseId, StudentId.random()))
               .event(new StudentEnrolledInCourse(courseId, StudentId.random()))
               .event(new StudentEnrolledInCourse(courseId, StudentId.random()))
               .event(new StudentEnrolledInCourse(courseId, StudentId.random()))
               .when()
               .command(new UpdateCourseCapacity(courseId, new CapacityRange(0, 3)))
               .then()
               .exceptionSatisfies(ex -> assertThat(ex)
                       .hasMessageContaining("is below current enrolment count"));
    }
}
