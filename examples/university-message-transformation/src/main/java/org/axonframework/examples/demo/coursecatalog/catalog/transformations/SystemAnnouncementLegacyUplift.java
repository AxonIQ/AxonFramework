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
import org.axonframework.examples.demo.coursecatalog.catalog.CourseCatalogMessageNames;
import org.axonframework.messaging.core.MessageType;

/**
 * Lifts an unversioned {@code SystemAnnouncement} (which AF5 treats as version
 * {@code 0.0.1}) into the v1 shape. The legacy payload carried a single
 * {@code message} field; v1 renamed it to {@code text}.
 */
public final class SystemAnnouncementLegacyUplift {

    private static final MessageType FROM =
            new MessageType(CourseCatalogMessageNames.SYSTEM_ANNOUNCEMENT, "0.0.1");
    private static final MessageType TO =
            new MessageType(CourseCatalogMessageNames.SYSTEM_ANNOUNCEMENT, "1.0.0");

    private SystemAnnouncementLegacyUplift() {
    }

    /** @return the transformation registered into the chain */
    public static EventTransformation build() {
        return EventTransformation.from(FROM).to(TO).transform(JsonNode.class, SystemAnnouncementLegacyUplift::map);
    }

    private static JsonNode map(JsonNode legacy) {
        ObjectNode v1 = JsonNodeFactory.instance.objectNode();
        v1.set("catalogId", legacy.get("catalogId"));
        v1.put("text", legacy.get("message").asString());
        return v1;
    }
}
