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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import io.axoniq.framework.messaging.transformation.events.EventTransformation;
import io.axoniq.framework.messaging.transformation.events.EventTransformer;
import org.axonframework.examples.demo.coursecatalog.catalog.CourseCatalogMessageNames;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;

/**
 * Lifts a year-1 {@code CoursePublished} (single {@code capacity} field) into the
 * year-2 shape with {@code minCapacity} and {@code maxCapacity}.
 */
public final class CoursePublishedV1ToV2 {

    private static final MessageType FROM = new MessageType(CourseCatalogMessageNames.COURSE_PUBLISHED, "1.0.0");
    private static final MessageType TO   = new MessageType(CourseCatalogMessageNames.COURSE_PUBLISHED, "2.0.0");

    private CoursePublishedV1ToV2() {
    }

    /** @return the transformer registered into the chain */
    public static EventTransformer build() {
        return EventTransformation.from(FROM).to(TO).transform(JsonNode.class, CoursePublishedV1ToV2::map);
    }

    private static JsonNode map(JsonNode v1, @Nullable ProcessingContext context) {
        int capacity = v1.get("capacity").asInt();
        ObjectNode v2 = JsonNodeFactory.instance.objectNode();
        v2.set("catalogId", v1.get("catalogId"));
        v2.set("courseId",  v1.get("courseId"));
        v2.set("name",      v1.get("name"));
        v2.put("minCapacity", capacity);
        v2.put("maxCapacity", capacity);
        return v2;
    }
}
