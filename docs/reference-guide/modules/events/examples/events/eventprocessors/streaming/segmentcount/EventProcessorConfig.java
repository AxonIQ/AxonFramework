package events.eventprocessors.streaming.segmentcount;

// tag::segment-count-spring-boot[]
import org.axonframework.extension.spring.config.EventProcessorDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EventProcessorConfig {

    @Bean
    public EventProcessorDefinition exampleProcessorDefinition() {
        return EventProcessorDefinition.pooledStreaming("example-processor")
                .assigningHandlers(descriptor ->
                    descriptor.beanType().getPackageName()
                              .equals("com.example.eventhandlers"))
                .customized(config -> config
                    .initialSegmentCount(32));
    }
}
// end::segment-count-spring-boot[]
