package events.eventprocessors.subscribing.springboot.matchingcustomized;

// tag::event-processor-definition-matching-customized[]
import org.axonframework.extension.spring.config.EventProcessorDefinition;
import org.axonframework.messaging.eventhandling.EventBus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EventProcessorConfig {

    @Bean
    public EventProcessorDefinition exampleProcessorDefinition(EventBus eventBus) {
        return EventProcessorDefinition.subscribingMatching("orders")
                                       .customized(config -> config
                                               .eventSource(eventBus)
                                       );
    }
}
// end::event-processor-definition-matching-customized[]
