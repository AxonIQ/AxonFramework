package migration.paths.dlq.declarativeconfiguration;

// tag::declarative-dlq-configuration[]
import io.axoniq.framework.messaging.eventhandling.deadletter.DeadLetterQueueConfiguration;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.SimpleEventHandlingComponent;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorModule;

public class AxonConfig {

    private final EventHandlingComponent myHandlerComponent = SimpleEventHandlingComponent.create("myHandler");

    public void configureDeadLetterQueue(MessagingConfigurer configurer) {
        configurer.eventProcessing(ep -> ep.pooledStreaming(ps -> ps
                .processor(
                        EventProcessorModule
                                .pooledStreaming("my-processor")
                                .eventHandlingComponents(components -> components
                                        .declarative("myHandler", cfg -> myHandlerComponent))
                                .customized((cfg, processorConfig) -> processorConfig
                                        .extend(DeadLetterQueueConfiguration.class,
                                                () -> new DeadLetterQueueConfiguration().enabled()))
                )
        ));
    }
}
// end::declarative-dlq-configuration[]
