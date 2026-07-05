package events.eventprocessors.index.errorhandling.defaults;

import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.eventhandling.processing.errorhandling.ErrorContext;
import org.axonframework.messaging.eventhandling.processing.errorhandling.ErrorHandler;

// tag::error-handler-default[]
public class AxonConfig {

    public void configureEventProcessing(MessagingConfigurer configurer) {
        configurer.eventProcessing(eventConfigurer -> eventConfigurer.defaults(
                defaults -> defaults.errorHandler(new CustomErrorHandler())
        ));
    }
}
// end::error-handler-default[]

class CustomErrorHandler implements ErrorHandler {

    @Override
    public void handleError(ErrorContext errorContext) {
        // handle the error, e.g. log it
    }
}
