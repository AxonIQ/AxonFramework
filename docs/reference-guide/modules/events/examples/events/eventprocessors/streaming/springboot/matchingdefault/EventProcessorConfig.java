package events.eventprocessors.streaming.springboot.matchingdefault;

// tag::event-processor-definition-matching-default[]
import org.axonframework.extension.spring.config.EventProcessorDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EventProcessorConfig {

    @Bean
    public EventProcessorDefinition exampleProcessorDefinition() {
        return EventProcessorDefinition.pooledStreamingMatching("orders")
                                       .notCustomized();
    }
}
// end::event-processor-definition-matching-default[]
