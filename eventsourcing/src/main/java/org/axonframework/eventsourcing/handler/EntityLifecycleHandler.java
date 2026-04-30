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

package org.axonframework.eventsourcing.handler;

import org.axonframework.common.annotation.Internal;
import org.axonframework.common.infra.DescribableComponent;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.modelling.repository.ManagedEntity;
import org.jspecify.annotations.Nullable;

/**
 * Defines the lifecycle operations for an event-sourced entity.
 * <p>
 * An {@code EntityLifecycleHandler} is responsible for managing how an entity is:
 * <ul>
 *   <li>initialized when no prior state exists</li>
 *   <li>reconstructed from an underlying event stream</li>
 *   <li>kept up to date through live event subscriptions</li>
 * </ul>
 * <p>
 * Implementations encapsulate the full lifecycle semantics of an entity, including
 * how state is derived from events and how runtime updates are applied.
 * <p>
 * The lifecycle consists of three primary operations:
 * <ul>
 *   <li><b>initialize</b>: creates a new entity instance</li>
 *   <li><b>source</b>: reconstructs an entity from its event history</li>
 *   <li><b>subscribe</b>: subscribes a managed entity to be updated with future events</li>
 * </ul>
 * <p>
 * Implementations may apply optimizations such as snapshotting or partial
 * reconstruction strategies, but these concerns remain internal to the handler.
 *
 * @param <I> the type of the entity identifier
 * @param <E> the type of the entity
 * @author John Hendrikx
 * @since 5.1.0
 */
@Internal
public interface EntityLifecycleHandler<I, E> extends SourcingHandler<I, E>, DescribableComponent {

    /**
     * Creates a new instance of the entity for the given identifier.
     * <p>
     * This operation is used when no prior state exists for the entity.
     * The resulting instance represents the initial state before any events
     * have been applied.
     *
     * @param identifier the entity identifier, cannot be {@code null}
     * @param firstEventMessage optional first event message to initialize the entiy with
     * @param context the processing context, cannot be {@code null}
     * @return a newly initialized entity instance
     */
    E initialize(I identifier, @Nullable EventMessage firstEventMessage, ProcessingContext context);

    /**
     * Subscribes the given managed entity to the event stream so it receives
     * future state changes.
     * <p>
     * After subscription, any newly appended events relevant to the entity are
     * applied to its current state, keeping it synchronized with the event store
     * for the remainder of its lifecycle.
     *
     * @param entity the managed entity to subscribe
     * @param context the processing context
     */
    void subscribe(ManagedEntity<I, E> entity, ProcessingContext context);
}