package messagingconcepts.messageintercepting.query;

import org.axonframework.messaging.core.configuration.MessagingConfigurer;

public class QueryInterceptorConfig {

    // tag::register-query-dispatch[]
    public void registerQueryDispatchInterceptor(MessagingConfigurer configurer) {
        configurer.registerQueryDispatchInterceptor(
                config -> new QueryLoggingDispatchInterceptor()
        );
    }
    // end::register-query-dispatch[]

    // tag::register-query-handler[]
    public void registerQueryHandlerInterceptor(MessagingConfigurer configurer) {
        configurer.registerQueryHandlerInterceptor(
                config -> new QuerySecurityInterceptor()
        );
    }
    // end::register-query-handler[]
}
