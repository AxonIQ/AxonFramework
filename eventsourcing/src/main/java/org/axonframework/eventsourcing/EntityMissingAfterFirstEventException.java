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
 * Exception thrown by an {@link EventSourcedEntityFactory} that receives the first event of an entity, but cannot
 * create the entity from it.
 * <p>
 * While an entity is sourced, its state is {@code null} until its first event. The
 * {@link EventSourcedEntityFactory#create(Object, EventMessage, ProcessingContext)} call for that first event, made
 * during {@link EventSourcingRepository#load(Object, ProcessingContext)} or
 * {@link EventSourcingRepository#loadOrCreate(Object, ProcessingContext)}, is where the entity normally comes into
 * existence. A factory may return {@code null} for that event to leave creation to the entity's
 * {@link org.axonframework.modelling.EntityEvolver}. A factory that is expected to create the entity from the event,
 * but cannot, throws this exception instead, so loading fails rather than leaving the entity absent.
 * <p>
 * To resolve this exception, make sure the factory returns a non-null entity for every first event it accepts.
 *
 * @author Mitchell Herrijgers
 * @since 5.0.0
 */
public class EntityMissingAfterFirstEventException extends RuntimeException {

    /**
     * Constructs the exception for the entity with the given {@code identifier}.
     *
     * @param identifier the identifier of the entity that was attempted to be loaded or created
     */
    public EntityMissingAfterFirstEventException(Object identifier) {
        super(("The EventSourcedEntityFactory returned a null entity while the first event message was non-null for identifier: [%s]. "
                + "Adjust your EventSourcedEntityFactory to return a non-null entity for every first event it accepts.").formatted(
                identifier));
    }
}
