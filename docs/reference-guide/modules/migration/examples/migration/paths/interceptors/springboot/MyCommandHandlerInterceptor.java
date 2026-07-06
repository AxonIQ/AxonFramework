package migration.paths.interceptors.springboot;

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// tag::springboot-handler-interceptor[]
@Component
public class MyCommandHandlerInterceptor implements MessageHandlerInterceptor<CommandMessage> {
    // end::springboot-handler-interceptor[]

    private static final Logger logger = LoggerFactory.getLogger(MyCommandHandlerInterceptor.class);
    // tag::springboot-handler-interceptor[]

    @Override
    public MessageStream<?> interceptOnHandle(CommandMessage message,
                                              ProcessingContext context,
                                              MessageHandlerInterceptorChain<CommandMessage> chain) {
        logger.info("Handling command: {}", message.type().name());
        return chain.proceed(message, context);
    }
}
// end::springboot-handler-interceptor[]
