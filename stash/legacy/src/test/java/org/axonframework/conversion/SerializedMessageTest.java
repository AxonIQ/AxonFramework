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

package org.axonframework.conversion;

import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.Metadata;
import org.junit.jupiter.api.*;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link SerializedMessage}.
 *
 * @author Allard Buijze
 */
class SerializedMessageTest {

    private final SerializedObject<String> serializedPayload =
            new SimpleSerializedObject<>("serializedPayload", String.class, "java.lang.Object", "1");
    private final SerializedObject<String> serializedMetadata =
            new SerializedMetadata<>("serializedMetadata", String.class);
    private final Object deserializedPayload = new Object();
    private final Metadata deserializedMetadata = Metadata.emptyInstance();
    private final Serializer serializer = mock(Serializer.class);
    private final String eventId = "eventId";

    @BeforeEach
    void setUp() {
        when(serializer.deserialize(serializedMetadata)).thenReturn(deserializedMetadata);
        when(serializer.deserialize(serializedPayload)).thenReturn(deserializedPayload);
        when(serializer.classForType(isA(SerializedType.class))).thenReturn(Object.class);
        when(serializer.getConverter()).thenReturn(new ChainingContentTypeConverter());
    }

    @Test
    void constructorLeavesSerializedObjectsSerialized() {
        SerializedMessage testSubject =
                new SerializedMessage(eventId, serializedPayload, serializedMetadata, serializer);

        assertEquals(Object.class, testSubject.payloadType());
        assertFalse(testSubject.isPayloadDeserialized());
        assertEquals(Object.class, testSubject.payload().getClass());
        assertTrue(testSubject.isPayloadDeserialized());
        assertFalse(testSubject.isMetadataDeserialized());
        assertSame(Metadata.emptyInstance(), testSubject.metadata());
        assertTrue(testSubject.isMetadataDeserialized());
    }

    @Test
    void withMetadataReplacesOriginalMetadata() {
        Map<String, String> metadataMap = Collections.singletonMap("key", "value");
        Metadata metadata = Metadata.from(metadataMap);
        when(serializer.deserialize(serializedMetadata)).thenReturn(metadata);

        SerializedMessage testSubject =
                new SerializedMessage(eventId, serializedPayload, serializedMetadata, serializer);

        Message resultOne = testSubject.withMetadata(Metadata.emptyInstance());
        Message resultTwo =
                testSubject.withMetadata(Metadata.from(Collections.singletonMap("key", "otherValue")));

        assertEquals(0, resultOne.metadata().size());
        assertEquals(1, resultTwo.metadata().size());
    }

    @Test
    void andMetadataAppendsToOriginalMetadata() {
        Map<String, String> metadataMap = Collections.singletonMap("key", "value");
        Metadata metadata = Metadata.from(metadataMap);
        when(serializer.deserialize(serializedMetadata)).thenReturn(metadata);

        Message testSubject =
                new SerializedMessage(eventId, serializedPayload, serializedMetadata, serializer);

        Message resultOne = testSubject.andMetadata(Metadata.emptyInstance());
        assertEquals(1, resultOne.metadata().size());
        assertEquals("value", resultOne.metadata().get("key"));

        Message resultTwo =
                testSubject.andMetadata(Metadata.from(Collections.singletonMap("key", "otherValue")));
        assertEquals(1, resultTwo.metadata().size());
        assertEquals("otherValue", resultTwo.metadata().get("key"));
    }

    @Test
    void rethrowSerializationExceptionOnGetPayload() {
        SerializationException serializationException = new SerializationException("test message");
        when(serializer.deserialize(serializedMetadata)).thenThrow(serializationException);
        when(serializer.deserialize(serializedPayload)).thenThrow(serializationException);

        SerializedMessage testSubject =
                new SerializedMessage(eventId, serializedPayload, serializedMetadata, serializer);

        SerializationException result = assertThrows(SerializationException.class, testSubject::payload);
        assertEquals("Error while deserializing payload of message " + eventId, result.getMessage());
        assertSame(serializationException, result.getCause());
    }

    @Test
    void rethrowSerializationExceptionOnMetadata() {
        SerializationException serializationException = new SerializationException("test message");
        when(serializer.deserialize(serializedMetadata)).thenThrow(serializationException);
        when(serializer.deserialize(serializedPayload)).thenThrow(serializationException);

        SerializedMessage testSubject =
                new SerializedMessage(eventId, serializedPayload, serializedMetadata, serializer);

        SerializationException result = assertThrows(SerializationException.class, testSubject::metadata);
        assertEquals("Error while deserializing metadata of message " + eventId, result.getMessage());
        assertSame(serializationException, result.getCause());
    }
}
