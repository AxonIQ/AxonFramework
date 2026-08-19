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

package org.axonframework.modelling.entity;

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.modelling.repository.EntityNotFoundException;

/**
 * Exception indicating that an instance command handler was invoked for an entity that does not exist.
 * <p>
 * If this command is valid for the creation of an entity, as well as instance commands, a creational command can be
 * defined for the same {@link CommandMessage#type()}.
 * <p>
 * This is a specialization of {@link EntityNotFoundException}: no business identifier is available at this level, so
 * {@link EntityNotFoundException#identifier()} returns the {@link CommandMessage#type()} of the command being handled
 * instead.
 *
 * @author Mitchell Herrijgers
 * @since 5.0.0
 */
public class EntityMissingForInstanceCommandHandlerException extends EntityNotFoundException {

    /**
     * Creates a new exception with the given {@code command}.
     *
     * @param command The {@link CommandMessage} that was handled.
     */
    public EntityMissingForInstanceCommandHandlerException(CommandMessage command) {
        super(command.type(), String.format(
                "Entity was missing for instance command handler for command [%s]",
                command.type()
        ));
    }
}
