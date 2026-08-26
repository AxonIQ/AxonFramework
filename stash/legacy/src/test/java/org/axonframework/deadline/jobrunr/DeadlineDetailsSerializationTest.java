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

package org.axonframework.deadline.jobrunr;

import org.axonframework.deadline.DeadlineMessage;
import org.axonframework.deadline.GenericDeadlineMessage;
import org.axonframework.deadline.TestScopeDescriptor;
import org.axonframework.messaging.core.GenericMessage;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.ScopeDescriptor;
import org.axonframework.conversion.Serializer;
import org.axonframework.conversion.SimpleSerializedObject;
import org.axonframework.conversion.json.JacksonSerializer;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DeadlineDetailsSerializationTest {

    private static final String TEST_DEADLINE_NAME = "deadline-name";
    private static final String TEST_DEADLINE_PAYLOAD = "deadline-payload";
    private static Metadata metadata;
    private static DeadlineMessage message;

    @BeforeAll
    static void setUp() {
        Map<String, String> map = new HashMap<>();
        map.put("someStringValue", "foo");
        map.put("someIntValue", "2");
        metadata = new Metadata(map);
        message = new GenericDeadlineMessage(
                TEST_DEADLINE_NAME,
                new GenericMessage(new MessageType(TEST_DEADLINE_PAYLOAD.getClass()), TEST_DEADLINE_PAYLOAD),
                Instant::now
        ).withMetadata(metadata);
    }

    @Test
    void whenSerializedAndDeserializedAllPropertiesShouldBeTheSameUsingJackson() {
        Serializer serializer = JacksonSerializer.defaultSerializer();
        testSerialisationWithSpecificSerializer(serializer);
    }

    private void testSerialisationWithSpecificSerializer(Serializer serializer) {
        String expectedType = "aggregateType";
        String expectedIdentifier = "identifier";
        ScopeDescriptor descriptor = new TestScopeDescriptor(expectedType, expectedIdentifier);
        String serializedDeadlineDetails = DeadlineDetails.serialized(
                TEST_DEADLINE_NAME, descriptor, message, serializer);
        SimpleSerializedObject<String> serializedDeadlineMetadata = new SimpleSerializedObject<>(
                serializedDeadlineDetails, String.class, DeadlineDetails.class.getName(), null
        );
        DeadlineDetails result = serializer.deserialize(serializedDeadlineMetadata);

        assertEquals(TEST_DEADLINE_NAME, result.getDeadlineName());
        assertEquals(descriptor, result.getDeserializedScopeDescriptor(serializer));
        DeadlineMessage resultMessage = result.asDeadLineMessage(serializer);

        assertNotNull(resultMessage);
        assertEquals(TEST_DEADLINE_PAYLOAD, resultMessage.payload());
        assertEquals(metadata, resultMessage.metadata());
    }
}