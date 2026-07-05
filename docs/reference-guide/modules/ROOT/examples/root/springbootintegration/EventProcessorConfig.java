package root.springbootintegration;

// tag::order-processor-definition[]
import org.axonframework.extension.spring.config.EventProcessorDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EventProcessorConfig {

    @Bean
    public EventProcessorDefinition orderProcessor() {
        return EventProcessorDefinition.pooledStreaming("order-processor")
                .assigningHandlers(descriptor ->
                    descriptor.beanName().startsWith("order"))
                .customized(config -> config
                    .initialSegmentCount(4)
                    .batchSize(100));
    }
}
// end::order-processor-definition[]
