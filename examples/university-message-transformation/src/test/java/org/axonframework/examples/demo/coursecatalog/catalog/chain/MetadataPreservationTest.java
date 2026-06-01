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
import org.axonframework.messaging.core.Metadata;
import org.junit.jupiter.api.Test;

class MetadataPreservationTest {

    @Test
    void multiHopTransformationPreservesInputMetadata() {
        Metadata inputMetadata = Metadata.with("trace-id", "abc-123")
                                         .and("tenant", "axoniq")
                                         .and("origin", "legacy-store");

        ChainTester.forChain(CourseCatalogTransformations.chain())
                   .given()
                   .messageType(CourseCatalogMessageNames.COURSE_PUBLISHED, "1.0.0")
                   .payloadFromResource("/transformations/coursepublished/v1.json")
                   .metadata(inputMetadata)
                   .when()
                   .then()
                   .success()
                   .outputType(new MessageType(CourseCatalogMessageNames.COURSE_PUBLISHED, "3.0.0"))
                   .outputPreservesInputMetadata();
    }
}
