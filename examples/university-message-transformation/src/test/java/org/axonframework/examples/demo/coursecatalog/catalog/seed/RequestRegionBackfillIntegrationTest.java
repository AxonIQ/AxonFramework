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
import org.axonframework.examples.demo.coursecatalog.catalog.events.CoursePublished;
import org.axonframework.examples.demo.coursecatalog.catalog.events.StudentEnrolledInCourse;
import org.axonframework.examples.demo.coursecatalog.shared.region.RequestRegion;
import org.axonframework.examples.demo.coursecatalog.catalog.values.CapacityRange;
import org.axonframework.examples.demo.coursecatalog.catalog.write.enrollstudent.EnrollStudent;
import org.axonframework.examples.demo.coursecatalog.catalog.write.enrollstudent.EnrollStudentConfiguration;
import org.axonframework.examples.demo.coursecatalog.shared.ids.CourseId;
import org.axonframework.examples.demo.coursecatalog.shared.ids.StudentId;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * Shows the one transformation that genuinely needs the {@code ProcessingContext}:
 * {@code StudentRegisteredV2ToV3} backfills a {@code region} that historic events never had.
 * <p>
 * A student is registered with a historic, region-less event. When an {@link EnrollStudent}
 * command runs, the {@code RequestRegionCommandInterceptor} lifts the caller's region from
 * the command metadata onto the context; the same context threads into sourcing, so the
 * historic registration is read back with the region of the read. The resolved region rides
 * out on the emitted {@link StudentEnrolledInCourse}, where it is observable.
 */
class RequestRegionBackfillIntegrationTest {

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
    void historicRegistrationIsReadBackWithTheRegionTheCommandRunsIn() {
        CourseId courseId = CourseId.random();
        StudentId studentId = StudentId.of("alice");
        fixture.given()
               .event(new CoursePublished(Ids.CATALOG_ID, courseId, "ES in Practice", new CapacityRange(0, 30)))
               // historic v1 registration: no region in the stored payload
               .event(new LegacyEventSeeder.StudentRegisteredV1(Ids.CATALOG_ID, studentId, "Alice", "Hopper"))
               .when()
               .command(new EnrollStudent(courseId, studentId), Map.of(RequestRegion.METADATA_KEY, "EU"))
               .then()
               .success()
               .events(new StudentEnrolledInCourse(courseId, studentId, "EU"));
    }

    @Test
    void commandWithoutARegionFallsBackToTheUnknownRegion() {
        CourseId courseId = CourseId.random();
        StudentId studentId = StudentId.of("bob");
        fixture.given()
               .event(new CoursePublished(Ids.CATALOG_ID, courseId, "ES in Practice", new CapacityRange(0, 30)))
               .event(new LegacyEventSeeder.StudentRegisteredV1(Ids.CATALOG_ID, studentId, "Bob", "Carter"))
               .when()
               .command(new EnrollStudent(courseId, studentId))
               .then()
               .success()
               // no region attached, so the backfill falls back to the default
               .events(new StudentEnrolledInCourse(courseId, studentId));
    }
}
