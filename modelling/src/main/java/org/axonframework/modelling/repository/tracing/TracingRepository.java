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

package org.axonframework.modelling.repository.tracing;

import org.axonframework.messaging.tracing.Span;
import org.axonframework.messaging.tracing.SpanFactory;
import org.axonframework.messaging.tracing.attributes.EntityIdSpanAttributesProvider;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.modelling.repository.ManagedEntity;
import org.axonframework.modelling.repository.Repository;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Delegating {@link Repository} decorator that opens internal tracing spans around the repository lifecycle operations
 * ({@code load}, {@code loadOrCreate}, {@code persist}, {@code attach}).
 * <p>
 * Each span carries the entity type name as its trailing element ({@code "Repository.load Booking"}) and an
 * {@link #ENTITY_TYPE_KEY} attribute. The repository identifier - when known - is recorded under
 * {@link EntityIdSpanAttributesProvider#DEFAULT_ATTRIBUTE_KEY}.
 * <p>
 * This decorator is registered by
 * {@link org.axonframework.modelling.tracing.configuration.ModellingTracingConfigurationEnhancer};
 * it is never instantiated directly by
 * applications.
 *
 * @param <ID> the type of the identifier
 * @param <E>  the type of the entity
 * @author Mateusz Nowak
 * @author Mitchell Herrijgers
 * @since 5.3.0
 */
@Internal
public final class TracingRepository<ID, E> implements Repository.LifecycleManagement<ID, E> {

    /** Prefix for the repository-load span ({@code "Repository.load <EntityType>"}). */
    private static final String LOAD_SPAN = "Repository.load";

    /** Prefix for the repository-load-or-create span. */
    private static final String LOAD_OR_CREATE_SPAN = "Repository.loadOrCreate";

    /** Prefix for the repository-persist span. */
    private static final String PERSIST_SPAN = "Repository.persist";

    /** Prefix for the repository-attach span. */
    private static final String ATTACH_SPAN = "Repository.attach";

    /** Attribute key for the entity type. */
    private static final String ENTITY_TYPE_KEY = "axoniq.entity.type";

    private final Repository.LifecycleManagement<ID, E> delegate;
    private final SpanFactory spanFactory;

    /**
     * Initializes a tracing {@link Repository} wrapping the given {@code delegate}, obtaining spans from the given
     * {@code spanFactory}.
     *
     * @param delegate    the repository to delegate to
     * @param spanFactory the factory producing the tracing spans
     */
    public TracingRepository(Repository.LifecycleManagement<ID, E> delegate, SpanFactory spanFactory) {
        this.delegate = Objects.requireNonNull(delegate, "delegate may not be null");
        this.spanFactory = Objects.requireNonNull(spanFactory, "spanFactory may not be null");
    }

    @Override
    public Class<E> entityType() {
        return delegate.entityType();
    }

    @Override
    public Class<ID> idType() {
        return delegate.idType();
    }

    @Override
    public CompletableFuture<ManagedEntity<ID, E>> load(ID identifier, ProcessingContext processingContext) {
        return openSpan(LOAD_SPAN, identifier, processingContext)
                .branchAsync(processingContext, context -> delegate.load(identifier, context));
    }

    @Override
    public CompletableFuture<ManagedEntity<ID, E>> loadOrCreate(ID identifier, ProcessingContext processingContext) {
        return openSpan(LOAD_OR_CREATE_SPAN, identifier, processingContext)
                .branchAsync(processingContext, context -> delegate.loadOrCreate(identifier, context));
    }

    @Override
    public ManagedEntity<ID, E> persist(ID identifier, E entity, ProcessingContext processingContext) {
        return openSpan(PERSIST_SPAN, identifier, processingContext)
                .branch(processingContext, context -> delegate.persist(identifier, entity, context));
    }

    @Override
    public ManagedEntity<ID, E> attach(ManagedEntity<ID, E> entity, ProcessingContext processingContext) {
        return openSpan(ATTACH_SPAN, entity.identifier(), processingContext)
                .branch(processingContext, context -> delegate.attach(entity, context));
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeWrapperOf(delegate);
        descriptor.describeProperty("spanFactory", spanFactory);
    }

    private Span openSpan(String prefix, ID identifier, ProcessingContext context) {
        String entityTypeName = delegate.entityType().getSimpleName();
        Span span = spanFactory.createInternalSpan(prefix + " " + entityTypeName, context)
                               .addAttribute(ENTITY_TYPE_KEY, entityTypeName);
        if (identifier != null) {
            span.addAttribute(EntityIdSpanAttributesProvider.DEFAULT_ATTRIBUTE_KEY, identifier.toString());
        }
        return span;
    }

}
