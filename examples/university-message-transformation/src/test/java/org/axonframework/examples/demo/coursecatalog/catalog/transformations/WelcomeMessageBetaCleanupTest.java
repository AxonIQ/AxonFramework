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

class WelcomeMessageBetaCleanupTest {

    private static final MessageType EXPECTED_TYPE =
            new MessageType(CourseCatalogMessageNames.WELCOME_MESSAGE_SENT, "1.0.0");

    @Test
    void liftsBetaV0_5ToV1() {
        runCleanup("0.5", "/transformations/welcomemessagesent/v0_5.json",
                   "/transformations/welcomemessagesent/v1_alice.json");
    }

    @Test
    void liftsBetaV0_7ToV1() {
        runCleanup("0.7", "/transformations/welcomemessagesent/v0_7.json",
                   "/transformations/welcomemessagesent/v1_bob.json");
    }

    @Test
    void liftsBetaV0_9ToV1() {
        runCleanup("0.9", "/transformations/welcomemessagesent/v0_9.json",
                   "/transformations/welcomemessagesent/v1_carol.json");
    }

    private static void runCleanup(String betaVersion, String inputResource, String expectedResource) {
        TransformationTester.forTransformation(WelcomeMessageBetaCleanup.build())
                            .given()
                            .messageType(CourseCatalogMessageNames.WELCOME_MESSAGE_SENT, betaVersion)
                            .payloadFromResource(inputResource)
                            .when()
                            .then()
                            .success()
                            .outputType(EXPECTED_TYPE)
                            .outputPayloadFromResource(expectedResource);
    }
}
