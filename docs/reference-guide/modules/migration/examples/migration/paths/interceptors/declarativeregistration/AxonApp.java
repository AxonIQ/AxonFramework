package migration.paths.interceptors.declarativeregistration;

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.MessageDispatchInterceptor;
import org.axonframework.messaging.core.MessageDispatchInterceptorChain;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;

class AxonApp {

    public static void main(String[] args) {
        // tag::declarative-registration[]
        MessagingConfigurer configurer = MessagingConfigurer.create();

        // Register handler interceptor for commands
        configurer.registerCommandHandlerInterceptor(
            config -> new MyCommandHandlerInterceptor()
        );

        // Register dispatch interceptor for commands
        configurer.registerCommandDispatchInterceptor(
            config -> new MyCommandDispatchInterceptor()
        );
        // end::declarative-registration[]
    }
}

class MyCommandHandlerInterceptor implements MessageHandlerInterceptor<CommandMessage> {

    @Override
    public MessageStream<?> interceptOnHandle(CommandMessage message,
                                              ProcessingContext context,
                                              MessageHandlerInterceptorChain<CommandMessage> chain) {
        return chain.proceed(message, context);
    }
}

class MyCommandDispatchInterceptor implements MessageDispatchInterceptor<CommandMessage> {

    @Override
    public MessageStream<?> interceptOnDispatch(CommandMessage message,
                                                @Nullable ProcessingContext context,
                                                MessageDispatchInterceptorChain<CommandMessage> chain) {
        return chain.proceed(message, context);
    }
}
