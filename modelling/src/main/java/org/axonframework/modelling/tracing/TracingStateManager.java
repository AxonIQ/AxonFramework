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

package org.axonframework.modelling.tracing;

import org.axonframework.messaging.tracing.Span;
import org.axonframework.messaging.tracing.SpanFactory;
import org.axonframework.messaging.tracing.attributes.EntityIdSpanAttributesProvider;
import org.axonframework.common.annotation.Internal;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.modelling.StateManager;
import org.axonframework.modelling.repository.ManagedEntity;
import org.axonframework.modelling.repository.Repository;
import org.axonframework.modelling.repository.tracing.TracingRepository;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Delegating {@link StateManager} decorator that opens internal tracing spans around state-manager load operations.
 * <p>
 * The {@code loadManagedEntity} call produces a single span named {@code StateManager.loadManagedEntity <EntityType>}
 * carrying the entity type name and the entity identifier (under
 * {@link EntityIdSpanAttributesProvider#DEFAULT_ATTRIBUTE_KEY}). The default {@code loadEntity} method
 * ultimately calls {@code loadManagedEntity}, so it is naturally traced too.
 * <p>
 * Repositories {@link #register(Repository) registered} on this state manager are wrapped in a
 * {@link TracingRepository} (when not traced already), so their {@code load} / {@code loadOrCreate} /
 * {@code persist} / {@code attach} operations produce spans as well. This matters for entity modules (e.g.
 * event-sourced entities), which build their {@code Repository} inside the module's own component registry - out of
 * reach of the root registry's {@code Repository} decorator - and then register it on the root {@code StateManager}:
 * this wrap is what puts the {@code Repository.load <EntityType>} span inside the entity-loading trace.
 * <p>
 * This decorator is registered by {@code ModellingTracingConfigurationEnhancer}; it is never instantiated directly by
 * applications.
 *
 * @author Mateusz Nowak
 * @since 5.3.0
 */
@Internal
public final class TracingStateManager implements StateManager {

    /** Prefix for the state-manager-load-managed-entity span ({@code "StateManager.loadManagedEntity <EntityType>"}). */
    public static final String LOAD_MANAGED_ENTITY_SPAN = "StateManager.loadManagedEntity";

    /** Attribute key for the entity type (same convention as {@link TracingRepository}). */
    static final String ENTITY_TYPE_KEY = "axoniq.entity.type";

    private final StateManager delegate;
    private final SpanFactory spanFactory;

    /**
     * Initializes a tracing {@link StateManager} wrapping the given {@code delegate}, obtaining spans from the given
     * {@code spanFactory}.
     *
     * @param delegate    the state manager to delegate to
     * @param spanFactory the factory producing the tracing spans
     */
    public TracingStateManager(StateManager delegate, SpanFactory spanFactory) {
        this.delegate = Objects.requireNonNull(delegate, "delegate may not be null");
        this.spanFactory = Objects.requireNonNull(spanFactory, "spanFactory may not be null");
    }

    /**
     * {@inheritDoc}
     * <p>
     * <b>Contract:</b> the given {@code repository} is assumed to be untraced and is wrapped in a
     * {@link TracingRepository} before registration, unless it already <em>is</em> one (e.g. on re-registration of
     * the same instance, or when registering a component the root registry's decorator already traced). The
     * already-traced check looks at the <em>outermost</em> wrapper only - sound for everything the component
     * registry builds, because the tracing decorators register at near-maximal {@code TRACING_DECORATOR_ORDER} and
     * are therefore always the outermost layer. If you register a hand-built decorator pipeline around an
     * already-traced repository, keep the {@code TracingRepository} as the outermost wrapper - burying it under
     * another decorator makes it undetectable here and results in duplicate {@code Repository.*} spans.
     */
    @Override
    public <ID, T> StateManager register(Repository<ID, T> repository) {
        delegate.register(traced(repository));
        return this;
    }

    private <ID, T> Repository<ID, T> traced(Repository<ID, T> repository) {
        // Entity-module repositories live in the module's own component registry, where the root registry's
        // Repository decorator does not reach - wrapping at registration is what traces them. Repositories that are
        // already traced (re-registrations, root-registered ones the decorator caught) are registered as-is to
        // avoid double spans. The outermost-instanceof check is sound for registry-built components because tracing
        // decorates at near-maximal order (ModellingTracingConfigurationEnhancer#TRACING_DECORATOR_ORDER) and is
        // therefore always the outermost wrapper.
        return repository instanceof Repository.LifecycleManagement<ID, T> lifecycleManagement
                && !(repository instanceof TracingRepository<ID, T>)
                ? new TracingRepository<>(lifecycleManagement, spanFactory)
                : repository;
    }

    @Override
    public <ID, T> CompletableFuture<ManagedEntity<ID, T>> loadManagedEntity(Class<T> type,
                                                                             ID id,
                                                                             ProcessingContext context) {
        Span span = spanFactory.createInternalSpan(
                                       LOAD_MANAGED_ENTITY_SPAN + " " + type.getSimpleName(), context)
                               .addAttribute(ENTITY_TYPE_KEY, type.getSimpleName());
        if (id != null) {
            span.addAttribute(EntityIdSpanAttributesProvider.DEFAULT_ATTRIBUTE_KEY, id.toString());
        }
        return span.branchAsync(context, scoped -> delegate.loadManagedEntity(type, id, scoped));
    }

    @Override
    public Set<Class<?>> registeredEntities() {
        return delegate.registeredEntities();
    }

    @Override
    public Set<Class<?>> registeredIdsFor(Class<?> entityType) {
        return delegate.registeredIdsFor(entityType);
    }

    @Override
    public <ID, T> Repository<ID, T> repository(Class<T> entityType, Class<ID> idType) {
        return delegate.repository(entityType, idType);
    }
}
