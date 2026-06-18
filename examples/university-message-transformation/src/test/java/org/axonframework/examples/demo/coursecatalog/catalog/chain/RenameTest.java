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

import io.axoniq.framework.messaging.transformation.events.EventTransformation;
import io.axoniq.framework.messaging.transformation.events.EventTransformerChain;
import org.axonframework.examples.demo.coursecatalog.catalog.CourseCatalogMessageNames;
import org.axonframework.examples.demo.coursecatalog.catalog.testutil.ChainTester;
import org.axonframework.examples.demo.coursecatalog.catalog.transformations.CourseCatalogTransformations;
import org.axonframework.messaging.core.MessageType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A {@code rename(...)} changes an event's identity while carrying the payload over unchanged.
 * Unlike a {@code transform(...)} (see {@link RenameViaTransformRejectedTest}), it may change the
 * qualified name, the version, or both.
 */
class RenameTest {

    @Test
    void renamedCourseOfferedFlowsThroughTheVersionChainToTheCurrentShape() {
        // The catalog chain renames CourseOffered to CoursePublished 1.0.0, then the version
        // transformers lift it to the current 3.0.0 shape, all in one read.
        ChainTester.forChain(CourseCatalogTransformations.chain())
                   .given()
                   .messageType(CourseCatalogMessageNames.COURSE_OFFERED, "1.0.0")
                   .payloadFromResource("/transformations/coursepublished/v1.json")
                   .when()
                   .then()
                   .success()
                   .outputType(new MessageType(CourseCatalogMessageNames.COURSE_PUBLISHED, "3.0.0"))
                   .outputPayloadStructurallyEquals("/transformations/coursepublished/v3.json");
    }

    @Test
    void renameCanChangeTheVersionOnly() {
        ObjectNode payload = JsonNodeFactory.instance.objectNode().put("text", "Catalog launched");
        EventTransformerChain chain = EventTransformerChain.builder()
                .register(EventTransformation.rename(
                        new MessageType(CourseCatalogMessageNames.SYSTEM_ANNOUNCEMENT, "1.0.0"),
                        new MessageType(CourseCatalogMessageNames.SYSTEM_ANNOUNCEMENT, "2.0.0")))
                .build();

        ChainTester.forChain(chain)
                   .given()
                   .messageType(CourseCatalogMessageNames.SYSTEM_ANNOUNCEMENT, "1.0.0")
                   .payload(payload)
                   .when()
                   .then()
                   .success()
                   .outputType(new MessageType(CourseCatalogMessageNames.SYSTEM_ANNOUNCEMENT, "2.0.0"))
                   .outputPayload(payload);
    }

    @Test
    void renameCanChangeBothNameAndVersionInOneStep() {
        ObjectNode payload = JsonNodeFactory.instance.objectNode().put("name", "Legacy Foundations");
        EventTransformerChain chain = EventTransformerChain.builder()
                .register(EventTransformation.rename(
                        new MessageType(CourseCatalogMessageNames.COURSE_OFFERED, "1.0.0"),
                        new MessageType(CourseCatalogMessageNames.COURSE_PUBLISHED, "2.0.0")))
                .build();

        ChainTester.forChain(chain)
                   .given()
                   .messageType(CourseCatalogMessageNames.COURSE_OFFERED, "1.0.0")
                   .payload(payload)
                   .when()
                   .then()
                   .success()
                   .outputType(new MessageType(CourseCatalogMessageNames.COURSE_PUBLISHED, "2.0.0"))
                   .outputPayload(payload);
    }

    @Test
    void renameWithIdenticalSourceAndTargetIsRejected() {
        MessageType source = new MessageType(CourseCatalogMessageNames.COURSE_OFFERED, "1.0.0");
        MessageType target = new MessageType(CourseCatalogMessageNames.COURSE_OFFERED, "1.0.0");
        assertThatThrownBy(() -> EventTransformation.rename(source, target))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identical");
    }
}
