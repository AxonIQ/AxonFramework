package migration.paths.projectorseventprocessors.springboot;

// tag::spring-event-processor-definition[]
import org.axonframework.extension.spring.config.EventProcessorDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AxonConfig {

    @Bean
    public EventProcessorDefinition myProcessorDefinition() {
        return EventProcessorDefinition.pooledStreaming("my-processor")
                                       .assigningHandlers(
                                               descriptor -> descriptor.beanType().getPackageName()
                                                                       .startsWith("com.my.projectors")
                                       )
                                       .customized(config -> config.initialSegmentCount(8)
                                                                   .batchSize(100));
    }
}
// end::spring-event-processor-definition[]
