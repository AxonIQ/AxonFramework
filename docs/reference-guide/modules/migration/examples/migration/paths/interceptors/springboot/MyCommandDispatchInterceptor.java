package migration.paths.interceptors.springboot;

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.MessageDispatchInterceptor;
import org.axonframework.messaging.core.MessageDispatchInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// tag::springboot-dispatch-interceptor[]
@Component
public class MyCommandDispatchInterceptor implements MessageDispatchInterceptor<CommandMessage> {
    // end::springboot-dispatch-interceptor[]

    private static final Logger logger = LoggerFactory.getLogger(MyCommandDispatchInterceptor.class);
    // tag::springboot-dispatch-interceptor[]

    @Override
    public MessageStream<?> interceptOnDispatch(CommandMessage message,
                                                @Nullable ProcessingContext context,
                                                MessageDispatchInterceptorChain<CommandMessage> chain) {
        logger.info("Dispatching command: {}", message.type().name());
        return chain.proceed(message, context);
    }
}
// end::springboot-dispatch-interceptor[]
