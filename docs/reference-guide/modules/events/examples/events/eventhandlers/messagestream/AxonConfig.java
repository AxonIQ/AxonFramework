package events.eventhandlers.messagestream;

import java.util.concurrent.CompletableFuture;

// tag::event-handler-messagestream-return[]
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.eventhandling.SimpleEventHandlingComponent;
import org.axonframework.messaging.eventhandling.configuration.EventHandlingComponentsConfigurer;

public class AxonConfig {

    // omitted event processing configurer methods...

    private EventHandlingComponentsConfigurer.AdditionalComponentPhase configureHandlingComponent(
            EventHandlingComponentsConfigurer.RequiredComponentPhase componentConfigurer
    ) {
        return componentConfigurer.declarative("order-handler", c -> {
            SimpleEventHandlingComponent eventHandlingComponent = SimpleEventHandlingComponent.create("order-handler");
            eventHandlingComponent.subscribe(
                    new QualifiedName("OrderPlaced"),
                    (event, context) -> {
                        OrderPlacedEvent eventPayload = event.payloadAs(OrderPlacedEvent.class);
                        // process events
                        return MessageStream.empty();
                    }
            );
            eventHandlingComponent.subscribe(
                    new QualifiedName("OrderDeclined"),
                    (event, context) -> {
                        OrderDeclinedEvent eventPayload = event.payloadAs(OrderDeclinedEvent.class);
                        AsyncService asyncService = context.component(AsyncService.class);
                        return MessageStream.fromFuture(
                                asyncService.processOrderDeclined(eventPayload)
                                            .thenApply(r -> (Message) null)
                        ).ignoreEntries();
                    }
            );
            return eventHandlingComponent;
        });
    }
}
// end::event-handler-messagestream-return[]

record OrderPlacedEvent(String orderId) {

}

record OrderDeclinedEvent(String orderId, String reason) {

}

interface AsyncService {

    CompletableFuture<Void> processOrderDeclined(OrderDeclinedEvent event);
}
