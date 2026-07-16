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
