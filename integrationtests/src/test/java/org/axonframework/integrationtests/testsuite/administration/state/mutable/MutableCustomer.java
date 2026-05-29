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

package org.axonframework.integrationtests.testsuite.administration.state.mutable;

import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.integrationtests.testsuite.administration.commands.CreateCustomer;
import org.axonframework.integrationtests.testsuite.administration.events.CustomerCreated;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;

public class MutableCustomer extends MutablePerson {

    @CommandHandler
    public static void handle(CreateCustomer command, EventAppender appender) {
        appender.append(new CustomerCreated(command.identifier(),
                                            command.emailAddress()));
    }

    @EventSourcingHandler
    public void on(CustomerCreated event) {
        this.identifier = event.identifier();
        this.emailAddress = event.emailAddress();
    }
}
