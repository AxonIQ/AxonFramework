package messagingconcepts.messageintercepting.query;

import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.queryhandling.QueryMessage;

// tag::query-handler-security[]
public class QuerySecurityInterceptor
        implements MessageHandlerInterceptor<QueryMessage> {

    @Override
    public MessageStream<?> interceptOnHandle(
            QueryMessage query,
            ProcessingContext context,
            MessageHandlerInterceptorChain<QueryMessage> chain
    ) {
        String userId = query.metadata().get("userId");
        if (userId == null || !"authorized-user".equals(userId)) {
            throw new SecurityException("Unauthorized query");
        }

        return chain.proceed(query, context);
    }
}
// end::query-handler-security[]
