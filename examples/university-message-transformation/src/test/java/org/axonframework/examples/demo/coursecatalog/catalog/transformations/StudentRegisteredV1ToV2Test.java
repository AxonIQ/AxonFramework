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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.axonframework.examples.demo.coursecatalog.catalog.CourseCatalogMessageNames;
import org.axonframework.examples.demo.coursecatalog.catalog.testing.JsonAssertions;
import org.axonframework.examples.demo.coursecatalog.catalog.testing.TransformationTester;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StudentRegisteredV1ToV2Test {

    @Test
    void combinesFirstAndLastNameIntoFullName() {
        // given
        // The TypeReference<Map<String, Object>> overload expects a Map input; feed one directly.
        Map<String, Object> v1 = new LinkedHashMap<>();
        v1.put("catalogId", "Catalog:axoniq-university");
        v1.put("studentId", "Student:alice");
        v1.put("firstName", "Alice");
        v1.put("lastName", "Hopper");

        // when
        EventMessage output = TransformationTester.forTransformation(StudentRegisteredV1ToV2.build())
                                                  .given()
                                                  .messageType(CourseCatalogMessageNames.STUDENT_REGISTERED, "1.0.0")
                                                  .payload(v1)
                                                  .whenTransformed()
                                                  .output();

        // then
        JsonNode actualPayload = new ObjectMapper().valueToTree(output.payload());
        assertThat(output.type()).isEqualTo(new MessageType(CourseCatalogMessageNames.STUDENT_REGISTERED, "2.0.0"));
        assertThat(actualPayload).isEqualTo(JsonAssertions.loadJson("/transformations/studentregistered/v2.json"));
    }
}
