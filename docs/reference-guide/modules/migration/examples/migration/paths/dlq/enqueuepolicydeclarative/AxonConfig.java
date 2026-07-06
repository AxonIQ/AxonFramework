package migration.paths.dlq.enqueuepolicydeclarative;

// tag::declarative-enqueue-policy[]
import io.axoniq.framework.messaging.deadletter.DeadLetter;
import io.axoniq.framework.messaging.deadletter.Decisions;
import io.axoniq.framework.messaging.deadletter.EnqueueDecision;
import io.axoniq.framework.messaging.deadletter.EnqueuePolicy;
import io.axoniq.framework.messaging.eventhandling.deadletter.DeadLetterQueueConfiguration;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.EventMessage;
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
                                                () -> new DeadLetterQueueConfiguration()
                                                        .enabled()
                                                        .enqueuePolicy(new CustomEnqueuePolicy()))))
        ));
    }
}

class CustomEnqueuePolicy implements EnqueuePolicy<EventMessage> {

    @Override
    public EnqueueDecision<EventMessage> decide(DeadLetter<? extends EventMessage> letter, Throwable cause) {
        return Decisions.enqueue(cause);
    }
}
// end::declarative-enqueue-policy[]
