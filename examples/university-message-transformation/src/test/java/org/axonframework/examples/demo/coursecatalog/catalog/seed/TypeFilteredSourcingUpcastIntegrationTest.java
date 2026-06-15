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

package org.axonframework.examples.demo.coursecatalog.catalog.seed;

import org.axonframework.examples.demo.coursecatalog.catalog.CourseCatalogAxonTestFixture;
import org.axonframework.examples.demo.coursecatalog.catalog.Ids;
import org.axonframework.examples.demo.coursecatalog.catalog.events.StudentEnrolledInCourse;
import org.axonframework.examples.demo.coursecatalog.catalog.events.StudentRegistered;
import org.axonframework.examples.demo.coursecatalog.shared.ids.CourseId;
import org.axonframework.examples.demo.coursecatalog.shared.ids.StudentId;
import org.axonframework.examples.demo.coursecatalog.catalog.write.enrollstudent.EnrollStudent;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the structural upcast is applied on the event-sourced entity-load path, not only on
 * the projection/streaming path. Enrolling sources the course through a type-filtered
 * {@code @EventCriteriaBuilder}; because the upcast keeps the qualified name and only bumps the
 * version, the stored historic {@code CoursePublished} v1 still matches the filter and is lifted
 * to the current v3 shape before the entity's {@code @EventSourcingHandler} sees it. The capacity
 * scenario is the decisive proof: only the upcast produces the {@code range} the capacity check
 * relies on, so a full v1 course is correctly rejected.
 */
class TypeFilteredSourcingUpcastIntegrationTest {

    private AxonTestFixture fixture;

    @BeforeEach
    void beforeEach() {
        fixture = CourseCatalogAxonTestFixture.app();
    }

    @AfterEach
    void afterEach() {
        fixture.stop();
    }

    @Test
    void historicCoursePublishedV1IsUpcastedWhenEnrollStudentSourcesTheCourse() {
        // given: a course stored in the historic v1 shape (single capacity field) and a registered student
        CourseId courseId = CourseId.of("sourcing-upcast-v1");
        StudentId studentId = StudentId.of("grace");
        fixture.given()
               .event(new LegacyEventSeeder.CoursePublishedV1(
                       Ids.CATALOG_ID, courseId, "Event Sourcing in Practice", 30))
               .event(new StudentRegistered(Ids.CATALOG_ID, studentId, "Grace Hopper"))
               // when: enrolling sources the entity through the type-filtered criteria
               .when()
               .command(new EnrollStudent(courseId, studentId))
               // then: the v1 was matched by name, upcasted to v3, and recognized as a published course
               .then()
               .success()
               .events(new StudentEnrolledInCourse(courseId, studentId));
    }

    @Test
    void v1CapacityIsCarriedThroughTheUpcastSoEnrollingIntoAFullCourseIsRejected() {
        // given: a v1 course with capacity 1 (upcasts to range [1,1]) that already has one enrolment
        CourseId courseId = CourseId.of("sourcing-upcast-full");
        StudentId studentId = StudentId.of("ada");
        fixture.given()
               .event(new LegacyEventSeeder.CoursePublishedV1(
                       Ids.CATALOG_ID, courseId, "Tiny Course", 1))
               .event(new StudentEnrolledInCourse(courseId, StudentId.of("seat-taker")))
               .event(new StudentRegistered(Ids.CATALOG_ID, studentId, "Ada Lovelace"))
               // when
               .when()
               .command(new EnrollStudent(courseId, studentId))
               // then: the upcasted range.max=1 drives the capacity check; raw v1 (range=null) would have passed
               .then()
               .exceptionSatisfies(ex -> assertThat(ex).hasMessageContaining("Course is full (capacity max=1)"));
    }
}
