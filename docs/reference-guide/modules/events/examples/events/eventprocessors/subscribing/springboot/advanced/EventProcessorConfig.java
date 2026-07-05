package events.eventprocessors.subscribing.springboot.advanced;

// tag::event-processor-definition-advanced[]
import org.axonframework.extension.spring.config.EventProcessorDefinition;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.eventhandling.EventBus;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.processing.errorhandling.PropagatingErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EventProcessorConfig {

    @Bean
    public EventProcessorDefinition advancedProcessorDefinition(
            EventBus eventBus,
            MessageHandlerInterceptor<? super EventMessage> interceptor) {
        return EventProcessorDefinition.subscribing("advanced-processor")
                .assigningHandlers(descriptor ->
                    descriptor.beanName().startsWith("advanced"))
                .customized(config -> config
                    .eventSource(eventBus)
                    .withInterceptor(interceptor)
                    .errorHandler(PropagatingErrorHandler.INSTANCE));
    }
}
// end::event-processor-definition-advanced[]
