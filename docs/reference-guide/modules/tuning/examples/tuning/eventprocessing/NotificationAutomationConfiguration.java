package tuning.eventprocessing;

import org.axonframework.extension.spring.config.EventProcessorDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// tag::notification-processor-definition[]
@Configuration
public class NotificationAutomationConfiguration {

    @Bean
    EventProcessorDefinition notificationProcessor() {
        return EventProcessorDefinition
                .pooledStreaming("notification-processor")
                .assigningHandlers(descriptor -> descriptor.beanName().endsWith("Notification")
                        && descriptor.beanType().getPackageName().endsWith("automation"))
                .customized(config -> config
                        .initialSegmentCount(4)
                        .batchSize(100)
                );
    }
}
// end::notification-processor-definition[]
