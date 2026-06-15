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

package org.axonframework.examples.demo.coursecatalog.catalog.testutil;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Helpers for reading golden JSON resources and asserting field-order-insensitive
 * equality between JSON payloads.
 */
public final class JsonAssertions {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonAssertions() {
    }

    /**
     * Converts an arbitrary payload (Map, JsonNode, POJO) into a {@link JsonNode}
     * so structural comparison against a fixture JSON tree is type-insensitive.
     *
     * @param payload the value to convert
     * @return the corresponding JSON tree
     */
    public static JsonNode toJsonTree(Object payload) {
        return MAPPER.valueToTree(payload);
    }

    /**
     * @param resourcePath classpath-relative path beginning with a slash
     * @return the parsed JSON tree
     * @throws IllegalArgumentException if the resource cannot be found or parsed
     */
    public static JsonNode loadJson(String resourcePath) {
        try (InputStream in = JsonAssertions.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalArgumentException("Golden JSON resource not found: " + resourcePath);
            }
            return MAPPER.readTree(in);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read golden JSON: " + resourcePath, e);
        }
    }

    /**
     * @param actual   payload produced by a transformation
     * @param expected expected payload (typically loaded via {@link #loadJson(String)})
     * @throws AssertionError if the trees do not match by deep equality
     */
    public static void assertJsonEquals(JsonNode actual, JsonNode expected) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("JSON payloads differ.\nExpected:\n" + pretty(expected)
                                            + "\nActual:\n" + pretty(actual));
        }
    }

    private static String pretty(JsonNode node) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return String.valueOf(node);
        }
    }
}
