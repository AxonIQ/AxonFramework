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

package org.axonframework.messaging.tracing.attributes;

import org.axonframework.messaging.tracing.SpanAttributesProvider;
import org.axonframework.messaging.core.LegacyResources;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Objects;

/**
 * Adds the aggregate identifier to the span when one is available on the {@link ProcessingContext} via
 * {@link LegacyResources#AGGREGATE_IDENTIFIER_KEY}. By default the attribute key is {@link #DEFAULT_ATTRIBUTE_KEY}
 * ({@code axoniq.aggregate.identifier}); a different key can be supplied through the constructor.
 * <p>
 * This is best-effort: the attribute is present only when a legacy aggregate-based event storage engine populated the
 * resource. It is absent for dynamic-consistency-boundary / entity-based operations and whenever no context is
 * available.
 * <p>
 * <b>Legacy-only.</b> This provider is exclusively for events produced by a legacy, aggregate-based event store. For
 * dynamic-consistency-boundary events, tags are recorded by the {@code axon-eventsourcing} module's
 * {@code EventTagsSpanAttributesProvider} instead.
 *
 * @author Mateusz Nowak
 * @author Mitchell Herrijgers
 * @since 4.6.0
 */
public final class AggregateIdentifierSpanAttributesProvider implements SpanAttributesProvider {

    /**
     * Default attribute key under which the aggregate identifier is recorded.
     */
    public static final String DEFAULT_ATTRIBUTE_KEY = "axoniq.aggregate.identifier";

    private final String attributeKey;

    /**
     * Creates a provider recording the aggregate identifier under the default {@link #DEFAULT_ATTRIBUTE_KEY} key.
     */
    public AggregateIdentifierSpanAttributesProvider() {
        this(DEFAULT_ATTRIBUTE_KEY);
    }

    /**
     * Creates a provider recording the aggregate identifier under the given {@code attributeKey}.
     *
     * @param attributeKey the span attribute key to record the aggregate identifier under
     */
    public AggregateIdentifierSpanAttributesProvider(String attributeKey) {
        this.attributeKey = Objects.requireNonNull(attributeKey, "attributeKey may not be null");
    }

    @Override
    public Map<String, String> provideForMessage(Message message, @Nullable ProcessingContext context) {
        if (context == null) {
            return Map.of();
        }
        String aggregateIdentifier = context.getResource(LegacyResources.AGGREGATE_IDENTIFIER_KEY);
        if (aggregateIdentifier == null) {
            return Map.of();
        }
        return Map.of(attributeKey, aggregateIdentifier);
    }
}
