package root.springbootintegration;

// The import block below is indented to the depth of the surrounding class body, so
// that indent=0 normalization on the include renders the combined regions flush left.
// tag::namespace-processor-import[]
    import org.axonframework.extension.spring.config.EventHandlerSelector;
    import org.axonframework.extension.spring.config.EventProcessorDefinition;

// end::namespace-processor-import[]
import org.axonframework.messaging.core.annotation.Namespace;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class NamespaceBasedProcessorConfig {

    // tag::namespace-processor[]
    @Bean
    public EventProcessorDefinition ordersProcessor() {
        return EventProcessorDefinition.pooledStreaming("orders")
                .assigningHandlers(EventHandlerSelector.matchesNamespaceOnType("orders"))
                .notCustomized();
    }

    // end::namespace-processor[]
    // tag::namespace-event-handler[]
    @Namespace("orders")
    public class OrderEventHandler {
        // omitted event handlers for brevity
    }
    // end::namespace-event-handler[]
}
