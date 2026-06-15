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
import org.axonframework.examples.demo.coursecatalog.catalog.transformations.CourseCatalogTransformations;
import org.axonframework.messaging.core.MessageType;
import org.junit.jupiter.api.Test;

class MultiHopChainTesterTest {

    private static final MessageType V3_TYPE = new MessageType(CourseCatalogMessageNames.COURSE_PUBLISHED, "3.0.0");
    private static final String V3_FIXTURE = "/transformations/coursepublished/v3.json";

    @Test
    void v1CoursePublishedReachesHandlerAsV3() {
        // The chain has both v1 to v2 and v2 to v3 registered; fixed-point iteration applies them in order.
        // The final hop returns the current CoursePublished event, so compare it structurally to the v3 wire shape.
        ChainTester.forChain(CourseCatalogTransformations.chain())
                   .given()
                   .messageType(CourseCatalogMessageNames.COURSE_PUBLISHED, "1.0.0")
                   .payloadFromResource("/transformations/coursepublished/v1.json")
                   .when()
                   .then()
                   .success()
                   .outputType(V3_TYPE)
                   .outputPayloadStructurallyEquals(V3_FIXTURE);
    }

    @Test
    void v2CoursePublishedReachesHandlerAsV3() {
        ChainTester.forChain(CourseCatalogTransformations.chain())
                   .given()
                   .messageType(CourseCatalogMessageNames.COURSE_PUBLISHED, "2.0.0")
                   .payloadFromResource("/transformations/coursepublished/v2.json")
                   .when()
                   .then()
                   .success()
                   .outputType(V3_TYPE)
                   .outputPayloadStructurallyEquals(V3_FIXTURE);
    }

    @Test
    void v3CoursePublishedPassesThroughUnchanged() {
        ChainTester.forChain(CourseCatalogTransformations.chain())
                   .given()
                   .messageType(CourseCatalogMessageNames.COURSE_PUBLISHED, "3.0.0")
                   .payloadFromResource(V3_FIXTURE)
                   .when()
                   .then()
                   .success()
                   .outputType(V3_TYPE)
                   .outputPayloadFromResource(V3_FIXTURE)
                   .outputPreservesInputIdentifier();
    }
}
