/*
 * Copyright (c) 2010-2026. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
