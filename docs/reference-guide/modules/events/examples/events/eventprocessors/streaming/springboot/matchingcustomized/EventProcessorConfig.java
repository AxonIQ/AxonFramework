package events.eventprocessors.streaming.springboot.matchingcustomized;

// tag::event-processor-definition-matching-customized[]
import org.axonframework.extension.spring.config.EventProcessorDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EventProcessorConfig {

    @Bean
    public EventProcessorDefinition exampleProcessorDefinition() {
        return EventProcessorDefinition.pooledStreamingMatching("orders")
                                       .customized(config -> config
                                               .initialSegmentCount(4)
                                               .batchSize(100)
                                               .claimExtensionThreshold(5000)
                                               .tokenClaimInterval(5000)
                                       );
    }
}
// end::event-processor-definition-matching-customized[]
