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

package org.axonframework.eventsourcing.handler.tracing;

import org.axonframework.messaging.tracing.Span;
import org.axonframework.messaging.tracing.SpanFactory;
import org.axonframework.messaging.tracing.attributes.EntityIdSpanAttributesProvider;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.eventsourcing.handler.EntityLifecycleHandler;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.modelling.repository.ManagedEntity;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Type-preserving tracing decorator for {@link EntityLifecycleHandler}. Opens an internal sourcing span around
 * {@link #source(Object, ProcessingContext)} - the parent for the {@code SnapshotStore.load} /
 * {@code SnapshotStore.store} spans produced by {@code TracingSnapshotStore}, plus the eventual event-replay spans
 * the framework emits underneath.
 * <p>
 * {@code initialize} and {@code subscribe} are pure pass-throughs (no span); they are not on the hot replay path and
 * adding spans there would only add noise to the trace.
 *
 * @param <I> the entity identifier type
 * @param <E> the entity type
 * @author Mateusz Nowak
 * @since 5.3.0
 */
@Internal
public final class TracingEntityLifecycleHandler<I, E> implements EntityLifecycleHandler<I, E> {

    /** Prefix for the entity-sourcing span ({@code "EntityLifecycleHandler.source <EntityType>"}). */
    public static final String SOURCE_SPAN = "EntityLifecycleHandler.source";

    /** Attribute key for the entity type (same convention as the modelling tracing decorators). */
    static final String ENTITY_TYPE_KEY = "axoniq.entity.type";

    private final EntityLifecycleHandler<I, E> delegate;
    private final SpanFactory spanFactory;
    private final String entityTypeName;

    /**
     * Initializes a tracing {@link EntityLifecycleHandler} wrapping the given {@code delegate}, obtaining spans from
     * the given {@code spanFactory}.
     *
     * @param delegate       the lifecycle handler to delegate to
     * @param spanFactory    the factory producing the tracing spans
     * @param entityTypeName the entity-type name used as the span suffix and {@code axoniq.entity.type} attribute. When
     *                       unknown a generic value such as {@code "entity"} is acceptable; null is not.
     */
    public TracingEntityLifecycleHandler(EntityLifecycleHandler<I, E> delegate,
                                         SpanFactory spanFactory,
                                         String entityTypeName) {
        this.delegate = Objects.requireNonNull(delegate, "delegate may not be null");
        this.spanFactory = Objects.requireNonNull(spanFactory, "spanFactory may not be null");
        this.entityTypeName = Objects.requireNonNull(entityTypeName, "entityTypeName may not be null");
    }

    @Override
    public CompletableFuture<E> source(I identifier, ProcessingContext processingContext) {
        Span span = spanFactory.createInternalSpan(SOURCE_SPAN + " " + entityTypeName, processingContext)
                               .addAttribute(ENTITY_TYPE_KEY, entityTypeName);
        if (identifier != null) {
            span.addAttribute(EntityIdSpanAttributesProvider.DEFAULT_ATTRIBUTE_KEY, identifier.toString());
        }
        return span.branchAsync(processingContext, scoped -> delegate.source(identifier, scoped));
    }

    @Override
    public E initialize(I identifier, ProcessingContext context) {
        return delegate.initialize(identifier, context);
    }

    @Override
    public void subscribe(ManagedEntity<I, E> entity, ProcessingContext context) {
        delegate.subscribe(entity, context);
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeWrapperOf(delegate);
        descriptor.describeProperty("spanFactory", spanFactory);
        descriptor.describeProperty("entityType", entityTypeName);
    }
}
