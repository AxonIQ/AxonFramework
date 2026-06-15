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
import org.axonframework.messaging.core.MessageType;

import java.util.function.Predicate;

/**
 * Uses the predicate-based {@code from} overload to match any {@code 0.x} version
 * in a single transformer instead of one transformer per beta version.
 */
public final class WelcomeMessageBetaCleanup {

    private static final Predicate<MessageType> FROM_PREDICATE =
            type -> CourseCatalogMessageNames.WELCOME_MESSAGE_SENT.equals(type.qualifiedName().name())
                    && type.version().startsWith("0.");

    private static final MessageType TO =
            new MessageType(CourseCatalogMessageNames.WELCOME_MESSAGE_SENT, "1.0.0");

    private WelcomeMessageBetaCleanup() {
    }

    /** @return the transformer registered into the chain */
    public static EventTransformer build() {
        return EventTransformer.from(FROM_PREDICATE).to(TO).transform(JsonNode.class, WelcomeMessageBetaCleanup::map);
    }

    private static JsonNode map(JsonNode beta) {
        ObjectNode v1 = JsonNodeFactory.instance.objectNode();
        v1.set("studentId", beta.get("studentId"));
        v1.set("body",      beta.get("body"));
        return v1;
    }
}
