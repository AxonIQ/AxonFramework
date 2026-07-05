package messagingconcepts.messageintercepting.generic;

import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.core.interception.LoggingInterceptor;

public class GenericInterceptorConfig {

    // tag::register-generic-handler[]
    public void registerMessageHandlerInterceptor(MessagingConfigurer configurer) {
        configurer.registerMessageHandlerInterceptor(
                // The LoggingInterceptor is provided by Axon out of the box.
                config -> new LoggingInterceptor<>()
        );
    }
    // end::register-generic-handler[]
}
