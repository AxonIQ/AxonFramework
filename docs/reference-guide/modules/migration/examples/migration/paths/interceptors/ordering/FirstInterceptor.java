package migration.paths.interceptors.ordering;

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

// tag::interceptor-order-first[]
@Component
@Order(1)
public class FirstInterceptor implements MessageHandlerInterceptor<CommandMessage> {
    // Applied first
    // end::interceptor-order-first[]

    @Override
    public MessageStream<?> interceptOnHandle(CommandMessage message,
                                              ProcessingContext context,
                                              MessageHandlerInterceptorChain<CommandMessage> chain) {
        return chain.proceed(message, context);
    }
    // tag::interceptor-order-first[]
}

// end::interceptor-order-first[]
