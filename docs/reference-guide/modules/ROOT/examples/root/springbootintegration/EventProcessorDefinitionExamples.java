package root.springbootintegration;

import org.axonframework.extension.spring.config.EventProcessorDefinition;

class EventProcessorDefinitionExamples {

    void namespaceConvenienceMethods() {
        // tag::namespace-convenience-methods[]
        // Equivalent to pooledStreaming("orders").assigningHandlers(matchesNamespaceOnType("orders"))
        EventProcessorDefinition.pooledStreamingMatching("orders")
                .notCustomized();

        // Equivalent to subscribing("orders").assigningHandlers(matchesNamespaceOnType("orders"))
        EventProcessorDefinition.subscribingMatching("orders")
                .notCustomized();
        // end::namespace-convenience-methods[]
    }

    void configurationOptions() {
        // tag::event-processor-configuration-options[]
        // With custom configuration
        EventProcessorDefinition.pooledStreaming("custom-processor")
            .assigningHandlers(descriptor -> descriptor.beanName().startsWith("custom"))
            .customized(config -> config
                .initialSegmentCount(16)
                .batchSize(100)
                .tokenClaimInterval(5000));

        // With default settings (only handler assignment)
        EventProcessorDefinition.pooledStreaming("default-processor")
            .assigningHandlers(descriptor -> descriptor.beanName().startsWith("default"))
            .notCustomized();
        // end::event-processor-configuration-options[]
    }
}
