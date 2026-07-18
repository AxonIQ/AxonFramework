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
import org.axonframework.examples.demo.coursecatalog.catalog.events.SystemHeartbeat;
import org.axonframework.examples.demo.coursecatalog.catalog.read.catalogview.CatalogViewReadModel;
import org.axonframework.examples.demo.coursecatalog.catalog.read.catalogview.CourseCatalogView;
import org.axonframework.examples.demo.coursecatalog.catalog.read.catalogview.GetCourseCatalogView;
import org.axonframework.examples.demo.coursecatalog.shared.ids.CourseId;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Console-visible proof of the drop: the catalog projection has a {@code SystemHeartbeat} handler that
 * counts pings, but the {@code SystemHeartbeatDrop} transformation suppresses them on read. So the
 * projection's {@code heartbeatsSeen} count stays at zero even though heartbeats are stored and
 * interleaved with events the projection does process.
 */
class SystemHeartbeatDropProjectionTest {

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
    void heartbeatsAreDroppedBeforeReachingTheProjectionWhileSiblingEventsStillLand() {
        fixture.given()
               .events(new SystemHeartbeat(Ids.CATALOG_ID, 1),
                       new LegacyEventSeeder.CoursePublishedV1(
                               Ids.CATALOG_ID, CourseId.of("hb-course"), "Heartbeat Neighbour", 10),
                       new SystemHeartbeat(Ids.CATALOG_ID, 2))
               .then()
               .await(r -> r.expect(cfg -> {
                   CourseCatalogView view = queryView(cfg);
                   // The course interleaved with the heartbeats is projected, so the processor has
                   // consumed the stream...
                   assertThat(view.courses())
                           .extracting(CatalogViewReadModel::courseId)
                           .contains(CourseId.of("hb-course"));
                   // ...yet the heartbeats around it were dropped before the projection's handler ran.
                   assertThat(view.heartbeatsSeen()).isZero();
               }));
    }

    private static CourseCatalogView queryView(Configuration configuration) {
        return configuration.getComponent(QueryGateway.class)
                            .query(new GetCourseCatalogView(), CourseCatalogView.class, null)
                            .orTimeout(5, TimeUnit.SECONDS)
                            .join();
    }
}
