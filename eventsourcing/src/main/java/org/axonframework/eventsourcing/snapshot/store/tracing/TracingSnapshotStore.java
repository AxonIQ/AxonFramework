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

package org.axonframework.eventsourcing.snapshot.store.tracing;

import org.axonframework.messaging.tracing.Span;
import org.axonframework.messaging.tracing.SpanFactory;
import org.axonframework.messaging.tracing.attributes.EntityIdSpanAttributesProvider;
import org.axonframework.common.annotation.Internal;
import org.axonframework.eventsourcing.snapshot.api.Snapshot;
import org.axonframework.eventsourcing.snapshot.store.SnapshotStore;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Delegating {@link SnapshotStore} decorator that opens internal tracing spans around the snapshot store / load
 * operations.
 * <p>
 * Each span carries the snapshot's qualified name as its trailing element ({@code "SnapshotStore.store Booking"}) and
 * an {@code axoniq.entity.id} attribute holding the identifier.
 * <p>
 * This decorator is registered by {@code EventSourcingTracingConfigurationEnhancer}; it is never instantiated directly
 * by applications.
 *
 * @author Mateusz Nowak
 * @since 5.3.0
 */
@Internal
public final class TracingSnapshotStore implements SnapshotStore {

    /** Prefix for the snapshot-store-write span ({@code "SnapshotStore.store <name>"}). */
    private static final String STORE_SPAN = "SnapshotStore.store";

    /** Prefix for the snapshot-store-read span ({@code "SnapshotStore.load <name>"}). */
    private static final String LOAD_SPAN = "SnapshotStore.load";

    /** Attribute key for the entity type (same convention as the modelling tracing decorators). */
    private static final String ENTITY_TYPE_KEY = "axoniq.entity.type";

    private final SnapshotStore delegate;
    private final SpanFactory spanFactory;

    /**
     * Initializes a tracing {@link SnapshotStore} wrapping the given {@code delegate}, obtaining spans from the given
     * {@code spanFactory}.
     *
     * @param delegate    the snapshot store to delegate to
     * @param spanFactory the factory producing the tracing spans
     */
    public TracingSnapshotStore(SnapshotStore delegate, SpanFactory spanFactory) {
        this.delegate = Objects.requireNonNull(delegate, "delegate may not be null");
        this.spanFactory = Objects.requireNonNull(spanFactory, "spanFactory may not be null");
    }

    @Override
    public CompletableFuture<Void> store(QualifiedName qualifiedName, Object identifier, Snapshot snapshot,
                                         @Nullable ProcessingContext context) {
        Span span = openSpan(STORE_SPAN, qualifiedName, identifier, context);
        return span.branchAsync(context, scoped -> delegate.store(qualifiedName, identifier, snapshot, scoped));
    }

    @Override
    public CompletableFuture<@Nullable Snapshot> load(QualifiedName qualifiedName, Object identifier,
                                                      @Nullable ProcessingContext context) {
        Span span = openSpan(LOAD_SPAN, qualifiedName, identifier, context);
        return span.branchAsync(context, scoped -> delegate.load(qualifiedName, identifier, scoped));
    }

    private Span openSpan(String prefix, QualifiedName qualifiedName, @Nullable Object identifier,
                          @Nullable ProcessingContext context) {
        Span span = spanFactory.createInternalSpan(prefix + " " + qualifiedName.name(), context)
                               .addAttribute(ENTITY_TYPE_KEY, qualifiedName.name());
        if (identifier != null) {
            span.addAttribute(EntityIdSpanAttributesProvider.DEFAULT_ATTRIBUTE_KEY, identifier.toString());
        }
        return span;
    }
}
