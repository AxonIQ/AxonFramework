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

package org.axonframework.messaging.queryhandling.interception;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageDispatchInterceptor;
import org.axonframework.messaging.core.MessageDispatchInterceptorChain;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkTestUtils;
import org.axonframework.messaging.queryhandling.GenericQueryMessage;
import org.axonframework.messaging.queryhandling.GenericQueryResponseMessage;
import org.axonframework.messaging.queryhandling.GenericSubscriptionQueryUpdateMessage;
import org.axonframework.messaging.queryhandling.QueryBus;
import org.axonframework.messaging.queryhandling.QueryHandler;
import org.axonframework.messaging.queryhandling.QueryMessage;
import org.axonframework.messaging.queryhandling.QueryResponseMessage;
import org.axonframework.messaging.queryhandling.SimpleQueryBus;
import org.axonframework.messaging.queryhandling.SubscriptionQueryUpdateMessage;
import org.axonframework.common.util.MockException;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class validating the {@link InterceptingQueryBus}.
 *
 * @author Mateusz Nowak
 * @since 5.0.0
 */
class InterceptingQueryBusTest {

    private static final MessageType TEST_QUERY_TYPE = new MessageType("testQuery");
    private static final MessageType TEST_RESPONSE_TYPE = new MessageType(String.class);
    private static final QualifiedName QUERY_NAME = new QualifiedName("testQuery");

    private InterceptingQueryBus testSubject;
    private QueryBus delegateQueryBus;
    private MessageHandlerInterceptor<QueryMessage> handlerInterceptor1;
    private MessageHandlerInterceptor<QueryMessage> handlerInterceptor2;
    private MessageDispatchInterceptor<Message> dispatchInterceptor1;
    private MessageDispatchInterceptor<Message> dispatchInterceptor2;

    @BeforeEach
    void setUp() {
        delegateQueryBus = new SimpleQueryBus(UnitOfWorkTestUtils.SIMPLE_FACTORY);
        handlerInterceptor1 = new AddMetadataCountInterceptor<>("handler1", "value");
        handlerInterceptor2 = new AddMetadataCountInterceptor<>("handler2", "value");
        dispatchInterceptor1 = new AddMetadataCountInterceptor<>("dispatch1", "value");
        dispatchInterceptor2 = new AddMetadataCountInterceptor<>("dispatch2", "value");

        testSubject = new InterceptingQueryBus(delegateQueryBus,
                                               List.of(handlerInterceptor1, handlerInterceptor2),
                                               List.of(dispatchInterceptor1, dispatchInterceptor2),
                                               List.of());
    }

    @Nested
    @DisplayName("Dispatch interceptor tests")
    class DispatchInterceptorTests {

        @Test
        void dispatchInterceptorsModifyRequestMessage() {
            // given
            RecordingQueryHandler handler = new RecordingQueryHandler();
            testSubject.subscribe(QUERY_NAME, handler);

            QueryMessage testQuery = new GenericQueryMessage(TEST_QUERY_TYPE, "test");

            // when
            testSubject.query(testQuery, StubProcessingContext.forMessage(testQuery));

            // then - Verify REQUEST interception: interception added metadata to the query BEFORE handler saw it
            // This demonstrates that dispatch interception can add context (correlation IDs, auth tokens, etc.)
            // to requests before they reach the handler
            assertThat(handler.getRecordedQueries()).hasSize(1);
            QueryMessage recordedQuery = handler.getRecordedQueries().getFirst();
            assertTrue(recordedQuery.metadata().containsKey("dispatch1"),
                       "Expected dispatch1 interceptor to add metadata to REQUEST");
            assertTrue(recordedQuery.metadata().containsKey("dispatch2"),
                       "Expected dispatch2 interceptor to add metadata to REQUEST");
        }

        @Test
        void dispatchInterceptorsModifyResponseMessage() {
            // given
            RecordingQueryHandler handler = new RecordingQueryHandler();
            testSubject.subscribe(QUERY_NAME, handler);

            QueryMessage testQuery = new GenericQueryMessage(TEST_QUERY_TYPE, "test");

            // when
            MessageStream<QueryResponseMessage> result = testSubject.query(testQuery,
                                                                           StubProcessingContext.forMessage(testQuery));

            // then - Verify RESPONSE interception: interception added metadata to the response AFTER handler executed
            // This demonstrates that dispatch interception can add info (metrics, timing, tracing, etc.)
            // to responses before they're returned to the caller
            QueryResponseMessage response = result.first().asCompletableFuture().join().message();
            assertTrue(response.metadata().containsKey("dispatch1"),
                       "Expected dispatch1 interceptor to add metadata to RESPONSE");
            assertTrue(response.metadata().containsKey("dispatch2"),
                       "Expected dispatch2 interceptor to add metadata to RESPONSE");
        }

        @Test
        void dispatchInterceptorsAreInvokedForEveryQuery() {
            // given
            AtomicInteger counter = new AtomicInteger(0);
            MessageDispatchInterceptor<Message> countingInterceptor = (message, context, chain) -> {
                counter.incrementAndGet();
                return chain.proceed(message, context);
            };
            InterceptingQueryBus countingTestSubject =
                    new InterceptingQueryBus(delegateQueryBus, List.of(), List.of(countingInterceptor), List.of());

            QueryHandler handler = (query, context) -> MessageStream.just(new GenericQueryResponseMessage(
                    TEST_RESPONSE_TYPE,
                    "ok"));
            countingTestSubject.subscribe(QUERY_NAME, handler);

            QueryMessage firstQuery = new GenericQueryMessage(TEST_QUERY_TYPE, "first");
            QueryMessage secondQuery = new GenericQueryMessage(TEST_QUERY_TYPE, "second");

            // when
            countingTestSubject.query(firstQuery, StubProcessingContext.forMessage(firstQuery));
            countingTestSubject.query(secondQuery, StubProcessingContext.forMessage(secondQuery));

            // then
            assertThat(counter.get()).isEqualTo(2);
        }

        @Test
        void exceptionInDispatchInterceptorPreventsHandlerInvocation() {
            // given
            MessageDispatchInterceptor<Message> throwingInterceptor = (message, context, chain) -> {
                throw new MockException("Simulating exception in dispatch interceptor");
            };

            InterceptingQueryBus throwingTestSubject =
                    new InterceptingQueryBus(delegateQueryBus,
                                             List.of(),
                                             List.of(dispatchInterceptor1, throwingInterceptor),
                                             List.of());

            RecordingQueryHandler handler = new RecordingQueryHandler();
            throwingTestSubject.subscribe(QUERY_NAME, handler);

            QueryMessage testQuery = new GenericQueryMessage(TEST_QUERY_TYPE, "test");

            // when
            MessageStream<QueryResponseMessage> result = throwingTestSubject.query(testQuery,
                                                                                   StubProcessingContext.forMessage(
                                                                                           testQuery));

            // then
            assertTrue(result.first().asCompletableFuture().isCompletedExceptionally());
            assertInstanceOf(MockException.class, result.first().asCompletableFuture().exceptionNow());
            assertThat(handler.getRecordedQueries()).isEmpty(); // Handler should not be invoked when interceptor throws
        }

        @Test
        void failedStreamInDispatchInterceptorPreventsHandlerInvocation() {
            // given
            MessageDispatchInterceptor<Message> failingInterceptor = (message, context, chain) ->
                    MessageStream.failed(new MockException("Simulating failed stream in interceptor"));

            InterceptingQueryBus failingTestSubject =
                    new InterceptingQueryBus(delegateQueryBus,
                                             List.of(),
                                             List.of(dispatchInterceptor1, failingInterceptor),
                                             List.of());

            RecordingQueryHandler handler = new RecordingQueryHandler();
            failingTestSubject.subscribe(QUERY_NAME, handler);

            QueryMessage testQuery = new GenericQueryMessage(TEST_QUERY_TYPE, "test");

            // when
            MessageStream<QueryResponseMessage> result = failingTestSubject.query(testQuery,
                                                                                  StubProcessingContext.forMessage(
                                                                                          testQuery));

            // then
            assertTrue(result.first().asCompletableFuture().isCompletedExceptionally());
            assertInstanceOf(MockException.class, result.first().asCompletableFuture().exceptionNow());
            assertThat(handler.getRecordedQueries()).isEmpty(); // Handler should not be invoked when interceptor returns failed stream
        }
    }

    @Nested
    @DisplayName("Handler interceptor tests")
    class HandlerInterceptorTests {

        @Test
        void handlerInterceptorsModifyRequestMessage() {
            // given
            RecordingQueryHandler handler = new RecordingQueryHandler();
            testSubject.subscribe(QUERY_NAME, handler);

            QueryMessage testQuery = new GenericQueryMessage(TEST_QUERY_TYPE, "test");
            ProcessingContext context = StubProcessingContext.forMessage(testQuery);

            // when
            testSubject.query(testQuery, context);

            // then - Verify REQUEST interception: handler interception added metadata to the query BEFORE handler saw it
            // Handler interception apply within the Processing Context, after dispatch interception
            assertThat(handler.getRecordedQueries()).hasSize(1);
            QueryMessage recordedQuery = handler.getRecordedQueries().getFirst();
            assertTrue(recordedQuery.metadata().containsKey("handler1"),
                       "Expected handler1 interceptor to add metadata to REQUEST");
            assertTrue(recordedQuery.metadata().containsKey("handler2"),
                       "Expected handler2 interceptor to add metadata to REQUEST");
        }

        @Test
        void handlerInterceptorsModifyResponseMessage() {
            // given
            RecordingQueryHandler handler = new RecordingQueryHandler();
            testSubject.subscribe(QUERY_NAME, handler);

            QueryMessage testQuery = new GenericQueryMessage(TEST_QUERY_TYPE, "test");
            ProcessingContext context = StubProcessingContext.forMessage(testQuery);

            // when
            MessageStream<QueryResponseMessage> result = testSubject.query(testQuery, context);

            // then - Verify RESPONSE interception: handler interception added metadata to the response AFTER handler executed
            // Handler interception can enrich responses within the UnitOfWork context
            QueryResponseMessage response = result.first().asCompletableFuture().join().message();
            assertTrue(response.metadata().containsKey("handler1"),
                       "Expected handler1 interceptor to add metadata to RESPONSE");
            assertTrue(response.metadata().containsKey("handler2"),
                       "Expected handler2 interceptor to add metadata to RESPONSE");
        }

        @Test
        void handlerInterceptorsAreInvokedForEveryQuery() {
            // given
            AtomicInteger counter = new AtomicInteger(0);
            MessageHandlerInterceptor<QueryMessage> countingInterceptor = (message, context, chain) -> {
                counter.incrementAndGet();
                return chain.proceed(message, context);
            };
            InterceptingQueryBus countingTestSubject =
                    new InterceptingQueryBus(delegateQueryBus, List.of(countingInterceptor), List.of(), List.of());

            QueryHandler handler = (query, context) -> MessageStream.just(new GenericQueryResponseMessage(
                    TEST_RESPONSE_TYPE,
                    "ok"));
            countingTestSubject.subscribe(QUERY_NAME, handler);

            QueryMessage firstQuery = new GenericQueryMessage(TEST_QUERY_TYPE, "first");
            QueryMessage secondQuery = new GenericQueryMessage(TEST_QUERY_TYPE, "second");

            // when
            countingTestSubject.query(firstQuery, StubProcessingContext.forMessage(firstQuery)).first();
            countingTestSubject.query(secondQuery, StubProcessingContext.forMessage(secondQuery)).first();

            // then
            assertThat(counter.get()).isEqualTo(2);
        }

        @Test
        void exceptionsInHandlerInterceptorReturnFailedStream() {
            // given
            MessageHandlerInterceptor<QueryMessage> failingInterceptor = (message, context, chain) -> {
                throw new MockException("Simulating failure in interceptor");
            };

            InterceptingQueryBus failingTestSubject =
                    new InterceptingQueryBus(delegateQueryBus,
                                             List.of(handlerInterceptor1, failingInterceptor),
                                             List.of(),
                                             List.of());

            QueryHandler handler = (query, context) -> MessageStream.just(new GenericQueryResponseMessage(
                    TEST_RESPONSE_TYPE,
                    "ok"));
            failingTestSubject.subscribe(QUERY_NAME, handler);

            QueryMessage testQuery = new GenericQueryMessage(TEST_QUERY_TYPE, "Request");
            ProcessingContext context = StubProcessingContext.forMessage(testQuery);

            // when
            var result = failingTestSubject.query(testQuery, context);

            // then
            assertTrue(result.first().asCompletableFuture().isCompletedExceptionally());
            assertInstanceOf(MockException.class, result.first().asCompletableFuture().exceptionNow());
        }
    }

    @Nested
    @DisplayName("Subscribe tests")
    class SubscribeTests {

        @Test
        void subscribeAllowsHandlingQueries() {
            // given
            QueryHandler handler = (query, context) -> MessageStream.just(new GenericQueryResponseMessage(
                    TEST_RESPONSE_TYPE,
                    "subscribed-ok"));

            // when
            testSubject.subscribe(QUERY_NAME, handler);

            QueryMessage testQuery = new GenericQueryMessage(TEST_QUERY_TYPE, "test");
            MessageStream<QueryResponseMessage> result = testSubject.query(testQuery,
                                                                           StubProcessingContext.forMessage(testQuery));

            // then
            QueryResponseMessage response = result.first().asCompletableFuture().join().message();
            assertThat(response.payload()).isEqualTo("subscribed-ok");
        }
    }

    @Nested
    @DisplayName("Subscription query tests")
    class SubscriptionQueryTests {

        @Test
        void subscriptionQueryDelegatesToUnderlyingBus() {
            // given
            QueryHandler handler = (query, context) -> MessageStream.just(new GenericQueryResponseMessage(
                    TEST_RESPONSE_TYPE,
                    "initial"));
            testSubject.subscribe(QUERY_NAME, handler);

            QueryMessage testQuery = new GenericQueryMessage(
                    TEST_QUERY_TYPE, "test"
            );
            ProcessingContext context = StubProcessingContext.forMessage(testQuery);

            // when
            MessageStream<QueryResponseMessage> result = testSubject.subscriptionQuery(testQuery, context, 10);

            // then
            QueryResponseMessage response = result.first().asCompletableFuture().join().message();
            assertThat(response.payload()).isEqualTo("initial");
        }

        @Test
        void subscriptionQueryHandlerInterceptorsApplied() {
            // given
            RecordingQueryHandler handler = new RecordingQueryHandler();
            testSubject.subscribe(QUERY_NAME, handler);

            QueryMessage testQuery = new GenericQueryMessage(
                    TEST_QUERY_TYPE, "test"
            );
            ProcessingContext context = StubProcessingContext.forMessage(testQuery);

            // when
            MessageStream<QueryResponseMessage> result = testSubject.subscriptionQuery(testQuery, context, 10);

            // then
            QueryResponseMessage response = result.first().asCompletableFuture().join().message();
            assertTrue(response.metadata().containsKey("handler1"),
                       "Expected handler1 interceptor to be applied to response");
            assertTrue(response.metadata().containsKey("handler2"),
                       "Expected handler2 interceptor to be applied to response");

            // Verify handler interception added metadata to the query received by handler
            assertThat(handler.getRecordedQueries()).hasSize(1);
            QueryMessage recordedQuery = handler.getRecordedQueries().get(0);
            assertTrue(recordedQuery.metadata().containsKey("handler1"),
                       "Expected handler1 interceptor to add metadata to query");
            assertTrue(recordedQuery.metadata().containsKey("handler2"),
                       "Expected handler2 interceptor to add metadata to query");
        }

        @Test
        void subscriptionQueryDispatchInterceptorsApplied() {
            // given
            RecordingQueryHandler handler = new RecordingQueryHandler();
            testSubject.subscribe(QUERY_NAME, handler);

            QueryMessage testQuery = new GenericQueryMessage(TEST_QUERY_TYPE, "test");
            ProcessingContext context = StubProcessingContext.forMessage(testQuery);

            // when
            MessageStream<QueryResponseMessage> result = testSubject.subscriptionQuery(testQuery, context, 10);

            // then
            QueryResponseMessage response = result.first().asCompletableFuture().join().message();
            assertTrue(response.metadata().containsKey("dispatch1"),
                       "Expected dispatch1 interceptor to be applied to response");
            assertTrue(response.metadata().containsKey("dispatch2"),
                       "Expected dispatch2 interceptor to be applied to response");

            // Verify dispatch interception added metadata to the query received by handler
            assertThat(handler.getRecordedQueries()).hasSize(1);
            QueryMessage recordedQuery = handler.getRecordedQueries().getFirst();
            assertTrue(recordedQuery.metadata().containsKey("dispatch1"),
                       "Expected dispatch1 interceptor to add metadata to query");
            assertTrue(recordedQuery.metadata().containsKey("dispatch2"),
                       "Expected dispatch2 interceptor to add metadata to query");
        }
    }

    @Nested
    @DisplayName("Subscription update interception tests")
    class SubscriptionUpdateInterceptionTests {

        @Test
        void updateDispatchInterceptorsInvokedOnEmitUpdate() {
            // given
            AtomicInteger interceptor1Invocations = new AtomicInteger(0);
            AtomicInteger interceptor2Invocations = new AtomicInteger(0);

            MessageDispatchInterceptor<SubscriptionQueryUpdateMessage> updateInterceptor1 =
                    (message, context, chain) -> {
                        interceptor1Invocations.incrementAndGet();
                        return chain.proceed(message.andMetadata(Map.of("update1", "value")), context);
                    };
            MessageDispatchInterceptor<SubscriptionQueryUpdateMessage> updateInterceptor2 =
                    (message, context, chain) -> {
                        interceptor2Invocations.incrementAndGet();
                        return chain.proceed(message.andMetadata(Map.of("update2", "value")), context);
                    };

            InterceptingQueryBus testSubjectWithUpdateInterceptors = new InterceptingQueryBus(
                    delegateQueryBus,
                    List.of(),
                    List.of(),
                    List.of(updateInterceptor1, updateInterceptor2)
            );

            GenericSubscriptionQueryUpdateMessage updateMessage =
                    new GenericSubscriptionQueryUpdateMessage(TEST_RESPONSE_TYPE, "update");

            Predicate<QueryMessage> matchAll = query -> true;

            // when
            CompletableFuture<Void> result = testSubjectWithUpdateInterceptors.emitUpdate(
                    matchAll,
                    () -> updateMessage,
                    null
            );

            // then
            assertDoesNotThrow(result::join);
            assertThat(interceptor1Invocations.get()).isEqualTo(1);
            assertThat(interceptor2Invocations.get()).isEqualTo(1);
        }

        @Test
        void emptyUpdateInterceptorListBypassesInterception() {
            // given
            GenericSubscriptionQueryUpdateMessage updateMessage =
                    new GenericSubscriptionQueryUpdateMessage(TEST_RESPONSE_TYPE, "update");

            Predicate<QueryMessage> matchAll = query -> true;

            // when
            CompletableFuture<Void> result = testSubject.emitUpdate(
                    matchAll,
                    () -> updateMessage,
                    null
            );

            // then
            // Verify that emitUpdate works without interception
            assertDoesNotThrow(result::join);
        }

        @Test
        void andCountVariantsReturnTheDelegatesCountsWithoutUpdateInterceptors() {
            assertAndCountVariantsReturnTheDelegatesCounts(testSubject);
        }

        @Test
        void andCountVariantsReturnTheDelegatesCountsWithUpdateInterceptors() {
            MessageDispatchInterceptor<SubscriptionQueryUpdateMessage> updateInterceptor =
                    (message, context, chain) -> chain.proceed(message, context);
            assertAndCountVariantsReturnTheDelegatesCounts(
                    new InterceptingQueryBus(delegateQueryBus, List.of(), List.of(), List.of(updateInterceptor))
            );
        }

        private void assertAndCountVariantsReturnTheDelegatesCounts(InterceptingQueryBus subject) {
            // given
            subject.subscribeToUpdates(new GenericQueryMessage(TEST_QUERY_TYPE, "first"), 16);
            subject.subscribeToUpdates(new GenericQueryMessage(TEST_QUERY_TYPE, "second"), 16);
            GenericSubscriptionQueryUpdateMessage updateMessage =
                    new GenericSubscriptionQueryUpdateMessage(TEST_RESPONSE_TYPE, "update");

            // when
            OptionalInt emitted = subject.emitUpdateAndCount(query -> true, () -> updateMessage, null).join();
            OptionalInt completedExceptionally = subject.completeSubscriptionsExceptionallyAndCount(
                    query -> query.payload().equals("first"), new MockException("boom"), null
            ).join();
            OptionalInt completed = subject.completeSubscriptionsAndCount(
                    query -> query.payload().equals("second"), null
            ).join();

            // then
            assertThat(emitted).hasValue(2);
            assertThat(completedExceptionally).hasValue(1);
            assertThat(completed).hasValue(1);
        }

        @Test
        void exceptionsInUpdateInterceptorReturnFailedFuture() {
            // given
            MessageDispatchInterceptor<SubscriptionQueryUpdateMessage> failingInterceptor =
                    (message, context, chain) -> {
                        throw new MockException("Simulating failure in update interceptor");
                    };

            InterceptingQueryBus testSubjectWithFailingInterceptor = new InterceptingQueryBus(
                    delegateQueryBus,
                    List.of(),
                    List.of(),
                    List.of(failingInterceptor)
            );

            QueryMessage testQuery = new GenericQueryMessage(
                    TEST_QUERY_TYPE, "test"
            );
            GenericSubscriptionQueryUpdateMessage updateMessage =
                    new GenericSubscriptionQueryUpdateMessage(TEST_RESPONSE_TYPE, "update");

            // when
            CompletableFuture<Void> result = testSubjectWithFailingInterceptor.emitUpdate(
                    query -> query.equals(testQuery),
                    () -> updateMessage,
                    null
            );

            // then
            assertTrue(result.isCompletedExceptionally(),
                       "Expected result to be completed exceptionally");
            assertInstanceOf(MockException.class, result.exceptionNow());
        }
    }

    @Test
    void describeToProvidesDelegateInformation() {
        // given
        org.axonframework.common.infra.MockComponentDescriptor mockComponentDescriptor =
                new org.axonframework.common.infra.MockComponentDescriptor();

        // when
        testSubject.describeTo(mockComponentDescriptor);

        // then
        Map<String, Object> properties = mockComponentDescriptor.getDescribedProperties();
        assertThat(properties).containsKey("delegate");
        assertThat((Object) mockComponentDescriptor.getProperty("delegate")).isEqualTo(delegateQueryBus);
        assertThat(properties).containsKey("dispatchInterceptors");
        assertThat(properties).containsKey("handlerInterceptors");
        assertThat(properties).containsKey("updateDispatchInterceptors");
    }

    /**
     * A recording query handler that stores all received queries for later assertion.
     */
    private static class RecordingQueryHandler implements QueryHandler {

        private final List<QueryMessage> recordedQueries = new ArrayList<>();

        @NonNull
        @Override
        public MessageStream<QueryResponseMessage> handle(@NonNull QueryMessage message,
                                                          @NonNull ProcessingContext context) {
            recordedQueries.add(message);
            return MessageStream.just(new GenericQueryResponseMessage(TEST_RESPONSE_TYPE, "ok"));
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
     * <p>
     * <b>Example flow with two interception:</b>
     * <ol>
     *   <li>Interceptor1 REQUEST: adds "dispatch1" -> "value-0"</li>
     *   <li>Interceptor2 REQUEST: adds "dispatch2" -> "value-0"</li>
     *   <li>Handler executes</li>
     *   <li>Interceptor2 RESPONSE: adds "dispatch2" -> "value-1"</li>
     *   <li>Interceptor1 RESPONSE: adds "dispatch1" -> "value-1"</li>
     * </ol>
     */
    private record AddMetadataCountInterceptor<M extends Message>(String key, String value)
            implements MessageHandlerInterceptor<M>, MessageDispatchInterceptor<M> {

        @Override
        @NonNull
        public MessageStream<?> interceptOnDispatch(@NonNull M message,
                                                    @Nullable ProcessingContext context,
                                                    @NonNull MessageDispatchInterceptorChain<M> interceptorChain) {
            // STEP 1: Modify the REQUEST message before passing to next interceptor/handler
            // This proves interception can add context (correlation IDs, auth tokens, etc.) to requests
            @SuppressWarnings("unchecked")
            var intercepted = (M) message.andMetadata(Map.of(key, buildValue(message)));

            return interceptorChain
                    .proceed(intercepted, context)
                    // STEP 2: Modify the RESPONSE message after handler execution
                    // This proves interception can add info (metrics, timing, etc.) to responses
                    .mapMessage(m -> m.andMetadata(Map.of(key, buildValue(m))));
        }

        @Override
        @NonNull
        public MessageStream<?> interceptOnHandle(@NonNull M message,
                                                  @NonNull ProcessingContext context,
                                                  @NonNull MessageHandlerInterceptorChain<M> interceptorChain) {
            // STEP 1: Modify the REQUEST message before passing to handler
            @SuppressWarnings("unchecked")
            var intercepted = (M) message.andMetadata(Map.of(key, buildValue(message)));

            return interceptorChain
                    .proceed(intercepted, context)
                    // STEP 2: Modify the RESPONSE message after handler execution
                    .mapMessage(m -> m.andMetadata(Map.of(key, buildValue(m))));
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
