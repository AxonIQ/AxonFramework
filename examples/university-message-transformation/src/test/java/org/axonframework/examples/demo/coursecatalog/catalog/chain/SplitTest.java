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

package org.axonframework.examples.demo.coursecatalog.catalog.chain;

import org.axonframework.examples.demo.coursecatalog.catalog.CourseCatalogMessageNames;
import org.axonframework.examples.demo.coursecatalog.catalog.testutil.ChainTester;
import org.axonframework.examples.demo.coursecatalog.catalog.testutil.JsonAssertions;
import org.axonframework.examples.demo.coursecatalog.catalog.transformations.CourseCatalogTransformations;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class SplitTest {

    @Test
    void legacyListingIsSplitAndItsCoursePublishedOutputFlowsThroughTheVersionChain() {
        // The split fans one stored CourseListedWithSeats into a CoursePublished (v1) and a
        // CourseCapacityChanged (v1). The CoursePublished output re-enters the chain and is lifted to v3,
        // while the CourseCapacityChanged has no further transformation and passes through.
        List<EventMessage> outputs =
                ChainTester.forChain(CourseCatalogTransformations.chain())
                           .given()
                           .messageType(CourseCatalogMessageNames.COURSE_LISTED_WITH_SEATS, "1.0.0")
                           .payloadFromResource("/transformations/courselistedwithseats/v1.json")
                           .when()
                           .then()
                           .outputs();

        assertThat(outputs).hasSize(2);
        assertThat(outputs.get(0).type())
                .isEqualTo(new MessageType(CourseCatalogMessageNames.COURSE_PUBLISHED, "3.0.0"));
        assertThat(outputs.get(1).type())
                .isEqualTo(new MessageType(CourseCatalogMessageNames.COURSE_CAPACITY_CHANGED, "1.0.0"));
        JsonAssertions.assertJsonEquals(
                JsonAssertions.toJsonTree(Objects.requireNonNull(outputs.get(0).payload())),
                JsonAssertions.loadJson("/transformations/coursepublished/v3.json"));
        JsonAssertions.assertJsonEquals(
                JsonAssertions.toJsonTree(Objects.requireNonNull(outputs.get(1).payload())),
                JsonAssertions.loadJson("/transformations/courselistedwithseats/capacitychanged-v1.json"));
    }
}
