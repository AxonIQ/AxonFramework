package events.eventprocessors.streaming.tokenclaim;

// tag::token-claim-spring-boot[]
import org.axonframework.extension.spring.config.EventProcessorDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EventProcessorConfig {

    @Bean
    public EventProcessorDefinition exampleProcessorDefinition() {
        return EventProcessorDefinition.pooledStreaming("example-processor")
                .assigningHandlers(descriptor ->
                    descriptor.beanName().startsWith("example"))
                .customized(config -> config
                    .claimExtensionThreshold(2500)
                    .tokenClaimInterval(7500));
    }
}
// end::token-claim-spring-boot[]
