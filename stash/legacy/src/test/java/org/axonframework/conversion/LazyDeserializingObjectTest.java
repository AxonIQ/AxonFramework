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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * @author Allard Buijze
 */
class LazyDeserializingObjectTest {

    private Serializer mockSerializer;

    private SerializedType mockType;
    private SerializedObject mockObject;

    private String mockDeserializedObject = "I'm a mock";

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        mockSerializer = mock(Serializer.class);
        mockType = mock(SerializedType.class);
        mockObject = new SimpleSerializedObject(mockDeserializedObject, String.class, mockType);

        when(mockSerializer.classForType(mockType)).thenReturn(String.class);
        when(mockSerializer.deserialize(mockObject)).thenReturn(mockDeserializedObject);
    }

    @SuppressWarnings("unchecked")
    @Test
    void lazilyDeserialized() {
        LazyDeserializingObject<Object> testSubject = new LazyDeserializingObject<>(mockObject, mockSerializer);
        verify(mockSerializer, never()).deserialize(any(SerializedObject.class));
        assertEquals(String.class, testSubject.getType());
        assertFalse(testSubject.isDeserialized());
        verify(mockSerializer, never()).deserialize(any(SerializedObject.class));
        assertSame(mockDeserializedObject, testSubject.getObject());
        assertTrue(testSubject.isDeserialized());
    }

    @Test
    void lazilyDeserialized_NullObject() {
        assertThrows(Exception.class, () -> new LazyDeserializingObject<>(null, mockSerializer));
    }

    @Test
    void lazilyDeserialized_NullSerializer() {
        assertThrows(IllegalArgumentException.class, () -> new LazyDeserializingObject<>(mockObject, null));
    }

    @Test
    void withProvidedDeserializedInstance() {
        LazyDeserializingObject<Object> testSubject = new LazyDeserializingObject<>(mockDeserializedObject);
        assertEquals(mockDeserializedObject.getClass(), testSubject.getType());
        assertSame(mockDeserializedObject, testSubject.getObject());
        assertTrue(testSubject.isDeserialized());
    }

    @Test
    void withProvidedDeserializedNullInstance() {
        assertThrows(IllegalArgumentException.class, () -> new LazyDeserializingObject<>(null));
    }
}
