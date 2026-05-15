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

package org.axonframework.integrationtests.queryhandling;

import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageDispatchInterceptor;
import org.axonframework.messaging.core.MessageDispatchInterceptorChain;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.queryhandling.GenericQueryResponseMessage;
import org.axonframework.messaging.queryhandling.QueryBus;
import org.axonframework.messaging.queryhandling.QueryHandler;
import org.axonframework.messaging.queryhandling.QueryMessage;
import org.axonframework.messaging.queryhandling.QueryResponseMessage;
import org.jspecify.annotations.NonNull;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Abstract base test suite for {@link QueryBus} integration tests. Provides common test infrastructure including helper
 * classes and abstract methods for query bus configuration.
 * <p>
 * Subclasses should implement:
 * <ul>
 *   <li>{@link #queryBus()} - provide the QueryBus instance to test</li>
 *   <li>{@link #createMessagingConfigurer()} - provide test-specific configuration</li>
 * </ul>
 *
 * @author Mateusz Nowak
 * @since 5.0.0
 */
public abstract class AbstractQueryTestSuite {

    protected static final MessageType TEST_RESPONSE_TYPE = new MessageType(String.class);

    /**
     * Return the {@link QueryBus} used for testing.
     *
     * @return The {@link QueryBus} instance to test.
     */
    public abstract QueryBus queryBus();

    /**
     * Creates a {@link MessagingConfigurer} with the test-specific configuration (e.g., AxonServer enabled/disabled).
     * This configurer is not yet built, allowing tests to register additional components like interception.
     *
     * @return A {@link MessagingConfigurer} instance ready for additional configuration.
     */
    protected abstract MessagingConfigurer createMessagingConfigurer();

    /**
     * A recording query handler that stores all received queries for later assertion.
     */
    protected static class RecordingQueryHandler implements QueryHandler {

        private final List<QueryMessage> recordedQueries = new CopyOnWriteArrayList<>();

        @NonNull
        @Override
        public MessageStream<QueryResponseMessage> handle(@NonNull QueryMessage message,
                                                          @NonNull ProcessingContext context) {
            recordedQueries.add(message);
            return MessageStream.just(
                    new GenericQueryResponseMessage(TEST_RESPONSE_TYPE,
                                                    Arrays.asList("Message1", "Message2", "Message3"))
            );
        }

        public List<QueryMessage> getRecordedQueries() {
            return recordedQueries;
        }
    }

    /**
     * Test interceptor that adds metadata to BOTH request and response messages with an incrementing counter.
     * <p>
     * This dual modification pattern allows tests to verify:
     * <ul>
     *   <li><b>Request interception:</b> Metadata added before handler sees the message</li>
     *   <li><b>Response interception:</b> Metadata added to the response after handler execution</li>
     *   <li><b>Chaining behavior:</b> Counter increments show interceptor order and layering</li>
     * </ul>
     */
    protected record AddMetadataCountInterceptor<M extends Message>(String key, String value)
            implements MessageHandlerInterceptor<M>, MessageDispatchInterceptor<M> {

        @Override
        public @NonNull MessageStream<?> interceptOnDispatch(@NonNull M message,
                                                             ProcessingContext context,
                                                             @NonNull MessageDispatchInterceptorChain<M> interceptorChain) {
            // STEP 1: Modify the REQUEST message before passing to next interceptor/handler
            @SuppressWarnings("unchecked")
            var intercepted = (M) message.andMetadata(java.util.Map.of(key, buildValue(message)));

            return interceptorChain
                    .proceed(intercepted, context)
                    // STEP 2: Modify the RESPONSE message after handler execution
                    .mapMessage(m -> m.andMetadata(java.util.Map.of(key, buildValue(m))));
        }

        @Override
        public @NonNull MessageStream<?> interceptOnHandle(@NonNull M message,
                                                           @NonNull ProcessingContext context,
                                                           @NonNull MessageHandlerInterceptorChain<M> interceptorChain) {
            // STEP 1: Modify the REQUEST message before passing to handler
            @SuppressWarnings("unchecked")
            var intercepted = (M) message.andMetadata(java.util.Map.of(key, buildValue(message)));

            return interceptorChain
                    .proceed(intercepted, context)
                    // STEP 2: Modify the RESPONSE message after handler execution
                    .mapMessage(m -> m.andMetadata(java.util.Map.of(key, buildValue(m))));
        }

        /**
         * Builds a value with an incrementing counter based on existing metadata. Counter starts at 0 and increments
         * with each modification.
         */
        private String buildValue(Message message) {
            int count = message.metadata().containsKey(key)
                    ? Integer.parseInt(message.metadata().get(key).split("-")[1])
                    : -1;
            return value + "-" + (count + 1);
        }
    }
}
