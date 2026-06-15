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

import io.axoniq.framework.messaging.transformation.events.EventTransformer;
import org.axonframework.common.TypeReference;
import org.axonframework.examples.demo.coursecatalog.catalog.CourseCatalogMessageNames;
import org.axonframework.messaging.core.MessageType;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lifts a v1 {@code StudentRegistered} (separate {@code firstName} and
 * {@code lastName}) into the v2 shape with a combined {@code fullName}, using
 * the generic {@code TypeReference<Map<String, Object>>} overload of
 * {@code EventTransformer.transform(...)}.
 */
public final class StudentRegisteredV1ToV2 {

    private static final TypeReference<Map<String, Object>> INPUT_TYPE = new TypeReference<>() {
    };

    private static final MessageType FROM =
            new MessageType(CourseCatalogMessageNames.STUDENT_REGISTERED, "1.0.0");
    private static final MessageType TO =
            new MessageType(CourseCatalogMessageNames.STUDENT_REGISTERED, "2.0.0");

    private StudentRegisteredV1ToV2() {
    }

    /** @return the transformer registered into the chain */
    public static EventTransformer build() {
        return EventTransformer.from(FROM).to(TO).transform(INPUT_TYPE, StudentRegisteredV1ToV2::map);
    }

    private static Map<String, Object> map(Map<String, Object> v1) {
        Map<String, Object> v2 = new LinkedHashMap<>();
        v2.put("catalogId", v1.get("catalogId"));
        v2.put("studentId", v1.get("studentId"));
        v2.put("fullName", combine(v1.get("firstName"), v1.get("lastName")));
        return v2;
    }

    private static String combine(@Nullable Object first, @Nullable Object last) {
        String f = first == null ? "" : first.toString().trim();
        String l = last == null ? "" : last.toString().trim();
        if (f.isEmpty()) return l;
        if (l.isEmpty()) return f;
        return f + " " + l;
    }
}
