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

package org.axonframework.examples.demo.coursecatalog.shared.ids;

import java.util.UUID;

/**
 * Identifier of a single student, prefixed with {@code Student:}.
 *
 * @param value the student identifier; must be non-blank
 */
public record StudentId(String value) {

    private static final String ENTITY_TYPE = "Student";

    /**
     * @param value the student identifier
     * @throws IllegalArgumentException if {@code value} is blank
     */
    public StudentId {
        if (value.isBlank()) {
            throw new IllegalArgumentException("Student id cannot be blank");
        }
        value = withEntityTypePrefix(value);
    }

    /**
     * @param value the student identifier
     * @return a student identifier wrapping {@code value}
     */
    public static StudentId of(String value) {
        return new StudentId(value);
    }

    /** @return a random student identifier */
    public static StudentId random() {
        return new StudentId(UUID.randomUUID().toString());
    }

    /** @return the prefixed value */
    @Override
    public String toString() {
        return value;
    }

    private static String withEntityTypePrefix(String value) {
        return value.startsWith(ENTITY_TYPE + ":") ? value : ENTITY_TYPE + ":" + value;
    }
}
