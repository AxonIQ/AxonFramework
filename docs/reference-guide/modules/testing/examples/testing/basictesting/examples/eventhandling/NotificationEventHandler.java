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
package testing.basictesting.examples.eventhandling;

import testing.basictesting.fixtures.AccountCreatedEvent;
import testing.basictesting.fixtures.MoneyWithdrawnEvent;
import testing.basictesting.fixtures.SendEmailCommand;

import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;

/**
 * Event handling component referenced by, but not shown alongside, the tagged example on the documentation page.
 */
class NotificationEventHandler {

    @EventHandler
    void on(AccountCreatedEvent event, CommandGateway commandGateway) {
        commandGateway.send(new SendEmailCommand("user@example.com", "Your account has been created"));
    }

    @EventHandler
    void on(MoneyWithdrawnEvent event, CommandGateway commandGateway) {
        commandGateway.send(new SendEmailCommand("user@example.com", "Low balance alert"));
    }
}
