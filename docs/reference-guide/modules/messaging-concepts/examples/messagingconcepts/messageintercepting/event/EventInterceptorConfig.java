package messagingconcepts.messageintercepting.event;

import org.axonframework.messaging.core.configuration.MessagingConfigurer;

public class EventInterceptorConfig {

    // tag::register-event-dispatch[]
    public void registerEventDispatchInterceptor(MessagingConfigurer configurer) {
        configurer.registerEventDispatchInterceptor(
                config -> new EventLoggingDispatchInterceptor()
        );
    }
    // end::register-event-dispatch[]

    // tag::register-event-handler[]
    public void registerEventHandlerInterceptor(MessagingConfigurer configurer) {
        configurer.registerEventHandlerInterceptor(
                config -> new EventSecurityInterceptor()
        );
    }
    // end::register-event-handler[]
}
