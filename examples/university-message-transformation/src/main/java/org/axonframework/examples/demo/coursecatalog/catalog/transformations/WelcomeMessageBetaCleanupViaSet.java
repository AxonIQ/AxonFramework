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

import java.util.Set;

/**
 * The same {@code 0.x} beta cleanup as {@link WelcomeMessageBetaCleanup}, expressed by listing the
 * exact stored versions ({@code 0.5}, {@code 0.7}, {@code 0.9}) in {@link EventTransformation#from(Set)} rather
 * than matching them with a predicate. One mapper still applies to all of them.
 * <p>
 * This is the preferred form whenever the versions to cover are known: each is matched by exact
 * identity, so the chain resolves it without depending on registration order, and an exact match
 * always takes precedence over any overlapping predicate. Reach for a predicate (as
 * {@link WelcomeMessageBetaCleanup} does) only when the set of versions cannot be listed up front.
 */
public final class WelcomeMessageBetaCleanupViaSet {

    private static final QualifiedName NAME =
            new QualifiedName(CourseCatalogMessageNames.WELCOME_MESSAGE_SENT);
    // Same name, bumped version: renaming an event is not supported.
    private static final MessageType TO = new MessageType(NAME, "1.0.0");

    private WelcomeMessageBetaCleanupViaSet() {
    }

    /** @return the transformation; an unregistered alternative to {@link WelcomeMessageBetaCleanup} */
    public static EventTransformation build() {
        return EventTransformation.from(Set.of(new MessageType(NAME, "0.5"),
                                               new MessageType(NAME, "0.7"),
                                               new MessageType(NAME, "0.9")))
                                  .to(TO)
                                  .transform(JsonNode.class, WelcomeMessageBetaCleanupViaSet::map);
    }

    private static JsonNode map(JsonNode beta) {
        ObjectNode v1 = JsonNodeFactory.instance.objectNode();
        v1.set("studentId", beta.get("studentId"));
        v1.set("body",      beta.get("body"));
        return v1;
    }
}
