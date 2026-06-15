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
import org.junit.jupiter.api.Test;

class SystemHeartbeatDropTest {

    @Test
    void dropsSystemHeartbeatSoNoEventIsProduced() {
        // A drop is the 1:0 case: the matched event yields no output, so no handler ever sees it.
        TransformationTester.forTransformation(SystemHeartbeatDrop.build())
                            .given()
                            .messageType(CourseCatalogMessageNames.SYSTEM_HEARTBEAT, "1.0.0")
                            .payloadFromResource("/transformations/systemheartbeat/v1.json")
                            .when()
                            .then()
                            .noOutput();
    }
}
