package queries.configuration.interceptors.springboot;

import org.axonframework.messaging.core.MessageDispatchInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// tag::query-dispatch-interceptor-springboot[]
import org.springframework.stereotype.Component;
import org.axonframework.messaging.core.MessageDispatchInterceptor;
import org.axonframework.messaging.queryhandling.QueryMessage;

@Component
public class LoggingQueryDispatchInterceptor implements MessageDispatchInterceptor<QueryMessage> {

    private static final Logger logger = LoggerFactory.getLogger(LoggingQueryDispatchInterceptor.class);

    @Override
    public MessageStream<?> interceptOnDispatch(
            QueryMessage message,
            ProcessingContext context,
            MessageDispatchInterceptorChain<QueryMessage> chain
    ) {
        logger.info("Dispatching: {}", message.type().name());
        return chain.proceed(message, context);
    }
}
// end::query-dispatch-interceptor-springboot[]
