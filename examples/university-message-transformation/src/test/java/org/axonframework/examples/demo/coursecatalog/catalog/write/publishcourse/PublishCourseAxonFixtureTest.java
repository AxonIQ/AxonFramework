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

package org.axonframework.examples.demo.coursecatalog.catalog.write.publishcourse;

import org.axonframework.examples.demo.coursecatalog.catalog.CourseCatalogAxonTestFixture;
import org.axonframework.examples.demo.coursecatalog.catalog.Ids;
import org.axonframework.examples.demo.coursecatalog.catalog.events.CoursePublished;
import org.axonframework.examples.demo.coursecatalog.catalog.values.CapacityRange;
import org.axonframework.examples.demo.coursecatalog.shared.ids.CourseId;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PublishCourseAxonFixtureTest {

    private AxonTestFixture fixture;

    @BeforeEach
    void beforeEach() {
        fixture = CourseCatalogAxonTestFixture.slice(PublishCourseConfiguration::configure);
    }

    @AfterEach
    void afterEach() {
        fixture.stop();
    }

    @Test
    void publishesNewCourse() {
        CourseId courseId = CourseId.random();
        fixture.given()
               .noPriorActivity()
               .when()
               .command(new PublishCourse(courseId, "Event Sourcing in Practice", new CapacityRange(20, 40)))
               .then()
               .success()
               .events(new CoursePublished(Ids.CATALOG_ID, courseId, "Event Sourcing in Practice",
                                           new CapacityRange(20, 40)));
    }

    @Test
    void republishingTheSameCourseIsANoOp() {
        CourseId courseId = CourseId.random();
        fixture.given()
               .event(new CoursePublished(Ids.CATALOG_ID, courseId, "Existing Course", new CapacityRange(10, 30)))
               .when()
               .command(new PublishCourse(courseId, "Existing Course", new CapacityRange(10, 30)))
               .then()
               .success()
               .noEvents();
    }
}
