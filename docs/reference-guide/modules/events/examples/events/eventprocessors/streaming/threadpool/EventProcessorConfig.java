package events.eventprocessors.streaming.threadpool;

// tag::thread-pool-spring-boot[]
import org.axonframework.common.AxonThreadFactory;
import org.axonframework.extension.spring.config.EventProcessorDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;

@Configuration
public class EventProcessorConfig {

    @Bean
    public EventProcessorDefinition exampleProcessorDefinition() {
        return EventProcessorDefinition.pooledStreaming("example-processor")
                .assigningHandlers(descriptor ->
                    descriptor.beanType().getPackageName()
                              .startsWith("com.example"))
                .customized(config -> config
                    .coordinatorExecutor(Executors.newScheduledThreadPool(
                        1, new AxonThreadFactory("Coordinator - example-processor")
                    ))
                    .workerExecutor(Executors.newScheduledThreadPool(
                        16, new AxonThreadFactory("Worker - example-processor")
                    )));
    }
}
// end::thread-pool-spring-boot[]
