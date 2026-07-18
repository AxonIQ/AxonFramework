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

package org.axonframework.examples.demo.coursecatalog.catalog.transformations;

import org.axonframework.examples.demo.coursecatalog.catalog.CourseCatalogMessageNames;
import org.axonframework.examples.demo.coursecatalog.catalog.events.CoursePublished;
import org.axonframework.examples.demo.coursecatalog.catalog.testutil.TransformationTester;
import org.axonframework.examples.demo.coursecatalog.catalog.values.CapacityRange;
import org.axonframework.examples.demo.coursecatalog.shared.ids.CatalogId;
import org.axonframework.examples.demo.coursecatalog.shared.ids.CourseId;
import org.axonframework.messaging.core.MessageType;
import org.junit.jupiter.api.Test;

class CoursePublishedV2ToV3Test {

    @Test
    void liftsV2PayloadToV3() {
        // The stored v2 JSON is deserialized into the typed V2Schema record before the mapper runs,
        // and the mapper returns the current CoursePublished event - asserted here by value equality.
        TransformationTester.forTransformation(CoursePublishedV2ToV3.build())
                            .given()
                            .messageType(CourseCatalogMessageNames.COURSE_PUBLISHED, "2.0.0")
                            .payloadFromResource("/transformations/coursepublished/v2.json")
                            .when()
                            .then()
                            .success()
                            .outputType(new MessageType(CourseCatalogMessageNames.COURSE_PUBLISHED, "3.0.0"))
                            .outputPayload(new CoursePublished(
                                    CatalogId.of("Catalog:axoniq-university"),
                                    CourseId.of("Course:event-sourcing-101"),
                                    "Event Sourcing in Practice",
                                    new CapacityRange(30, 30)));
    }
}
