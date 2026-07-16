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
