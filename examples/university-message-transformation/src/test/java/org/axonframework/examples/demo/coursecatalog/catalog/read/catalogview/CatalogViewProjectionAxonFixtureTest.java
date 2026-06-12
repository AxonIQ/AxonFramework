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

package org.axonframework.examples.demo.coursecatalog.catalog.read.catalogview;

import org.axonframework.common.configuration.Configuration;
import org.axonframework.examples.demo.coursecatalog.catalog.CourseCatalogAxonTestFixture;
import org.axonframework.examples.demo.coursecatalog.catalog.Ids;
import org.axonframework.examples.demo.coursecatalog.catalog.events.CourseCapacityChanged;
import org.axonframework.examples.demo.coursecatalog.catalog.events.CoursePublished;
import org.axonframework.examples.demo.coursecatalog.catalog.events.RegistrationClosed;
import org.axonframework.examples.demo.coursecatalog.catalog.events.StudentEnrolledInCourse;
import org.axonframework.examples.demo.coursecatalog.catalog.events.StudentRegistered;
import org.axonframework.examples.demo.coursecatalog.catalog.events.SystemAnnouncement;
import org.axonframework.examples.demo.coursecatalog.catalog.events.WelcomeMessageSent;
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

class CatalogViewProjectionAxonFixtureTest {

    private static final String COURSE_NAME = "ES in Practice";

    private AxonTestFixture fixture;

    @BeforeEach
    void beforeEach() {
        fixture = CourseCatalogAxonTestFixture.slice(CatalogViewConfiguration::configure);
    }

    @AfterEach
    void afterEach() {
        fixture.stop();
    }

    @Test
    void coursePublishedShowsUpInView() {
        CourseId courseId = CourseId.random();
        fixture.given()
               .events(new CoursePublished(Ids.CATALOG_ID, courseId, COURSE_NAME, new CapacityRange(0, 30)))
               .then()
               .await(r -> r.expect(cfg -> assertViewContainsCourse(
                       cfg, courseId, new CapacityRange(0, 30), 0, false)));
    }

    @Test
    void capacityChangeIsReflected() {
        CourseId courseId = CourseId.random();
        fixture.given()
               .events(new CoursePublished(Ids.CATALOG_ID, courseId, COURSE_NAME, new CapacityRange(0, 30)),
                       new CourseCapacityChanged(courseId, new CapacityRange(5, 40)))
               .then()
               .await(r -> r.expect(cfg -> assertViewContainsCourse(
                       cfg, courseId, new CapacityRange(5, 40), 0, false)));
    }

    @Test
    void studentEnrolmentsAccumulate() {
        CourseId courseId = CourseId.random();
        fixture.given()
               .events(new CoursePublished(Ids.CATALOG_ID, courseId, COURSE_NAME, new CapacityRange(0, 30)),
                       new StudentEnrolledInCourse(courseId, StudentId.random()),
                       new StudentEnrolledInCourse(courseId, StudentId.random()))
               .then()
               .await(r -> r.expect(cfg -> assertViewContainsCourse(
                       cfg, courseId, new CapacityRange(0, 30), 2, false)));
    }

    @Test
    void registrationClosedIsReflected() {
        CourseId courseId = CourseId.random();
        fixture.given()
               .events(new CoursePublished(Ids.CATALOG_ID, courseId, COURSE_NAME, new CapacityRange(0, 30)),
                       new RegistrationClosed(courseId))
               .then()
               .await(r -> r.expect(cfg -> assertViewContainsCourse(
                       cfg, courseId, new CapacityRange(0, 30), 0, true)));
    }

    @Test
    void announcementsAreCollected() {
        fixture.given()
               .events(new SystemAnnouncement(Ids.CATALOG_ID, "Welcome to the catalog"),
                       new SystemAnnouncement(Ids.CATALOG_ID, "Spring semester begins"))
               .then()
               .await(r -> r.expect(cfg -> {
                   CourseCatalogView view = queryView(cfg);
                   assertThat(view.announcements())
                           .containsExactly("Welcome to the catalog", "Spring semester begins");
               }));
    }

    @Test
    void registeredStudentsCountAccumulates() {
        fixture.given()
               .events(new StudentRegistered(Ids.CATALOG_ID, StudentId.random(), "Alice Hopper"),
                       new StudentRegistered(Ids.CATALOG_ID, StudentId.random(), "Bob Carter"),
                       new StudentRegistered(Ids.CATALOG_ID, StudentId.random(), "Carol Davis"))
               .then()
               .await(r -> r.expect(cfg -> assertThat(queryView(cfg).registeredStudents()).isEqualTo(3)));
    }

    @Test
    void welcomeMessageShowsUpInView() {
        StudentId studentId = StudentId.random();
        fixture.given()
               .events(new WelcomeMessageSent(studentId, "Welcome to the catalog"))
               .then()
               .await(r -> r.expect(cfg -> assertThat(queryView(cfg).welcomeMessages())
                       .containsExactly(new WelcomeMessageView(studentId, "Welcome to the catalog"))));
    }

    private static void assertViewContainsCourse(
            Configuration configuration,
            CourseId courseId,
            CapacityRange range,
            int enrolments,
            boolean registrationClosed
    ) {
        CourseCatalogView view = queryView(configuration);
        CatalogViewReadModel expected =
                new CatalogViewReadModel(courseId, COURSE_NAME, range, enrolments, registrationClosed);
        assertThat(view.courses())
                .as("catalog view should contain course %s in expected shape", courseId)
                .contains(expected);
    }

    private static CourseCatalogView queryView(Configuration configuration) {
        return configuration.getComponent(QueryGateway.class)
                            .query(new GetCourseCatalogView(), CourseCatalogView.class, null)
                            .orTimeout(5, TimeUnit.SECONDS)
                            .join();
    }
}
