package messagingconcepts.messageintercepting.query;

import org.axonframework.messaging.core.MessageDispatchInterceptor;
import org.axonframework.messaging.core.MessageDispatchInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.queryhandling.QueryMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// tag::query-dispatch-logging[]
public class QueryLoggingDispatchInterceptor
        implements MessageDispatchInterceptor<QueryMessage> {

    private static final Logger logger =
            LoggerFactory.getLogger(QueryLoggingDispatchInterceptor.class);

    @Override
    public MessageStream<?> interceptOnDispatch(
            QueryMessage query,
            ProcessingContext context,
            MessageDispatchInterceptorChain<QueryMessage> chain
    ) {

        logger.info("Dispatching query: {}", query.type());
        return chain.proceed(query, context);
    }
}
// end::query-dispatch-logging[]
