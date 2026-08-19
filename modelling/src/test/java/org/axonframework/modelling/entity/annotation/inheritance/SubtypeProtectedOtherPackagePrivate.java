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

package org.axonframework.modelling.entity.annotation.inheritance;

import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.modelling.entity.annotation.inheritance.other.OtherPackagePrivate;
import org.axonframework.modelling.entity.domain.todo.commands.CreateTodoItem;

/**
 * Subtype fixture with a {@code protected} override of the package-private handler declared in
 * {@link OtherPackagePrivate}. Used by inheritance tests to verify that the supertype handler is recognized as
 * duplicate.
 */
public class SubtypeProtectedOtherPackagePrivate extends OtherPackagePrivate {

    @CommandHandler
    protected void handle(CreateTodoItem command) {
        this.id = command.id();
        this.handledBy = "subtype";
    }
}
