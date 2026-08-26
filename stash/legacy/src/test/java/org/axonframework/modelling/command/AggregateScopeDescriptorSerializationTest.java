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

package org.axonframework.modelling.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.axonframework.modelling.OnlyAcceptConstructorPropertiesAnnotation;
import org.axonframework.conversion.SerializedObject;
import org.axonframework.conversion.json.JacksonSerializer;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test class tests whether the {@link AggregateScopeDescriptor} is serializable as expected, by Java, XStream and
 * Jackson. It does so because an AggregateScopeDescriptor can be instantiated with a
 * {@link java.util.function.Supplier} for the {@code identifier}. We do not want to serialize a Supplier, but rather
 * the actual identifier it supplies, hence functionality is added which ensure the Supplier is called to fill the
 * {@code identifier} field just prior to the complete conversion. This test ensures this works as designed.
 */
class AggregateScopeDescriptorSerializationTest {

    private final String expectedType = "aggregateType";
    private final String expectedIdentifier = "identifier";

    private AggregateScopeDescriptor testSubject;

    @BeforeEach
    void setUp() {
        testSubject = new AggregateScopeDescriptor(expectedType, expectedIdentifier);
    }

    @Test
    void jacksonSerializationWorksAsExpected() {
        JacksonSerializer jacksonSerializer = JacksonSerializer.defaultSerializer();


        SerializedObject<String> serializedObject = jacksonSerializer.serialize(testSubject, String.class);
        AggregateScopeDescriptor result = jacksonSerializer.deserialize(serializedObject);

        assertEquals(expectedType, result.getType());
        assertEquals(expectedIdentifier, result.getIdentifier());
    }

    @Test
    void responseTypeShouldBeSerializableWithJacksonUsingConstructorProperties() {
        ObjectMapper objectMapper = OnlyAcceptConstructorPropertiesAnnotation.attachTo(new ObjectMapper());
        JacksonSerializer jacksonSerializer = JacksonSerializer.builder().objectMapper(objectMapper).build();

        SerializedObject<String> serializedObject = jacksonSerializer.serialize(testSubject, String.class);
        AggregateScopeDescriptor result = jacksonSerializer.deserialize(serializedObject);

        assertEquals(expectedType, result.getType());
        assertEquals(expectedIdentifier, result.getIdentifier());
    }
}