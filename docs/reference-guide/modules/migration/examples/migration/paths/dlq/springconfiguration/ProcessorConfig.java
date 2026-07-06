package migration.paths.dlq.springconfiguration;

// tag::spring-dlq-configuration[]
import io.axoniq.framework.messaging.eventhandling.deadletter.DeadLetterQueueConfiguration;
import org.axonframework.extension.spring.config.EventProcessorDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ProcessorConfig {

    @Bean
    EventProcessorDefinition orderProcessor() {
        return EventProcessorDefinition.pooledStreaming("order-processor")
                .assigningHandlers(descriptor ->
                        descriptor.beanName().startsWith("order"))
                .customized(config -> config
                        .extend(DeadLetterQueueConfiguration.class,
                                () -> new DeadLetterQueueConfiguration()
                                        .enabled()
                                        .cacheMaxSize(2048)));
    }
}
// end::spring-dlq-configuration[]
