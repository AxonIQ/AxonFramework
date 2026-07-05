package events.eventprocessors.index.declarative;

// tag::declarative-subscribing-processor[]
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
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
                "my-processor",
                config -> config.eventHandlingComponents(this::configureHandlingComponent)
                                .notCustomized()
        );
    }

    private EventHandlingComponentsConfigurer.AdditionalComponentPhase configureHandlingComponent(
            EventHandlingComponentsConfigurer.RequiredComponentPhase componentConfigurer
    ) {
        return componentConfigurer.autodetected("my-handler", c -> new MyHandler())
                                  .autodetected("module-handler", c -> new ModuleHandler())
                                  .autodetected("my-other-handler", c -> new MyOtherHandler());
    }
}
// end::declarative-subscribing-processor[]

class MyHandler {

    @org.axonframework.messaging.eventhandling.annotation.EventHandler
    void on(MyEvent event) {
        // handle event
    }
}

class MyOtherHandler {

    @org.axonframework.messaging.eventhandling.annotation.EventHandler
    void on(MyEvent event) {
        // handle event
    }
}

class ModuleHandler {

    @org.axonframework.messaging.eventhandling.annotation.EventHandler
    void on(MyEvent event) {
        // handle event
    }
}

record MyEvent(String id) {

}
