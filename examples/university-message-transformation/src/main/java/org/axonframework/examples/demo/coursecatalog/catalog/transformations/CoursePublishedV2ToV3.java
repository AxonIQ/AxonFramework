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
 * Lifts a v2 {@code CoursePublished} (separate {@code minCapacity} and
 * {@code maxCapacity}) into the v3 shape that wraps both into a single
 * {@code range} value object.
 */
public final class CoursePublishedV2ToV3 {

    private static final MessageType FROM = new MessageType(CourseCatalogMessageNames.COURSE_PUBLISHED, "2.0.0");
    private static final MessageType TO   = new MessageType(CourseCatalogMessageNames.COURSE_PUBLISHED, "3.0.0");

    private CoursePublishedV2ToV3() {
    }

    /** @return the transformer registered into the chain */
    public static EventTransformer build() {
        return EventTransformation.from(FROM).to(TO).transform(JsonNode.class, CoursePublishedV2ToV3::map);
    }

    private static JsonNode map(JsonNode v2, @Nullable ProcessingContext context) {
        ObjectNode v3 = JsonNodeFactory.instance.objectNode();
        v3.set("catalogId", v2.get("catalogId"));
        v3.set("courseId",  v2.get("courseId"));
        v3.set("name",      v2.get("name"));
        ObjectNode range = v3.putObject("range");
        range.put("min", v2.get("minCapacity").asInt());
        range.put("max", v2.get("maxCapacity").asInt());
        return v3;
    }
}
