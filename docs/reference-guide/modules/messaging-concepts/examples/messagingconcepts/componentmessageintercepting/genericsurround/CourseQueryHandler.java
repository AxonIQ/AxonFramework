package messagingconcepts.componentmessageintercepting.genericsurround;

import org.axonframework.messaging.core.unitofwork.ProcessingContext;

// tag::message-interceptor-surround[]
import org.axonframework.messaging.core.interception.annotation.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.queryhandling.QueryMessage;

public class CourseQueryHandler {

    @MessageHandlerInterceptor(messageType = QueryMessage.class)
    MessageStream<?> intercept(
            QueryMessage query,
            MessageHandlerInterceptorChain<QueryMessage> chain,
            ProcessingContext context
    ) {
        // Logic before handling
        MessageStream<?> result = chain.proceed(query, context);
        // Logic after handling (if needed, chain the stream)
        return result;
    }
}
// end::message-interceptor-surround[]
