package events.eventprocessors.streaming.defaults;

// tag::processor-defaults-with-override[]
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.eventhandling.configuration.EventHandlingComponentsConfigurer;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorsConfigurer;
import org.axonframework.messaging.eventstreaming.StreamableEventSource;

import java.time.Duration;

public class AxonConfig {

    public void configureEventProcessing(MessagingConfigurer configurer) {
        configurer.eventProcessing(eventConfigurer -> eventConfigurer.pooledStreaming(
                this::configurePooledStreamingProcessor
        ));
    }

    private PooledStreamingEventProcessorsConfigurer configurePooledStreamingProcessor(
            PooledStreamingEventProcessorsConfigurer pooledStreamingConfigurer
    ) {
        return pooledStreamingConfigurer
                // Set defaults for all pooled streaming processors
                .defaults((config, processorConfig) -> processorConfig
                        .eventSource(config.getComponent(StreamableEventSource.class))
                        .initialSegmentCount(4)
                        .batchSize(100)
                        .claimExtensionThreshold(Duration.ofSeconds(5).toMillis())
                )
                // Configure a specific processor
                .processor(
                        "example-processor",
                        config -> config.eventHandlingComponents(this::configureHandlingComponent)
                                        .customized((c, pooledStreamingConfig) -> pooledStreamingConfig
                                                .initialSegmentCount(8)  // Override default for this processor
                                        )
                );
    }

    private EventHandlingComponentsConfigurer.AdditionalComponentPhase configureHandlingComponent(
            EventHandlingComponentsConfigurer.RequiredComponentPhase componentConfigurer
    ) {
        return componentConfigurer.autodetected("example-component", c -> new AnnotatedEventHandlingClass());
    }
}
// end::processor-defaults-with-override[]

class AnnotatedEventHandlingClass {

    @org.axonframework.messaging.eventhandling.annotation.EventHandler
    void on(ExampleEvent event) {
        // handle event
    }
}

record ExampleEvent(String id) {

}
