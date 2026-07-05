package queries.configuration.interceptors;

// tag::query-interceptors-configuration-api[]
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.core.MessageDispatchInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptor;

public class AxonConfig {

    public void registerQueryInterceptors(MessagingConfigurer configurer) {
        // Register a query dispatch interceptor
        configurer.registerQueryDispatchInterceptor(
                          config -> new LoggingQueryDispatchInterceptor()
                  )
                  // Register a query handler interceptor
                  .registerQueryHandlerInterceptor(
                          config -> new LoggingQueryHandlerInterceptor()
                  );
    }
}
// end::query-interceptors-configuration-api[]
