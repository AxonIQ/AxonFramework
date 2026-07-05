package messagingconcepts.componentmessageintercepting.querybefore;

import messagingconcepts.componentmessageintercepting.AuditLog;
import messagingconcepts.componentmessageintercepting.CourseView;
import messagingconcepts.componentmessageintercepting.FindCourseQuery;
import messagingconcepts.componentmessageintercepting.SecurityContext;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.queryhandling.QueryMessage;

// tag::query-interceptor-before[]
import org.axonframework.messaging.queryhandling.interception.annotation.QueryHandlerInterceptor;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;

public class CourseQueryHandler {
    // end::query-interceptor-before[]

    private final AuditLog auditLog = new AuditLog();
    private final SecurityContext securityContext = new SecurityContext();
    // tag::query-interceptor-before[]

    @QueryHandlerInterceptor
    void auditQuery(QueryMessage query) {
        auditLog.record(query.type().qualifiedName(), securityContext.currentUser());
    }

    @QueryHandler
    CourseView handle(FindCourseQuery query, ProcessingContext context) {
        // Handle the query
        return null;
    }
}
// end::query-interceptor-before[]
