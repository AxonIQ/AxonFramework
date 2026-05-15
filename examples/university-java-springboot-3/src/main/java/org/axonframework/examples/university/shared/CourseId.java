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

package org.axonframework.examples.university.shared;

import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.NonNull;

import java.util.UUID;

/**
 * Course courseId.
 *
 * @param raw raw string courseId representation.
 */
public record CourseId(@NotNull String raw) {

    private final static String ENTITY_TYPE = "Course";

    public CourseId {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Course ID cannot be null or empty");
        }
        raw = withType(raw);
    }

    public static CourseId of(String raw) {
        return new CourseId(raw);
    }

    public static CourseId random() {
        return new CourseId(UUID.randomUUID().toString());
    }

    @Override
    public @NonNull String toString() {
        return raw;
    }

    private static String withType(String id) {
        return id.startsWith(ENTITY_TYPE + ":") ? id : ENTITY_TYPE + ":" + id;
    }
}