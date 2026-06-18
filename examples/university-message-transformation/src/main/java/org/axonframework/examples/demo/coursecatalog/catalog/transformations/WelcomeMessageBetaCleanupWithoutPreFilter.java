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
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;

import java.util.function.Predicate;

/**
 * The same {@code 0.x} beta cleanup as {@link WelcomeMessageBetaCleanup}, but expressed without
 * {@code declaringFromTypes(...)}. The qualified-name guard must then live inside the predicate,
 * and because the chain cannot look inside a predicate, a type-filtering read drops its type
 * filter (a broader read) so nothing is excluded before the predicate runs.
 * <p>
 * Kept beside {@link WelcomeMessageBetaCleanup} to contrast the two predicate-{@code from}
 * styles; the chain registers the {@code declaringFromTypes(...)} form.
 */
public final class WelcomeMessageBetaCleanupWithoutPreFilter {

    private static final Predicate<MessageType> NAME_AND_BETA_VERSION =
            type -> CourseCatalogMessageNames.WELCOME_MESSAGE_SENT.equals(type.qualifiedName().name())
                    && type.version().startsWith("0.");

    private static final MessageType TO =
            new MessageType(CourseCatalogMessageNames.WELCOME_MESSAGE_SENT, "1.0.0");

    private WelcomeMessageBetaCleanupWithoutPreFilter() {
    }

    /** @return the transformation; an unregistered alternative to {@link WelcomeMessageBetaCleanup} */
    public static EventTransformation build() {
        return EventTransformation.from(NAME_AND_BETA_VERSION)
                                  .to(TO)
                                  .transform(JsonNode.class, WelcomeMessageBetaCleanupWithoutPreFilter::map);
    }

    private static JsonNode map(JsonNode beta, @Nullable ProcessingContext context) {
        ObjectNode v1 = JsonNodeFactory.instance.objectNode();
        v1.set("studentId", beta.get("studentId"));
        v1.set("body",      beta.get("body"));
        return v1;
    }
}
