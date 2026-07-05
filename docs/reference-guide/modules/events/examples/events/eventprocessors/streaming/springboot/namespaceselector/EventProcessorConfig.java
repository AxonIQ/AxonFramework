package events.eventprocessors.streaming.springboot.namespaceselector;

// tag::event-processor-definition-namespace-selector[]
import org.axonframework.extension.spring.config.EventHandlerSelector;
import org.axonframework.extension.spring.config.EventProcessorDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EventProcessorConfig {

    @Bean
    public EventProcessorDefinition exampleProcessorDefinition() {
        return EventProcessorDefinition.pooledStreaming("example-processor")
                                       .assigningHandlers(EventHandlerSelector.matchesNamespaceOnType(
                                               "orders"
                                       ))
                                       .customized(config -> config
                                               .initialSegmentCount(4)
                                               .batchSize(100)
                                               .claimExtensionThreshold(5000)
                                               .tokenClaimInterval(5000)
                                       );
    }
}
// end::event-processor-definition-namespace-selector[]
