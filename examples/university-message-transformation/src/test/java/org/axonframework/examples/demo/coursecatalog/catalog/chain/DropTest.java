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

import io.axoniq.framework.messaging.transformation.events.EventTransformerChain;
import org.axonframework.examples.demo.coursecatalog.catalog.CourseCatalogMessageNames;
import org.axonframework.examples.demo.coursecatalog.catalog.testutil.ChainTester;
import org.axonframework.examples.demo.coursecatalog.catalog.transformations.CourseCatalogTransformations;
import org.axonframework.messaging.core.MessageType;
import org.junit.jupiter.api.Test;

/**
 * A {@code drop(...)} removes a matched event from the read stream entirely (the 1:0 case), while
 * the rest of the chain keeps lifting the events it handles. These tests run the full assembled
 * catalog chain to show both sides of that behavior.
 */
class DropTest {

    @Test
    void systemHeartbeatIsDroppedByTheAssembledChain() {
        // The catalog chain registers a drop for the legacy SystemHeartbeat, so a stored heartbeat
        // produces no output and reaches no handler.
        ChainTester.forChain(CourseCatalogTransformations.chain())
                   .given()
                   .messageType(CourseCatalogMessageNames.SYSTEM_HEARTBEAT, "1.0.0")
                   .payloadFromResource("/transformations/systemheartbeat/v1.json")
                   .when()
                   .then()
                   .noOutput();
    }

    @Test
    void aHandledEventStillTransformsThroughAChainThatRegistersADrop() {
        // Contrast: with the drop registered in the chain, a stored CoursePublished v1 still reaches
        // its current 3.0.0 shape; the drop only suppresses its own SystemHeartbeat.
        ChainTester.forChain(CourseCatalogTransformations.chain())
                   .given()
                   .messageType(CourseCatalogMessageNames.COURSE_PUBLISHED, "1.0.0")
                   .payloadFromResource("/transformations/coursepublished/v1.json")
                   .when()
                   .then()
                   .success()
                   .outputType(new MessageType(CourseCatalogMessageNames.COURSE_PUBLISHED, "3.0.0"))
                   .outputPayloadStructurallyEquals("/transformations/coursepublished/v3.json");
    }

    @Test
    void withoutTheDropAHeartbeatWouldReachHandlers() {
        // Negative control: a chain without the drop lets the heartbeat through unchanged, so it would
        // reach the projection's handler. Registering the drop is what keeps it out.
        ChainTester.forChain(EventTransformerChain.builder().build())
                   .given()
                   .messageType(CourseCatalogMessageNames.SYSTEM_HEARTBEAT, "1.0.0")
                   .payloadFromResource("/transformations/systemheartbeat/v1.json")
                   .when()
                   .then()
                   .success()
                   .outputType(new MessageType(CourseCatalogMessageNames.SYSTEM_HEARTBEAT, "1.0.0"));
    }
}
