package events.eventprocessors.streaming.sequencingpolicy.declarative;

// tag::declarative-sequencing-policy-config[]
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.eventhandling.SimpleEventHandlingComponent;
import org.axonframework.messaging.eventhandling.configuration.EventHandlingComponentsConfigurer;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorsConfigurer;
import org.axonframework.messaging.core.sequencing.PropertySequencingPolicy;

public class AxonConfig {

    public void configureEventProcessing(MessagingConfigurer configurer) {
        configurer.eventProcessing(eventConfigurer -> eventConfigurer.pooledStreaming(
                this::configurePooledStreamingProcessor
        ));
    }

    private PooledStreamingEventProcessorsConfigurer configurePooledStreamingProcessor(
            PooledStreamingEventProcessorsConfigurer pooledStreamingConfigurer
    ) {
        return pooledStreamingConfigurer.processor(
                "example-processor",
                config -> config.eventHandlingComponents(this::configureHandlingComponent).notCustomized()
        );
    }

    private EventHandlingComponentsConfigurer.AdditionalComponentPhase configureHandlingComponent(
            EventHandlingComponentsConfigurer.RequiredComponentPhase componentConfigurer
    ) {
        return componentConfigurer.declarative("example-component", c -> {
            PropertySequencingPolicy<BaseEvent, Object> policy =
                    new PropertySequencingPolicy<>(BaseEvent.class, "identifier");

            SimpleEventHandlingComponent eventHandlingComponent = SimpleEventHandlingComponent.create("example-component", policy);
            eventHandlingComponent.subscribe(
                    new QualifiedName("test-event"),
                    (event, context) -> {
                        // process events
                        return MessageStream.empty();
                    }
                    );
            return eventHandlingComponent;
        });
    }
}
// end::declarative-sequencing-policy-config[]

class BaseEvent {

    private final String identifier;

    BaseEvent(String identifier) {
        this.identifier = identifier;
    }

    public String getIdentifier() {
        return identifier;
    }
}
