package events.eventprocessors.streaming.replay.blocking;

// tag::replay-blocking-event-handling-component[]
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.SimpleEventHandlingComponent;
import org.axonframework.messaging.eventhandling.configuration.EventHandlingComponentsConfigurer;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorsConfigurer;
import org.axonframework.messaging.eventhandling.replay.ReplayBlockingEventHandlingComponent;

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
                "example-processor",
                config -> config.eventHandlingComponents(this::configureHandlingComponent).notCustomized()
        );
    }

    private EventHandlingComponentsConfigurer.AdditionalComponentPhase configureHandlingComponent(
            EventHandlingComponentsConfigurer.RequiredComponentPhase componentConfigurer
    ) {
        return componentConfigurer.declarative("example-component", c -> {
            SimpleEventHandlingComponent delegate = SimpleEventHandlingComponent.create("example-component");
            delegate.subscribe(
                    new QualifiedName("test-event"),
                    (event, context) -> {
                        // process events
                        return MessageStream.empty();
                    }
            );
            EventHandlingComponent eventHandlingComponent = new ReplayBlockingEventHandlingComponent<>(delegate);
            return eventHandlingComponent;
        });
    }
}
// end::replay-blocking-event-handling-component[]
