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

import org.axonframework.common.configuration.Configuration;
import org.axonframework.examples.demo.coursecatalog.catalog.CourseCatalogAxonTestFixture;
import org.axonframework.examples.demo.coursecatalog.catalog.Ids;
import org.axonframework.examples.demo.coursecatalog.catalog.read.catalogview.CatalogViewReadModel;
import org.axonframework.examples.demo.coursecatalog.catalog.read.catalogview.CourseCatalogView;
import org.axonframework.examples.demo.coursecatalog.catalog.read.catalogview.GetCourseCatalogView;
import org.axonframework.examples.demo.coursecatalog.catalog.values.CapacityRange;
import org.axonframework.examples.demo.coursecatalog.shared.ids.CourseId;
import org.axonframework.examples.demo.coursecatalog.shared.ids.StudentId;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test: historic-shape events written into the store flow through the
 * transformation chain on the read path and arrive at the catalog projection in
 * their current shape. Exercises every transformation registered in
 * {@link org.axonframework.examples.demo.coursecatalog.catalog.transformations.CourseCatalogTransformations}.
 */
class HistoricEventsUpcastingIntegrationTest {

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
    void coursePublishedV1ReadsAsCurrentRangeBothEndsEqualToOriginalCapacity() {
        CourseId courseId = CourseId.of("integration-v1");
        fixture.given()
               .events(new LegacyEventSeeder.CoursePublishedV1(
                       Ids.CATALOG_ID, courseId, "V1 Course", 20))
               .then()
               .await(r -> r.expect(cfg -> assertViewContainsClosedCourseWithoutEnrollments(
                       cfg, courseId, "V1 Course", new CapacityRange(20, 20))));
    }

    @Test
    void coursePublishedV2ReadsAsCurrentRange() {
        CourseId courseId = CourseId.of("integration-v2");
        fixture.given()
               .events(new LegacyEventSeeder.CoursePublishedV2(
                       Ids.CATALOG_ID, courseId, "V2 Course", 5, 25))
               .then()
               .await(r -> r.expect(cfg -> assertViewContainsClosedCourseWithoutEnrollments(
                       cfg, courseId, "V2 Course", new CapacityRange(5, 25))));
    }

    @Test
    void studentRegisteredV1ContributesToCurrentCount() {
        fixture.given()
               .events(new LegacyEventSeeder.StudentRegisteredV1(
                               Ids.CATALOG_ID, StudentId.of("zoe"), "Zoe", "Quinn"),
                       new LegacyEventSeeder.StudentRegisteredV1(
                               Ids.CATALOG_ID, StudentId.of("yann"), "Yann", "Le Cun"))
               .then()
               .await(r -> r.expect(cfg -> assertThat(queryView(cfg).registeredStudents()).isEqualTo(2)));
    }

    @Test
    void unversionedSystemAnnouncementIsLiftedAndAppears() {
        fixture.given()
               .events(new LegacyEventSeeder.SystemAnnouncementUnversioned(
                       Ids.CATALOG_ID, "Catalog opened in 2023"))
               .then()
               .await(r -> r.expect(cfg -> assertThat(queryView(cfg).announcements())
                       .containsExactly("Catalog opened in 2023")));
    }

    @Test
    void seedingTheFullHistoryProducesAllExpectedUpcastedRows() {
        // The seeder writes the full v1/v2/v3 history in one shot.
        // Verifies all five historic courses, every legacy StudentRegistered,
        // and the unversioned announcement arrives at the projection in current shape.
        fixture.given()
               .command(new SeedCatalog(Ids.CATALOG_ID))
               .then()
               .await(r -> r.expect(cfg -> {
                   CourseCatalogView view = queryView(cfg);
                   assertThat(view.courses()).hasSize(5);
                   assertThat(view.registeredStudents()).isEqualTo(4);
                   assertThat(view.announcements()).containsExactly("Catalog launched in 2023");
                   // V1 single-capacity course: range collapses to [n,n]
                   assertThat(view.courses()).contains(new CatalogViewReadModel(
                           CourseId.of("event-sourcing-101"), "Event Sourcing in Practice",
                           new CapacityRange(30, 30), 0, false));
                   // V2 min/max course: range preserves both bounds
                   assertThat(view.courses()).contains(new CatalogViewReadModel(
                           CourseId.of("hexagonal-architecture"), "Hexagonal Architecture",
                           new CapacityRange(10, 30), 0, false));
               }));
    }

    @Test
    void seedingIsIdempotentSoCallingSeedTwiceLeavesViewUnchanged() {
        // The first call writes the history; the second call sees the marker and emits nothing.
        fixture.given()
               .command(new SeedCatalog(Ids.CATALOG_ID))
               .when()
               .command(new SeedCatalog(Ids.CATALOG_ID))
               .then()
               .await(r -> r.expect(cfg -> {
                   CourseCatalogView view = queryView(cfg);
                   assertThat(view.courses()).hasSize(5);
                   assertThat(view.registeredStudents()).isEqualTo(4);
                   assertThat(view.announcements()).hasSize(1);
               }));
    }

    private static void assertViewContainsClosedCourseWithoutEnrollments(
            Configuration configuration,
            CourseId courseId,
            String name,
            CapacityRange range
    ) {
        CourseCatalogView view = queryView(configuration);
        CatalogViewReadModel expected = new CatalogViewReadModel(courseId, name, range, 0, false);
        assertThat(view.courses())
                .as("catalog view should contain course %s after upcasting", courseId)
                .contains(expected);
    }

    private static CourseCatalogView queryView(Configuration configuration) {
        return configuration.getComponent(QueryGateway.class)
                            .query(new GetCourseCatalogView(), CourseCatalogView.class, null)
                            .orTimeout(5, TimeUnit.SECONDS)
                            .join();
    }
}
