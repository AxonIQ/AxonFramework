package migration.paths.sequencingpolicies.declarative;

// tag::declarative-sequencing-policy-config[]
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.core.sequencing.PropertySequencingPolicy;
import org.axonframework.messaging.eventhandling.SimpleEventHandlingComponent;

public class AxonConfig {

    public void configureEventProcessing(MessagingConfigurer configurer) {
        configurer.eventProcessing(eventConfigurer ->
                eventConfigurer.pooledStreaming(pooledStreamingConfigurer ->
                        pooledStreamingConfigurer.processor("order-processor", customizer ->
                                        customizer.eventHandlingComponents(ehc ->
                                                        ehc.declarative("orders", configuration ->
                                                                SimpleEventHandlingComponent.create("orders",
                                                                        new PropertySequencingPolicy<>(Order.class, "customerId")
                                                                )
                                                        )
                                        ).notCustomized()
                        )
                )
        );
    }
}
// end::declarative-sequencing-policy-config[]

record Order(String orderId, String customerId) {

}
