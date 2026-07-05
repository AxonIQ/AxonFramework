package commands.infrastructure.repositories.configapi;

// tag::order-entity[]
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;

@EventSourcedEntity
public class Order {

    // Omitted handlers and state for brevity.
}

// end::order-entity[]
