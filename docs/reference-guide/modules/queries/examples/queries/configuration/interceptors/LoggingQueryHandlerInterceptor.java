package queries.configuration.interceptors;

import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.queryhandling.QueryMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Supporting handler interceptor used by the interceptor configuration sample on the configuration page.
 */
public class LoggingQueryHandlerInterceptor implements MessageHandlerInterceptor<QueryMessage> {

    private static final Logger logger = LoggerFactory.getLogger(LoggingQueryHandlerInterceptor.class);

    @Override
    public MessageStream<?> interceptOnHandle(
            QueryMessage message,
            ProcessingContext context,
            MessageHandlerInterceptorChain<QueryMessage> chain
    ) {
        logger.info("Handling: {}", message.type().name());
        return chain.proceed(message, context);
    }
}
