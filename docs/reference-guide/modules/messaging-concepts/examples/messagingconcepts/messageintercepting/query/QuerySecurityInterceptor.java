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
