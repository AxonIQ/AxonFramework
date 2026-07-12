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

/**
 * Holder for the shared span attribute key under which an entity's identifier is recorded, regardless of whether
 * that entity is a legacy aggregate or a dynamic-consistency-boundary entity.
 * <p>
 * Unlike {@link AggregateIdentifierSpanAttributesProvider}, this is not a {@link org.axonframework.messaging.tracing.SpanAttributesProvider}:
 * there is no generic resource on the {@link org.axonframework.messaging.core.unitofwork.ProcessingContext} holding
 * "the current entity id" for an arbitrary entity type. Instead, tracing decorators that already know an entity's
 * identifier locally - such as {@code TracingRepository}, {@code TracingStateManager},
 * {@code TracingEntityLifecycleHandler}, and {@code TracingSnapshotStore} - tag their spans with it directly under
 * {@link #DEFAULT_ATTRIBUTE_KEY}.
 * <p>
 * {@link AggregateIdentifierSpanAttributesProvider} remains the dedicated, legacy-only provider for events sourced
 * through an aggregate-based event storage engine.
 *
 * @author Mateusz Nowak
 * @since 5.3.0
 */
public final class EntityIdSpanAttributesProvider {

    /**
     * Default attribute key under which an entity's identifier is recorded.
     */
    public static final String DEFAULT_ATTRIBUTE_KEY = "axoniq.entity.id";

    private EntityIdSpanAttributesProvider() {
        // Utility class; holds the shared attribute key constant only.
    }
}
