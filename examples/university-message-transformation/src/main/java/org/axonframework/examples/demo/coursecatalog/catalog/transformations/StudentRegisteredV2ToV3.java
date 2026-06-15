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
import io.axoniq.framework.messaging.transformation.events.EventTransformer;
import org.axonframework.examples.demo.coursecatalog.catalog.CourseCatalogMessageNames;
import org.axonframework.examples.demo.coursecatalog.shared.region.RequestRegion;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;

/**
 * Lifts a v2 {@code StudentRegistered} into the v3 shape, which added a {@code region}
 * after the catalog gained multi-region support.
 * <p>
 * This is the one transformation in the catalog that genuinely needs the
 * {@link ProcessingContext}: the field did not exist when historic events were written,
 * so it is backfilled from the {@link RequestRegion region the read runs in} rather than
 * from the stored payload. Reads that run with no region (such as a background tracking
 * processor) fall back to {@link RequestRegion#UNKNOWN_REGION}.
 */
public final class StudentRegisteredV2ToV3 {

    private static final MessageType FROM =
            new MessageType(CourseCatalogMessageNames.STUDENT_REGISTERED, "2.0.0");
    private static final MessageType TO =
            new MessageType(CourseCatalogMessageNames.STUDENT_REGISTERED, "3.0.0");

    private StudentRegisteredV2ToV3() {
    }

    /** @return the transformer registered into the chain */
    public static EventTransformer build() {
        return EventTransformer.from(FROM).to(TO).transform(JsonNode.class, StudentRegisteredV2ToV3::map);
    }

    private static JsonNode map(JsonNode v2, @Nullable ProcessingContext context) {
        ObjectNode v3 = JsonNodeFactory.instance.objectNode();
        v3.set("catalogId", v2.get("catalogId"));
        v3.set("studentId", v2.get("studentId"));
        v3.set("fullName", v2.get("fullName"));
        v3.put("region", RequestRegion.resolve(context));
        return v3;
    }
}
