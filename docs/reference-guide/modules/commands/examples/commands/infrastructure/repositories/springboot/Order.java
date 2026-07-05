package commands.infrastructure.repositories.springboot;

// tag::event-sourced-spring[]
import org.axonframework.extension.spring.stereotype.EventSourced;

@EventSourced
public class Order {

    // Omitted handlers and state for brevity.
}
// end::event-sourced-spring[]
