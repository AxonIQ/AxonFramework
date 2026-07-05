package events.eventhandlers.springboot;

import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.springframework.stereotype.Component;

// tag::spring-boot-event-handling-component[]
@Component
public class AnnotatedEventHandlingComponent {

    @EventHandler
    public void on(SomeEvent event) {
        // ...
    }
}
// end::spring-boot-event-handling-component[]

record SomeEvent() {

}
