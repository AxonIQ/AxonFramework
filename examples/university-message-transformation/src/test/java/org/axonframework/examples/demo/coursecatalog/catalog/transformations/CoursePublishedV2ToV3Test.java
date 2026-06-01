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
import org.axonframework.examples.demo.coursecatalog.catalog.testutil.JsonAssertions;
import org.axonframework.examples.demo.coursecatalog.catalog.testutil.TransformationTester;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CoursePublishedV2ToV3Test {

    @Test
    void liftsV2PayloadToV3() {
        // given / when
        EventMessage output = TransformationTester.forTransformation(CoursePublishedV2ToV3.build())
                                                  .given()
                                                  .messageType(CourseCatalogMessageNames.COURSE_PUBLISHED, "2.0.0")
                                                  .payloadFromResource("/transformations/coursepublished/v2.json")
                                                  .whenTransformed()
                                                  .output();

        // then
        assertThat(output.type()).isEqualTo(new MessageType(CourseCatalogMessageNames.COURSE_PUBLISHED, "3.0.0"));
        assertThat(output.payload()).isEqualTo(JsonAssertions.loadJson("/transformations/coursepublished/v3.json"));
    }
}
