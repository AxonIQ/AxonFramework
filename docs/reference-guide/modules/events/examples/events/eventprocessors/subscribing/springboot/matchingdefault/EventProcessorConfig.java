package events.eventprocessors.subscribing.springboot.matchingdefault;

// tag::event-processor-definition-not-customized[]
import org.axonframework.extension.spring.config.EventProcessorDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EventProcessorConfig {

    @Bean
    public EventProcessorDefinition exampleProcessorDefinition() {
        return EventProcessorDefinition.subscribingMatching("orders")
                                       .notCustomized();
    }
}
// end::event-processor-definition-not-customized[]
