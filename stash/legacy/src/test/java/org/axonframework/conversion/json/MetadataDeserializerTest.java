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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.axonframework.messaging.core.Metadata;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.stream.Stream;

class MetadataDeserializerTest {

    public static Stream<Arguments> objectMappers() {
        return Stream.of(null,
                         ObjectMapper.DefaultTyping.JAVA_LANG_OBJECT,
                         ObjectMapper.DefaultTyping.NON_CONCRETE_AND_ARRAYS,
                         ObjectMapper.DefaultTyping.NON_FINAL,
                         ObjectMapper.DefaultTyping.OBJECT_AND_NON_CONCRETE)
                     .map(defaultTyping -> Arguments.of(objectMapper(defaultTyping), defaultTyping));
    }

    private static ObjectMapper objectMapper(ObjectMapper.DefaultTyping defaultTyping) {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(
                new SimpleModule("Axon-Jackson Module").addDeserializer(Metadata.class, new MetadataDeserializer()));

        if (defaultTyping != null) {
            objectMapper.activateDefaultTyping(objectMapper.getPolymorphicTypeValidator(), defaultTyping);
        }
        return objectMapper;
    }

    @MethodSource("objectMappers")
    @ParameterizedTest
    void metadataSerializationWithDefaultTyping(ObjectMapper objectMapper) throws IOException {
        Metadata metadata = new Metadata(Collections.singletonMap("one", "two"));
        String serializedString = objectMapper.writeValueAsString(metadata);

        Metadata deserialized = objectMapper.readValue(serializedString, Metadata.class);
        Assertions.assertEquals("two", deserialized.get("one"));
        Assertions.assertEquals(serializedString, objectMapper.writeValueAsString(deserialized));
    }

    @MethodSource("objectMappers")
    @ParameterizedTest
    void emptyMetadataSerializationWithDefaultTyping(ObjectMapper objectMapper) throws IOException {
        Metadata metadata1 = new Metadata(new HashMap<>());
        String emptySerializedString = objectMapper.writeValueAsString(metadata1);

        Metadata deserialized = objectMapper.readValue(emptySerializedString, Metadata.class);
        Assertions.assertTrue(deserialized.entrySet().isEmpty());

        Assertions.assertEquals(emptySerializedString, objectMapper.writeValueAsString(deserialized));
    }

    @MethodSource("objectMappers")
    @ParameterizedTest
    void metadataContainerWithDefaultTyping(ObjectMapper objectMapper) throws IOException {
        Metadata metadata = new Metadata(Collections.singletonMap("one", "two"));

        Container container = new Container("a", metadata, 1);
        String serializedContainerString = objectMapper.writeValueAsString(container);

        Container deserialized = objectMapper.readValue(serializedContainerString, Container.class);
        Assertions.assertEquals("two", deserialized.b.get("one"));

        Assertions.assertEquals(serializedContainerString, objectMapper.writeValueAsString(deserialized));
    }

    public static class Container {

        private String a;
        private Metadata b;
        private Integer c;

        @JsonCreator
        public Container(
                @JsonProperty("a") String a,
                @JsonProperty("b") Metadata b,
                @JsonProperty("c") Integer c) {
            this.a = a;
            this.b = b;
            this.c = c;
        }

        public String getA() {
            return a;
        }

        public void setA(String a) {
            this.a = a;
        }

        public Metadata getB() {
            return b;
        }

        public void setB(Metadata b) {
            this.b = b;
        }

        public Integer getC() {
            return c;
        }

        public void setC(Integer c) {
            this.c = c;
        }
    }
}
