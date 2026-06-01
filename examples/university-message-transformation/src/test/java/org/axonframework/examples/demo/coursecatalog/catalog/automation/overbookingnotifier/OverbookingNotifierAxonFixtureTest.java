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

package org.axonframework.examples.demo.coursecatalog.catalog.automation.overbookingnotifier;

import org.axonframework.examples.demo.coursecatalog.catalog.CourseCatalogAxonTestFixture;
import org.axonframework.examples.demo.coursecatalog.catalog.Ids;
import org.axonframework.examples.demo.coursecatalog.catalog.events.CoursePublished;
import org.axonframework.examples.demo.coursecatalog.catalog.values.CapacityRange;
import org.axonframework.examples.demo.coursecatalog.shared.ids.CourseId;
import org.axonframework.examples.demo.coursecatalog.shared.notifier.NotificationService;
import org.axonframework.examples.demo.coursecatalog.shared.notifier.RecordingNotificationService;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OverbookingNotifierAxonFixtureTest {

    private RecordingNotificationService notifier;
    private AxonTestFixture fixture;

    @BeforeEach
    void beforeEach() {
        notifier = new RecordingNotificationService();
        // The customization registers the recording NotificationService BEFORE the slice's
        // configuring runs, so the slice's registerIfNotPresent leaves our recorder in place.
        fixture = CourseCatalogAxonTestFixture.slice(
                OverbookingNotifierConfiguration::configure,
                c -> c.componentRegistry(
                        registry -> registry.registerComponent(NotificationService.class, cfg -> notifier))
        );
    }

    @AfterEach
    void afterEach() {
        fixture.stop();
    }

    @Test
    void wideCapacityRangeEmitsOverbookingNotification() {
        CourseId courseId = CourseId.random();
        fixture.given()
               .events(new CoursePublished(Ids.CATALOG_ID, courseId, "Mega Course", new CapacityRange(0, 50)))
               .then()
               .await(r -> r.expect(cfg -> assertThat(notifier.received())
                       .anyMatch(n -> n.topic().equals(OverbookingNotifier.OVERBOOKING_TOPIC)
                               && n.body().contains("Mega Course"))));
    }

    @Test
    void narrowCapacityRangeDoesNotEmitNotification() {
        CourseId courseId = CourseId.random();
        fixture.given()
               .events(new CoursePublished(Ids.CATALOG_ID, courseId, "Small Course", new CapacityRange(5, 15)))
               .then()
               .await(r -> r.expect(cfg -> assertThat(notifier.received()).isEmpty()));
    }
}
