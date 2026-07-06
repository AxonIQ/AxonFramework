package migration.paths.interceptors.componentspecific;

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.core.interception.HandlerInterceptorRegistry;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

class AxonApp {

    public static void main(String[] args) {
        MessagingConfigurer configurer = MessagingConfigurer.create();

        // tag::component-specific-registration[]
        configurer.componentRegistry(cr -> cr.registerDecorator(
                HandlerInterceptorRegistry.class,
                0,
                (config, name, registry) -> registry.registerCommandInterceptor(
                        (factoryConfig, componentType, componentName) -> {
                            // Only intercept OrderAggregate commands
                            if (componentType.equals(OrderAggregate.class)) {
                                return new OrderValidationInterceptor();
                            }
                            return null; // No interceptor for other components
                        }
                )
        ));
        // end::component-specific-registration[]
    }
}

class OrderAggregate {
}

class OrderValidationInterceptor implements MessageHandlerInterceptor<CommandMessage> {

    @Override
    public MessageStream<?> interceptOnHandle(CommandMessage message,
                                              ProcessingContext context,
                                              MessageHandlerInterceptorChain<CommandMessage> chain) {
        return chain.proceed(message, context);
    }
}
