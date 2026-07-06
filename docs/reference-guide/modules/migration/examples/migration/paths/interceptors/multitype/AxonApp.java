package migration.paths.interceptors.multitype;

import org.axonframework.common.configuration.Configuration;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

class AxonApp {

    public static void main(String[] args) {
        // tag::multi-type-registration[]
        MessagingConfigurer configurer = MessagingConfigurer.create();

        // Register for multiple message types
        configurer.registerCommandHandlerInterceptor(config -> new LoggingInterceptor("Command"))
                  .registerEventHandlerInterceptor(config -> new LoggingInterceptor("Event"))
                  .registerQueryHandlerInterceptor(config -> new LoggingInterceptor("Query"));

        Configuration configuration = configurer.build();
        // end::multi-type-registration[]
    }
}

class LoggingInterceptor implements MessageHandlerInterceptor<Message> {

    private final String label;

    LoggingInterceptor(String label) {
        this.label = label;
    }

    @Override
    public MessageStream<?> interceptOnHandle(Message message,
                                              ProcessingContext context,
                                              MessageHandlerInterceptorChain<Message> chain) {
        return chain.proceed(message, context);
    }
}
