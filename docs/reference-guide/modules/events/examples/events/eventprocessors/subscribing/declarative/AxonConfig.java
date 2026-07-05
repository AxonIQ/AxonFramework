package events.eventprocessors.subscribing.declarative;

// tag::declarative-subscribing-processor[]
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.eventhandling.EventBus;
import org.axonframework.messaging.eventhandling.configuration.EventHandlingComponentsConfigurer;
import org.axonframework.messaging.eventhandling.processing.subscribing.SubscribingEventProcessorsConfigurer;

public class AxonConfig {

    public void configureEventProcessing(MessagingConfigurer configurer) {
        configurer.eventProcessing(eventConfigurer -> eventConfigurer.subscribing(
                this::configureSubscribingProcessor
        ));
    }

    private SubscribingEventProcessorsConfigurer configureSubscribingProcessor(
            SubscribingEventProcessorsConfigurer subscribingConfigurer
    ) {
        return subscribingConfigurer.processor(
                "example-processor",
                config -> config.eventHandlingComponents(this::configureHandlingComponent)
                                .customized((c, subscribingConfig) -> subscribingConfig.eventSource(
                                        c.getComponent(EventBus.class)
                                ))
        );
    }

    private EventHandlingComponentsConfigurer.AdditionalComponentPhase configureHandlingComponent(
            EventHandlingComponentsConfigurer.RequiredComponentPhase componentConfigurer
    ) {
        return componentConfigurer.autodetected("example-component", c -> new AnnotatedEventHandlingClass());
    }
}
// end::declarative-subscribing-processor[]

class AnnotatedEventHandlingClass {

    @org.axonframework.messaging.eventhandling.annotation.EventHandler
    void on(ExampleEvent event) {
        // handle event
    }
}

record ExampleEvent(String id) {

}
