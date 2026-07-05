package events.eventhandlers.declarative;

// tag::declarative-configuration-api[]
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.eventhandling.SimpleEventHandlingComponent;
import org.axonframework.messaging.eventhandling.configuration.EventHandlingComponentsConfigurer;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorsConfigurer;

public class AxonConfig {

    public void configureEventProcessing(MessagingConfigurer configurer) {
        configurer.eventProcessing(eventConfigurer -> eventConfigurer.pooledStreaming(
                this::configurePooledStreamingProcessor
        ));
    }

    private PooledStreamingEventProcessorsConfigurer configurePooledStreamingProcessor(
            PooledStreamingEventProcessorsConfigurer pooledStreamingConfigurer
    ) {
        return pooledStreamingConfigurer.processor(
                "my-processor",
                config -> config.eventHandlingComponents(this::configureHandlingComponent).notCustomized()
        );
    }

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
            return eventHandlingComponent;
        });
    }
}
// end::declarative-configuration-api[]

record OrderPlacedEvent(String orderId, String customerId) {

}
