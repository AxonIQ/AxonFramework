package tuning.eventprocessing;

import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.SimpleEventHandlingComponent;

public class PooledStreamingProcessorConfiguration {

    private final EventHandlingComponent orderProjection = SimpleEventHandlingComponent.create("order-projection");

    // tag::configure-pooled-streaming[]
    public void configureEventProcessing(MessagingConfigurer configurer) {

        configurer.eventProcessing(eventProcessing -> eventProcessing
                               .pooledStreaming(pooled -> pooled
                                      .defaults((configuration, processorConfig) -> processorConfig
                                               .initialSegmentCount(8)
                                               .batchSize(50)
                                               .tokenClaimInterval(5000)
                                               .claimExtensionThreshold(5000))
                                       .defaultProcessor("order-processor", components ->
                                               components.declarative("OrderUpdated", cfg -> orderProjection))));
    }
    // end::configure-pooled-streaming[]
}
