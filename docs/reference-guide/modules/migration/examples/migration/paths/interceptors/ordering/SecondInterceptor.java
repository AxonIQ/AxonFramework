package migration.paths.interceptors.ordering;

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

// tag::interceptor-order-second[]
@Component
@Order(2)
public class SecondInterceptor implements MessageHandlerInterceptor<CommandMessage> {
    // Applied second
    // end::interceptor-order-second[]

    @Override
    public MessageStream<?> interceptOnHandle(CommandMessage message,
                                              ProcessingContext context,
                                              MessageHandlerInterceptorChain<CommandMessage> chain) {
        return chain.proceed(message, context);
    }
    // tag::interceptor-order-second[]
}
// end::interceptor-order-second[]
