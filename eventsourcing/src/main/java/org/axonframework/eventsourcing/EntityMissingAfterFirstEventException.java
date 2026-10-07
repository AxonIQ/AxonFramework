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

package org.axonframework.eventsourcing;

import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;

/**
 * Exception thrown by the {@link EventSourcedEntityFactory} when an entity creator accepts the {@code firstEventMessage}
 * passed to {@link EventSourcedEntityFactory#create(Object, EventMessage, ProcessingContext)}, but returns {@code null}
 * instead of an entity, during {@link EventSourcingRepository#load(Object, ProcessingContext)} or
 * {@link EventSourcingRepository#loadOrCreate(Object, ProcessingContext)}.
 * <p>
 * A factory that cannot create the entity from a given first event may return {@code null}, deferring creation to a
 * {@code static} event sourcing handler. A creator that does accept the event, however, is expected to create the
 * entity from it: returning {@code null} there means the entity could never be created from its own creating event.
 * <p>
 * Ensure that the entity creator returns an entity for the event it accepts, or let a {@code static} event sourcing
 * handler decide whether to create the entity instead.
 *
 * @author Mitchell Herrijgers
 * @since 5.0.0
 */
public class EntityMissingAfterFirstEventException extends RuntimeException {

    /**
     * Constructs the exception with the given {@code identifier}.
     *
     * @param identifier The identifier of the entity that was attempted to be loaded or created.
     */
    public EntityMissingAfterFirstEventException(Object identifier) {
        super(("The EventSourcedEntityFactory returned a null entity while the first event message was non-null for identifier: [%s]. "
                + "Adjust your EventSourcedEntityFactory to always return a non-null entity when an event message is present.").formatted(
                identifier));
    }
}
