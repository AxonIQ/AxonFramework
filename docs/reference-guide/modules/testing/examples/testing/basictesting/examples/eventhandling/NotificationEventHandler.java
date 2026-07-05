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
