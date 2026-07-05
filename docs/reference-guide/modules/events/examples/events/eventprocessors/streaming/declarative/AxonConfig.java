package events.eventprocessors.streaming.declarative;

// tag::declarative-pooled-streaming-processor[]
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
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
                "example-processor",
                config -> config.eventHandlingComponents(this::configureHandlingComponent)
                                .notCustomized()
        );
    }

    private EventHandlingComponentsConfigurer.AdditionalComponentPhase configureHandlingComponent(
            EventHandlingComponentsConfigurer.RequiredComponentPhase componentConfigurer
    ) {
        return componentConfigurer.autodetected("example-component", c -> new AnnotatedEventHandlingClass());
    }
}
// end::declarative-pooled-streaming-processor[]

class AnnotatedEventHandlingClass {

    @org.axonframework.messaging.eventhandling.annotation.EventHandler
    void on(ExampleEvent event) {
        // handle event
    }
}

record ExampleEvent(String id) {

}
