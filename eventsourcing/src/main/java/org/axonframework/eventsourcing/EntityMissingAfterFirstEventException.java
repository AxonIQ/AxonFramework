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
 * Exception thrown by an {@link EventSourcedEntityFactory} that accepts the first event of an entity, but does not
 * create the entity from it.
 * <p>
 * While an entity is sourced, its state is {@code null} until its first event. The
 * {@link EventSourcedEntityFactory#create(Object, EventMessage, ProcessingContext)} call for that first event, made
 * during {@link EventSourcingRepository#load(Object, ProcessingContext)} or
 * {@link EventSourcingRepository#loadOrCreate(Object, ProcessingContext)}, is where the entity comes into existence. A
 * factory that accepts the event but returns {@code null} means the entity could never be created from its own
 * creating event, so loading it fails with this exception instead of leaving the entity absent.
 * <p>
 * The {@link org.axonframework.eventsourcing.annotation.reflection.AnnotationBasedEventSourcedEntityFactory} throws
 * this exception when an {@link org.axonframework.eventsourcing.annotation.reflection.EntityCreator} invoked with the
 * first event returns {@code null}. It does not throw it for an entity that declares {@code static} event sourcing
 * handlers: such a handler may create the entity from the event instead, or deliberately leave it absent.
 * <p>
 * To resolve this exception, make sure the entity creator returns an entity for the event it accepts, or let a
 * {@code static} event sourcing handler create the entity, for example:
 * <pre>{@code
 * @EventSourcingHandler
 * static Account on(AccountOpened event, @Nullable Account state) {
 *     return new Account(event.accountId());
 * }
 * }</pre>
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
                + "Adjust your EventSourcedEntityFactory to always return a non-null entity when an event message is present.").formatted(
                identifier));
    }
}
