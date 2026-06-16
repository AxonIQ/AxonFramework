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

package org.axonframework.integrationtests.testsuite.administration;

import org.axonframework.integrationtests.testsuite.administration.commands.AssignTaskCommand;
import org.axonframework.integrationtests.testsuite.administration.commands.ChangeEmailAddress;
import org.axonframework.integrationtests.testsuite.administration.commands.CompleteTaskCommand;
import org.axonframework.integrationtests.testsuite.administration.commands.GiveRaise;
import org.axonframework.integrationtests.testsuite.administration.commands.PersonCommand;
import org.axonframework.integrationtests.testsuite.administration.common.PersonIdentifier;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.modelling.EntityIdResolutionException;
import org.axonframework.modelling.EntityIdResolver;
import org.jspecify.annotations.NonNull;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

class PersonIdentifierEntityIdResolver implements EntityIdResolver<PersonIdentifier> {

    @Override
    public PersonIdentifier resolve(
            @NonNull Message message,
            @NonNull ProcessingContext context
    ) throws EntityIdResolutionException {
        List<Class<? extends PersonCommand>> personCommandTypes = List.of(
                AssignTaskCommand.class,
                ChangeEmailAddress.class,
                CompleteTaskCommand.class,
                GiveRaise.class
        );
        var clazz = personCommandTypes.stream()
                                      .filter(type -> type.getName().equals(message.type().name()))
                                      .findFirst()
                                      .orElseThrow(() -> new EntityIdResolutionException(
                                              message.payloadType(), Collections.emptyList()
                                      ));
        return Objects.requireNonNull(message.payloadAs(clazz)).identifier();
    }
}