package events.eventprocessors.streaming.threadpool;

// tag::thread-pool-config-api[]
import org.axonframework.common.AxonThreadFactory;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.eventhandling.configuration.EventHandlingComponentsConfigurer;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorsConfigurer;

import java.util.concurrent.Executors;

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
                config -> config.eventHandlingComponents(this::configureHandlingComponent).customized(
                        (c, psepConfig) -> psepConfig.coordinatorExecutor(Executors.newScheduledThreadPool(
                                                             1, new AxonThreadFactory("Coordinator - example-processor")
                                                     ))
                                                     .workerExecutor(Executors.newScheduledThreadPool(
                                                             16, new AxonThreadFactory("Worker - example-processor")
                                                     ))
                )
        );
    }

    private EventHandlingComponentsConfigurer.AdditionalComponentPhase configureHandlingComponent(
            EventHandlingComponentsConfigurer.RequiredComponentPhase componentConfigurer
    ) {
        return componentConfigurer.autodetected("example-component", c -> new AnnotatedEventHandlingClass());
    }
}
// end::thread-pool-config-api[]

class AnnotatedEventHandlingClass {

    @org.axonframework.messaging.eventhandling.annotation.EventHandler
    void on(ExampleEvent event) {
        // handle event
    }
}

record ExampleEvent(String id) {

}
