package migration.paths.messages;

import org.axonframework.messaging.commandhandling.annotation.Command;

// tag::command-routing-key[]
@Command(routingKey = "someKey")
public class MyCommand {
    private String someKey;
}
// end::command-routing-key[]
