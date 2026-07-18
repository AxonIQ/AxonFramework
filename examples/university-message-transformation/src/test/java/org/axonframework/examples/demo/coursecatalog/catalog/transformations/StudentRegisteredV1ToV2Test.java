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

class StudentRegisteredV1ToV2Test {

    @Test
    void combinesFirstAndLastNameIntoFullName() {
        TransformationTester.forTransformation(StudentRegisteredV1ToV2.build())
                            .given()
                            .messageType(CourseCatalogMessageNames.STUDENT_REGISTERED, "1.0.0")
                            .payloadFromResource("/transformations/studentregistered/v1.json")
                            .when()
                            .then()
                            .success()
                            .outputType(new MessageType(CourseCatalogMessageNames.STUDENT_REGISTERED, "2.0.0"))
                            .outputPayloadStructurallyEquals("/transformations/studentregistered/v2.json");
    }
}
