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
import org.axonframework.messaging.core.QualifiedName;

import java.util.function.Predicate;

/**
 * Folds every {@code 0.x} beta version of {@code WelcomeMessageSent} up to {@code 1.0.0} with a
 * single transformation. The predicate matches on version alone; {@code declaringFromTypes(...)}
 * scopes it to {@code WelcomeMessageSent}, so the predicate is only ever evaluated against
 * {@code WelcomeMessageSent} events and a type-filtering read for that name stays scoped to it
 * instead of scanning every stored event.
 * <p>
 * Contrast {@link WelcomeMessageBetaCleanupWithoutPreFilter}, which expresses the same rule
 * without {@code declaringFromTypes(...)}.
 */
public final class WelcomeMessageBetaCleanup {

    private static final Predicate<MessageType> BETA_VERSION =
            type -> type.version().startsWith("0.");

    private static final QualifiedName FROM_NAME =
            new QualifiedName(CourseCatalogMessageNames.WELCOME_MESSAGE_SENT);
    // Same name, bumped version: renaming an event is not supported.
    private static final MessageType TO = new MessageType(FROM_NAME, "1.0.0");

    private WelcomeMessageBetaCleanup() {
    }

    /** @return the transformation registered into the chain */
    public static EventTransformation build() {
        return EventTransformation.from(BETA_VERSION)
                                  .declaringFromTypes(FROM_NAME)
                                  .to(TO)
                                  .transform(JsonNode.class, WelcomeMessageBetaCleanup::map);
    }

    private static JsonNode map(JsonNode beta) {
        ObjectNode v1 = JsonNodeFactory.instance.objectNode();
        v1.set("studentId", beta.get("studentId"));
        v1.set("body",      beta.get("body"));
        return v1;
    }
}
