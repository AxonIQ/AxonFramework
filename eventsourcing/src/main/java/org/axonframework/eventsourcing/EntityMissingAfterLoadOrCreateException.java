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
 * {@link EventSourcedEntityFactory#create(Object, EventMessage, ProcessingContext)} is {@code null} when calling it with a {@code null}
 * {@code firstEventMessage} during the {@link EventSourcingRepository#loadOrCreate(Object, ProcessingContext)}.
 * <p>
 * This indicates that the factory is incapable of creating an entity without an event message, as is the case with
 * entities that only have a constructor that takes an event message or its payload. If this is the case, the
 * {@link EventSourcingRepository#load(Object, ProcessingContext)} should be used instead, which will leave the entity
 * as {@code null} if no events are found.
 * <p>
 * If the entity has a constructor that takes no arguments or an identifier, the factory should be adjusted to return a
 * non-null entity when no event message is present.
 *
 * @author Mitchell Herrijgers
 * @since 5.0.0
 */
public class EntityMissingAfterLoadOrCreateException extends RuntimeException {

    /**
     * Constructs the exception with the given {@code identifier}.
     *
     * @param identifier The identifier of the entity that was attempted to be loaded or created.
     */
    public EntityMissingAfterLoadOrCreateException(Object identifier) {
        super(("The EventSourcedEntityFactory returned a null entity while loadOrCreate was called for identifier: [%s]. "
                + "Adjust your EventSourcedEntityFactory to return a non-null entity when no event message is present.").formatted(
                identifier));
    }
}
