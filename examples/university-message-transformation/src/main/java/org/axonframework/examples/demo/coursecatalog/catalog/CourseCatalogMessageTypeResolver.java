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

package org.axonframework.examples.demo.coursecatalog.catalog;

import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.annotation.AnnotationMessageTypeResolver;
import tools.jackson.databind.JsonNode;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves payload types to {@link MessageType}s for the catalog application.
 * Behaves like the framework's {@link AnnotationMessageTypeResolver} for typed
 * payloads (records / classes annotated with {@code @Event}), but returns
 * {@link Optional#empty()} for "untyped" representations like {@link JsonNode},
 * {@link Map}, {@link Collection}, and raw {@code byte[]}.
 * <p>
 * This matches the framework's {@code resolver-permitting} contract for the
 * transformation chain's output-identity check: when a mapper returns one of
 * these untyped types, the check is intentionally skipped because the runtime
 * class carries no identity. The framework's default fallback
 * ({@code ClassBasedMessageTypeResolver}) instead synthesises a {@code MessageType}
 * from the class FQN, which would fail the check for every untyped mapper output.
 */
public final class CourseCatalogMessageTypeResolver implements MessageTypeResolver {

    private final MessageTypeResolver delegate = new AnnotationMessageTypeResolver();

    @Override
    public Optional<MessageType> resolve(Class<?> payloadType) {
        if (isUntypedRepresentation(payloadType)) {
            return Optional.empty();
        }
        return delegate.resolve(payloadType);
    }

    private static boolean isUntypedRepresentation(Class<?> payloadType) {
        return JsonNode.class.isAssignableFrom(payloadType)
                || Map.class.isAssignableFrom(payloadType)
                || Collection.class.isAssignableFrom(payloadType)
                || payloadType == byte[].class;
    }
}
