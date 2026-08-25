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

package org.axonframework.conversion.json;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;
import org.axonframework.conversion.*;
import org.junit.jupiter.api.*;
import org.mockito.*;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class validating the {@link JacksonSerializer}.
 *
 * @author Allard Buijze
 */
class JacksonSerializerTest {

    private ObjectMapper objectMapper;
    private Instant time;

    private JacksonSerializer testSubject;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        time = Instant.now();

        testSubject = JacksonSerializer.builder()
                                       .objectMapper(objectMapper)
                                       .build();
    }

    @Test
    void canSerializeToByteArrayStringInputStreamJsonNodeAndObjectNode() {
        assertTrue(testSubject.canSerializeTo(byte[].class));
        assertTrue(testSubject.canSerializeTo(String.class));
        assertTrue(testSubject.canSerializeTo(InputStream.class));
        assertTrue(testSubject.canSerializeTo(JsonNode.class));
        assertTrue(testSubject.canSerializeTo(ObjectNode.class));
    }

    @Test
    void serializeAndDeserializeObject_StringFormat() {
        SimpleSerializableType toSerialize =
                new SimpleSerializableType("first", time, new SimpleSerializableType("nested"));

        SerializedObject<String> serialized = testSubject.serialize(toSerialize, String.class);

        SimpleSerializableType actual = testSubject.deserialize(serialized);
        assertEquals(toSerialize.getValue(), actual.getValue());
        assertEquals(toSerialize.getNested().getValue(), actual.getNested().getValue());
    }

    @Test
    void serializeAndDeserializeArray() {
        SimpleSerializableType toSerialize =
                new SimpleSerializableType("first", time, new SimpleSerializableType("nested"));

        SerializedObject<String> serialized =
                testSubject.serialize(new SimpleSerializableType[]{toSerialize}, String.class);

        SimpleSerializableType[] actual = testSubject.deserialize(serialized);
        assertEquals(1, actual.length);
        assertEquals(toSerialize.getValue(), actual[0].getValue());
        assertEquals(toSerialize.getNested().getValue(), actual[0].getNested().getValue());
    }

    @Test
    void serializeAndDeserializeList() {
        objectMapper.activateDefaultTyping(objectMapper.getPolymorphicTypeValidator(),
                                           ObjectMapper.DefaultTyping.NON_CONCRETE_AND_ARRAYS);
        SimpleSerializableType toSerialize =
                new SimpleSerializableType("first", time, new SimpleSerializableType("nested"));

        SerializedObject<String> serialized = testSubject.serialize(singletonList(toSerialize), String.class);

        List<SimpleSerializableType> actual = testSubject.deserialize(serialized);
        assertEquals(1, actual.size());
        assertEquals(toSerialize.getValue(), actual.get(0).getValue());
        assertEquals(toSerialize.getNested().getValue(), actual.get(0).getNested().getValue());
    }

    @Test
    void serializeAndDeserializeObject_ByteArrayFormat() {
        SimpleSerializableType toSerialize =
                new SimpleSerializableType("first", time, new SimpleSerializableType("nested"));

        SerializedObject<byte[]> serialized = testSubject.serialize(toSerialize, byte[].class);

        SimpleSerializableType actual = testSubject.deserialize(serialized);

        assertEquals(toSerialize.getValue(), actual.getValue());
        assertEquals(toSerialize.getNested().getValue(), actual.getNested().getValue());
    }

    @Test
    void serializeAndDeserializeObjectUnknownType() {
        SimpleSerializableType toSerialize =
                new SimpleSerializableType("first", time, new SimpleSerializableType("nested"));

        SerializedObject<byte[]> serialized = testSubject.serialize(toSerialize, byte[].class);

        Object actual = testSubject.deserialize(new SimpleSerializedObject<>(serialized.getData(),
                                                                             byte[].class,
                                                                             "someUnknownType",
                                                                             "42.1"));

        assertTrue(actual instanceof UnknownSerializedType);
        UnknownSerializedType actualUnknown = ((UnknownSerializedType) actual);

        assertTrue(actualUnknown.supportsFormat(JsonNode.class));
        JsonNode actualJson = actualUnknown.readData(JsonNode.class);

        assertEquals("first", actualJson.get("value").asText());
        assertEquals("nested", actualJson.path("nested").path("value").asText());
    }

    @Test
    void readUnknownSerializedTypeCachesLookupResults() {
        ObjectMapper spiedMapper = Mockito.spy(objectMapper);
        testSubject = JacksonSerializer.builder()
                                       .objectMapper(spiedMapper)
                                       .build();
        SerializedObject<String> testObject =
                new SimpleSerializedObject<>("{\"data\" : \"value\"}", String.class, "my.nonexistent.Class", null);

        for (int i = 0; i < 10; i++) {
            Class<?> actual = testSubject.classForType(testObject.getType());
            assertEquals(UnknownSerializedType.class, actual);
        }

        Mockito.verify(spiedMapper, Mockito.times(1)).getTypeFactory();
    }

    @Test
    void readUnknownSerializedTypeWithCachingDisabled() {
        ObjectMapper spiedMapper = Mockito.spy(objectMapper);
        testSubject = JacksonSerializer.builder()
                                       .objectMapper(spiedMapper)
                                       .disableCachingOfUnknownClasses()
                                       .build();
        SerializedObject<String> testObject =
                new SimpleSerializedObject<>("{\"data\" : \"value\"}", String.class, "my.nonexistent.Class", null);

        for (int i = 0; i < 10; i++) {
            Class<?> actual = testSubject.classForType(testObject.getType());
            assertEquals(UnknownSerializedType.class, actual);
        }

        Mockito.verify(spiedMapper, Mockito.times(10)).getTypeFactory();
    }

    @Test
    void serializeAndDeserializeObject_JsonNodeFormat() {
        SimpleSerializableType toSerialize =
                new SimpleSerializableType("first", time, new SimpleSerializableType("nested"));

        SerializedObject<JsonNode> serialized = testSubject.serialize(toSerialize, JsonNode.class);
        SimpleSerializableType actual = testSubject.deserialize(serialized);

        assertEquals(toSerialize.getValue(), actual.getValue());
        assertEquals(toSerialize.getNested().getValue(), actual.getNested().getValue());
    }

    @Test
    void customObjectMapper() {
        ObjectMapper objectMapper = Mockito.spy(new ObjectMapper());

        testSubject = JacksonSerializer.builder()
                                       .objectMapper(objectMapper)
                                       .build();

        SerializedObject<byte[]> serialized =
                testSubject.serialize(new SimpleSerializableType("test"), byte[].class);
        SimpleSerializableType actual = testSubject.deserialize(serialized);

        assertNotNull(actual);
        assertTrue(testSubject.getConverter() instanceof ChainingContentTypeConverter);
        Mockito.verify(objectMapper).readerFor(SimpleSerializableType.class);
        Mockito.verify(objectMapper).writer();
    }

    @Test
    void serializeMetadata() {
        testSubject = JacksonSerializer.builder().build();

        SerializedObject<byte[]> serialized =
                testSubject.serialize(Metadata.from(singletonMap("test", "test")), byte[].class);
        Metadata actual = testSubject.deserialize(serialized);

        assertNotNull(actual);
        assertEquals("test", actual.get("test"));
        assertEquals(1, actual.size());
    }

    /**
     * Test case corresponding with a {@link QueryHandler} annotated method which
     * returns a {@link List} of {@link ComplexObject}. Upon deserialization, the type info is required by the
     * {@link ObjectMapper} to <b>not</b> defer to an {@link ArrayList} of {@link java.util.LinkedHashMap}s. This can be
     * enabled through {@link JacksonSerializer.Builder#defaultTyping()} or by providing an {@link ObjectMapper} which
     * is configured with {@link ObjectMapper#enableDefaultTyping(ObjectMapper.DefaultTyping)}.
     */
    @Test
    void serializeCollectionOfObjects() {
        // Typing must be enabled for this (which we expect end-users to do)
        JacksonSerializer testSubject = JacksonSerializer.builder().defaultTyping().build();

        List<ComplexObject> objectToSerialize = new ArrayList<>();
        objectToSerialize.add(new ComplexObject("String1", "String2", 3));
        objectToSerialize.add(new ComplexObject("String4", "String5", 6));
        objectToSerialize.add(new ComplexObject("String7", "String8", 9));

        SerializedObject<String> serializedResult = testSubject.serialize(objectToSerialize, String.class);

        List<ComplexObject> deserializedResult = testSubject.deserialize(serializedResult);

        assertEquals(objectToSerialize, deserializedResult);
    }

    @Test
    void deserializeNullValue() {
        SerializedObject<byte[]> serializedNull = testSubject.serialize(null, byte[].class);
        SimpleSerializedObject<byte[]> serializedNullString = new SimpleSerializedObject<>(
                serializedNull.getData(), byte[].class, testSubject.typeForClass(String.class)
        );
        assertNull(testSubject.deserialize(serializedNull));
        assertNull(testSubject.deserialize(serializedNullString));
    }

    @Test
    void deserializeEmptyBytes() {
        assertEquals(Void.class, testSubject.classForType(SerializedType.emptyType()));
        assertNull(testSubject.deserialize(
                new SimpleSerializedObject<>(new byte[0], byte[].class, SerializedType.emptyType())
        ));
    }

    @Test
    void deserializeLenientIgnoresUnknownValues() {
        testSubject = JacksonSerializer.builder().lenientDeserialization().objectMapper(objectMapper).build();
        SerializedObject<JsonNode> serialized =
                testSubject.serialize(new ComplexObject("one", "two", 3), JsonNode.class);
        ObjectNode data = (ObjectNode) serialized.getData();
        JsonNodeFactory nf = objectMapper.getNodeFactory();
        data.set("newField", nf.textNode("newValue"));
        ArrayNode arrayNode = nf.arrayNode().add(data);
        ComplexObject actual = testSubject.deserialize(
                new SimpleSerializedObject<>(arrayNode, JsonNode.class, serialized.getType())
        );
        assertEquals("one", actual.value1());
        assertEquals("two", actual.value2());
        assertEquals(3, actual.value3());
    }

    @Test
    void serializeAndDeserializeObjectObjectNodeFormat() {
        SimpleSerializableType toSerialize =
                new SimpleSerializableType("first", time, new SimpleSerializableType("nested"));

        SerializedObject<ObjectNode> serialized = testSubject.serialize(toSerialize, ObjectNode.class);
        SimpleSerializableType actual = testSubject.deserialize(serialized);

        assertEquals(toSerialize.getValue(), actual.getValue());
        assertEquals(toSerialize.getNested().getValue(), actual.getNested().getValue());
    }

    @Test
    void testConvertByteArrayToString() throws JsonProcessingException {
        ComplexObject object = new ComplexObject("value1", "value2", 3);
        byte[] source = objectMapper.writeValueAsBytes(object);
        String actual = testSubject.convert(source, String.class);

        assertEquals("{\"value1\":\"value1\",\"value2\":\"value2\",\"value3\":3}", actual);
    }

    public record ComplexObject(String value1, String value2, int value3) {

            @JsonCreator
            public ComplexObject(@JsonProperty("value1") String value1,
                                 @JsonProperty("value2") String value2,
                                 @JsonProperty("value3") int value3) {
                this.value1 = value1;
                this.value2 = value2;
                this.value3 = value3;
            }
    }

    public static class SimpleSerializableType {

        private final String value;
        private final Instant time;
        private final SimpleSerializableType nested;

        public SimpleSerializableType(String value) {
            this(value, Instant.now(), null);
        }

        @JsonCreator
        public SimpleSerializableType(@JsonProperty("value") String value,
                                      @JsonProperty("time") Instant time,
                                      @JsonProperty("nested") SimpleSerializableType nested) {
            this.value = value;
            this.time = time;
            this.nested = nested;
        }

        public SimpleSerializableType getNested() {
            return nested;
        }

        public String getValue() {
            return value;
        }

        public Instant getTime() {
            return time;
        }
    }
}
