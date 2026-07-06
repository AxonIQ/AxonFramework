package migration.paths.messages;

import org.axonframework.messaging.eventhandling.annotation.Event;

// tag::explicit-message-type[]
@Event(name = "MyEvent", version = "1.0")
public class MyEvent {
}
// end::explicit-message-type[]
