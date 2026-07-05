package messagingconcepts.timeouts;

import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.core.timeout.UnitOfWorkTimeoutInterceptorBuilder;

class ProcessingContextTimeoutConfiguration {

    // tag::processing-context-timeout-config[]
    public void configureTimeoutBehavior(MessagingConfigurer configurer) {
        // Register timeout interceptor for command bus
        configurer.registerCommandHandlerInterceptor(c -> {
            UnitOfWorkTimeoutInterceptorBuilder builder =
                    new UnitOfWorkTimeoutInterceptorBuilder(
                            "CommandBus",
                            30000,  // timeout in ms
                            25000,  // warning threshold in ms
                            1000    // warning interval in ms
                    );
            return builder.buildCommandInterceptor();
        });

        // Register timeout interceptor for query bus
        configurer.registerQueryHandlerInterceptor(c -> {
            UnitOfWorkTimeoutInterceptorBuilder builder =
                    new UnitOfWorkTimeoutInterceptorBuilder(
                            "QueryBus",
                            30000,  // timeout in ms
                            25000,  // warning threshold in ms
                            1000    // warning interval in ms
                    );
            return builder.buildQueryInterceptor();
        });

        // Register timeout interceptor for event handlers
        configurer.registerEventHandlerInterceptor(c -> {
            UnitOfWorkTimeoutInterceptorBuilder builder =
                    new UnitOfWorkTimeoutInterceptorBuilder(
                            "EventProcessor",
                            30000,  // timeout in ms
                            25000,  // warning threshold in ms
                            1000    // warning interval in ms
                    );
            return builder.buildEventInterceptor();
        });
    }
    // end::processing-context-timeout-config[]
}
