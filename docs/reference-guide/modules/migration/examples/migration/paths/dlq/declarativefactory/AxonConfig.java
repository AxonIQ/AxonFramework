package migration.paths.dlq.declarativefactory;

// tag::declarative-dlq-factory[]
import io.axoniq.framework.messaging.deadletter.InMemorySequencedDeadLetterQueue;
import io.axoniq.framework.messaging.eventhandling.deadletter.DeadLetterQueueConfiguration;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.SimpleEventHandlingComponent;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorModule;

import java.util.List;

public class AxonConfig {

    private final EventHandlingComponent myHandlerComponent = SimpleEventHandlingComponent.create("myHandler");

    public void configureDeadLetterQueue(MessagingConfigurer configurer) {
        var dlqEnabledGroups = List.of(/*...*/);
        configurer.eventProcessing(ep -> ep.pooledStreaming(ps -> ps
                .processor(
                        EventProcessorModule
                                .pooledStreaming("my-processor")
                                .eventHandlingComponents(components -> components
                                        .declarative("myHandler", cfg -> myHandlerComponent))
                                .customized((cfg, processorConfig) -> processorConfig
                                        .extend(DeadLetterQueueConfiguration.class,
                                                () -> new DeadLetterQueueConfiguration()
                                                        .enabled()
                                                        .factory((name, config) -> {
                                                            if (dlqEnabledGroups.contains(name)) {
                                                                return InMemorySequencedDeadLetterQueue
                                                                        .<EventMessage>builder()
                                                                        .maxSequences(256)
                                                                        .maxSequenceSize(256)
                                                                        .build();
                                                            } else {
                                                                return null;
                                                            }
                                                        })))
                )
        ));
    }
}
// end::declarative-dlq-factory[]
