package messagingconcepts.componentmessageintercepting.querysurround;

import messagingconcepts.componentmessageintercepting.AccessDeniedException;
import messagingconcepts.componentmessageintercepting.CourseView;
import messagingconcepts.componentmessageintercepting.FindCourseQuery;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.queryhandling.QueryMessage;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;

// tag::query-interceptor-surround[]
import org.axonframework.messaging.queryhandling.interception.annotation.QueryHandlerInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;

public class CourseQueryHandler {
    // end::query-interceptor-surround[]

    private final String tenantId = "tenant-1";
    // tag::query-interceptor-surround[]

    @QueryHandlerInterceptor
    MessageStream<?> checkTenant(
            QueryMessage query,
            MessageHandlerInterceptorChain<QueryMessage> chain,
            ProcessingContext context
    ) {
        if (!tenantId.equals(query.metadata().get("tenantId"))) {
            return MessageStream.failed(new AccessDeniedException("Wrong tenant"));
        }
        return chain.proceed(query, context);
    }

    @QueryHandler
    CourseView handle(FindCourseQuery query, ProcessingContext context) {
        // Handle the query
        return null;
    }
}
// end::query-interceptor-surround[]
