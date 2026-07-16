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
