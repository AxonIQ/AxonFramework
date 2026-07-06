package migration.understandingarchitectureprinciples.eventversioning;

import org.axonframework.messaging.eventhandling.annotation.Event;

// tag::account-created-event[]
// New Axon 5 approach
@Event(name = "AccountCreatedEvent", version = "2.0")
public class AccountCreatedEvent { }
// end::account-created-event[]
