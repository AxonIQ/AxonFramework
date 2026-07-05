package queries.configuration.interceptors;

import org.axonframework.messaging.core.MessageDispatchInterceptor;
import org.axonframework.messaging.core.MessageDispatchInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.queryhandling.QueryMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Supporting dispatch interceptor used by the interceptor configuration sample on the configuration page.
 */
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
