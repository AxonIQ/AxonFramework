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
 * Exception thrown by the {@link EventSourcedEntityFactory} when the entity returned by
 * {@link EventSourcedEntityFactory#create(Object, EventMessage, ProcessingContext)} is {@code null} when calling it with a non-null
 * {@code firstEventMessage} during the {@link EventSourcingRepository#load(Object, ProcessingContext)} or
 * {@link EventSourcingRepository#loadOrCreate(Object, ProcessingContext)}.
 * <p>
 * Returning a {@code null} entity in this case indicates that the factory is incapable of creating an entity when
 * provided an {@link EventMessage}, which violates the contract of the {@link EventSourcedEntityFactory}.
 * <p>
 * Ensure that the factory is capable of creating an entity when provided with an event message.
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
