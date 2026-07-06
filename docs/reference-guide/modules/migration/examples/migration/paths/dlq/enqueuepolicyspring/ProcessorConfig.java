package migration.paths.dlq.enqueuepolicyspring;

// tag::spring-enqueue-policy[]
import io.axoniq.framework.messaging.deadletter.DeadLetter;
import io.axoniq.framework.messaging.deadletter.Decisions;
import io.axoniq.framework.messaging.deadletter.EnqueueDecision;
import io.axoniq.framework.messaging.deadletter.EnqueuePolicy;
import io.axoniq.framework.messaging.eventhandling.deadletter.DeadLetterQueueConfiguration;
import org.axonframework.extension.spring.config.EventProcessorDefinition;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ProcessorConfig {

    @Bean
    EventProcessorDefinition myProcessor() {
        return EventProcessorDefinition.pooledStreaming("my-processor")
                .assigningHandlers(descriptor -> descriptor.beanName().startsWith("my"))
                .customized(config -> config
                        .extend(DeadLetterQueueConfiguration.class,
                                () -> new DeadLetterQueueConfiguration()
                                        .enabled()
                                        .enqueuePolicy(new CustomEnqueuePolicy())));
    }
}

class CustomEnqueuePolicy implements EnqueuePolicy<EventMessage> {

    @Override
    public EnqueueDecision<EventMessage> decide(DeadLetter<? extends EventMessage> letter, Throwable cause) {
        return Decisions.enqueue(cause);
    }
}
// end::spring-enqueue-policy[]
