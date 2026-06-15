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
import org.axonframework.examples.demo.coursecatalog.catalog.testutil.TransformationTester;
import org.axonframework.messaging.core.MessageType;
import org.junit.jupiter.api.Test;

class SystemAnnouncementLegacyUpliftTest {

    @Test
    void liftsUnversionedAnnouncementToV1() {
        // Unversioned events default to "0.0.1" in AF5.
        TransformationTester.forTransformation(SystemAnnouncementLegacyUplift.build())
                            .given()
                            .messageType(CourseCatalogMessageNames.SYSTEM_ANNOUNCEMENT, "0.0.1")
                            .payloadFromResource("/transformations/systemannouncement/unversioned.json")
                            .when()
                            .then()
                            .success()
                            .outputType(new MessageType(CourseCatalogMessageNames.SYSTEM_ANNOUNCEMENT, "1.0.0"))
                            .outputPayloadFromResource("/transformations/systemannouncement/v1.json");
    }
}
