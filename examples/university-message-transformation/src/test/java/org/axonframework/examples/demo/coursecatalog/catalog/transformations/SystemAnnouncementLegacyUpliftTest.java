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
import org.axonframework.examples.demo.coursecatalog.catalog.testing.JsonAssertions;
import org.axonframework.examples.demo.coursecatalog.catalog.testing.TransformationTester;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SystemAnnouncementLegacyUpliftTest {

    @Test
    void liftsUnversionedAnnouncementToV1() {
        // given / when
        // Unversioned events default to "0.0.1" in AF5.
        EventMessage output = TransformationTester.forTransformation(SystemAnnouncementLegacyUplift.build())
                                                  .given()
                                                  .messageType(CourseCatalogMessageNames.SYSTEM_ANNOUNCEMENT, "0.0.1")
                                                  .payloadFromResource("/transformations/systemannouncement/unversioned.json")
                                                  .whenTransformed()
                                                  .output();

        // then
        assertThat(output.type())
                .isEqualTo(new MessageType(CourseCatalogMessageNames.SYSTEM_ANNOUNCEMENT, "1.0.0"));
        assertThat(output.payload())
                .isEqualTo(JsonAssertions.loadJson("/transformations/systemannouncement/v1.json"));
    }
}
