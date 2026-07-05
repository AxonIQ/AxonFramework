package events.eventprocessors.index.errorhandling.specific;

import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.eventhandling.configuration.EventHandlingComponentsConfigurer;
import org.axonframework.messaging.eventhandling.processing.errorhandling.ErrorContext;
import org.axonframework.messaging.eventhandling.processing.errorhandling.ErrorHandler;
import org.axonframework.messaging.eventhandling.processing.subscribing.SubscribingEventProcessorsConfigurer;

// tag::error-handler-specific-processor[]
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
                                .customized((c, subscribingConfig) -> subscribingConfig.errorHandler(
                                        new CustomErrorHandler()
                                ))
        );
    }

    private EventHandlingComponentsConfigurer.AdditionalComponentPhase configureHandlingComponent(
            EventHandlingComponentsConfigurer.RequiredComponentPhase componentConfigurer
    ) {
        return componentConfigurer.autodetected("my-handler", c -> new MyHandler());
    }
}
// end::error-handler-specific-processor[]

class MyHandler {

    @org.axonframework.messaging.eventhandling.annotation.EventHandler
    void on(MyEvent event) {
        // handle event
    }
}

record MyEvent(String id) {

}

class CustomErrorHandler implements ErrorHandler {

    @Override
    public void handleError(ErrorContext errorContext) {
        // handle the error, e.g. log it
    }
}
