package events.eventprocessors.subscribing.springboot.namespaceselector;

// tag::event-processor-definition-namespace-selector[]
import org.axonframework.extension.spring.config.EventHandlerSelector;
import org.axonframework.extension.spring.config.EventProcessorDefinition;
import org.axonframework.messaging.eventhandling.EventBus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EventProcessorConfig {

    @Bean
    public EventProcessorDefinition exampleProcessorDefinition(EventBus eventBus) {
        return EventProcessorDefinition.subscribing("example-processor")
                                       .assigningHandlers(EventHandlerSelector.matchesNamespaceOnType(
                                               "orders"
                                       ))
                                       .customized(config -> config
                                               .eventSource(eventBus)
                                       );
    }
}
// end::event-processor-definition-namespace-selector[]
