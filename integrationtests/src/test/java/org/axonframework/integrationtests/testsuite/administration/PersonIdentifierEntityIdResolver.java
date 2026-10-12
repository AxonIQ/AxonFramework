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
import org.axonframework.integrationtests.testsuite.administration.commands.GrantCertificationCommand;
import org.axonframework.integrationtests.testsuite.administration.commands.PersonCommand;
import org.axonframework.integrationtests.testsuite.administration.commands.RevokeCertificationCommand;
import org.axonframework.integrationtests.testsuite.administration.commands.SuspendEmployeeCommand;
import org.axonframework.integrationtests.testsuite.administration.common.PersonIdentifier;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.modelling.EntityIdResolutionException;
import org.axonframework.modelling.EntityIdResolver;
import org.jspecify.annotations.NonNull;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

class PersonIdentifierEntityIdResolver implements EntityIdResolver<PersonIdentifier> {

    private static final Map<String, Class<? extends PersonCommand>> COMMAND_TYPES_BY_NAME = Map.of(
            AssignTaskCommand.class.getName(), AssignTaskCommand.class,
            ChangeEmailAddress.class.getName(), ChangeEmailAddress.class,
            CompleteTaskCommand.class.getName(), CompleteTaskCommand.class,
            GiveRaise.class.getName(), GiveRaise.class,
            GrantCertificationCommand.class.getName(), GrantCertificationCommand.class,
            RevokeCertificationCommand.class.getName(), RevokeCertificationCommand.class,
            SuspendEmployeeCommand.COMMAND_NAME, SuspendEmployeeCommand.class
    );

    @Override
    public PersonIdentifier resolve(
            @NonNull Message message,
            @NonNull ProcessingContext context
    ) throws EntityIdResolutionException {
        Class<? extends PersonCommand> clazz = COMMAND_TYPES_BY_NAME.get(message.type().name());
        if (clazz == null) {
            throw new EntityIdResolutionException(message.payloadType(), Collections.emptyList());
        }
        return Objects.requireNonNull(message.payloadAs(clazz)).identifier();
    }
}