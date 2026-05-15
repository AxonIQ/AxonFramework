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

import org.awaitility.Awaitility;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.common.util.MockException;
import org.axonframework.messaging.core.FluxUtils;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageDispatchInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.queryhandling.GenericQueryMessage;
import org.axonframework.messaging.queryhandling.GenericQueryResponseMessage;
import org.axonframework.messaging.queryhandling.GenericSubscriptionQueryUpdateMessage;
import org.axonframework.messaging.queryhandling.QueryBus;
import org.axonframework.messaging.queryhandling.QueryHandler;
import org.axonframework.messaging.queryhandling.QueryMessage;
import org.axonframework.messaging.queryhandling.QueryResponseMessage;
import org.axonframework.messaging.queryhandling.SubscriptionQueryUpdateMessage;
import org.junit.jupiter.api.*;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Abstract test suite for comprehensive {@link QueryBus} interceptor integration testing. Tests all interceptor
 * functionality for regular queries, subscription queries, and update interceptors.
 * <p>
 * This suite translates all test cases from {@code InterceptingQueryBusTest} to integration tests, validating that
 * interceptor functionality works correctly with both {@code SimpleQueryBus} and {@code DistributedQueryBus}.
 *
 * @author Mateusz Nowak
 * @since 5.0.0
 */
public abstract class AbstractQueryInterceptorTestSuite extends AbstractQueryTestSuite {

    private final QualifiedName QUERY_NAME = new QualifiedName("test." + UUID.randomUUID() + "query");
    private final MessageType TEST_QUERY_TYPE = new MessageType(QUERY_NAME.fullName());

    @Nested
    @DisplayName("Dispatch interceptor tests")
    class DispatchInterceptorTests {

        @Test
        void dispatchModifyRequestMessage() {
            // given
            AxonConfiguration interceptingConfig = createMessagingConfigurer()
                    .build();
            QueryBus interceptingQueryBus = interceptingConfig.getComponent(QueryBus.class);

            RecordingQueryHandler handler = new RecordingQueryHandler();
            interceptingQueryBus.subscribe(QUERY_NAME, handler);

            QueryMessage testQuery = new GenericQueryMessage(TEST_QUERY_TYPE, "test");

            // when
            StepVerifier.create(FluxUtils.of(interceptingQueryBus.query(testQuery,
                                                                        StubProcessingContext.forMessage(testQuery))))
                        .expectNextCount(1)
                        .verifyComplete();

            // then - Verify REQUEST interceptor: interception added metadata to the query BEFORE handler saw it
            assertThat(handler.getRecordedQueries()).hasSize(1);

            interceptingConfig.shutdown();
        }

        @Test
        void dispatchInterceptorsModifyRequestMessage() {
            // given
            MessageDispatchInterceptor<Message> dispatchInterceptor1 = new AddMetadataCountInterceptor<>("dispatch1",
                                                                                                         "value");
            MessageDispatchInterceptor<Message> dispatchInterceptor2 = new AddMetadataCountInterceptor<>("dispatch2",
                                                                                                         "value");

            AxonConfiguration interceptingConfig = createMessagingConfigurer()
                    .registerQueryDispatchInterceptor(config -> dispatchInterceptor1)
                    .registerQueryDispatchInterceptor(config -> dispatchInterceptor2)
                    .build();
            QueryBus interceptingQueryBus = interceptingConfig.getComponent(QueryBus.class);

            RecordingQueryHandler handler = new RecordingQueryHandler();
            interceptingQueryBus.subscribe(QUERY_NAME, handler);

            QueryMessage testQuery = new GenericQueryMessage(TEST_QUERY_TYPE, "test");

            // when
            StepVerifier.create(FluxUtils.of(interceptingQueryBus.query(testQuery,
                                                                        StubProcessingContext.forMessage(testQuery))))
                        .expectNextCount(1)
                        .verifyComplete();

            // then - Verify REQUEST interception: interception added metadata to the query BEFORE handler saw it
            assertThat(handler.getRecordedQueries()).hasSize(1);
            QueryMessage recordedQuery = handler.getRecordedQueries().getFirst();
            assertTrue(recordedQuery.metadata().containsKey("dispatch1"),
                       "Expected dispatch1 interceptor to add metadata to REQUEST");
            assertTrue(recordedQuery.metadata().containsKey("dispatch2"),
                       "Expected dispatch2 interceptor to add metadata to REQUEST");

            interceptingConfig.shutdown();
        }

        @Test
        void dispatchInterceptorsModifyResponseMessage() {
            // given
            MessageDispatchInterceptor<Message> dispatchInterceptor1 = new AddMetadataCountInterceptor<>("dispatch1",
                                                                                                         "value");
            MessageDispatchInterceptor<Message> dispatchInterceptor2 = new AddMetadataCountInterceptor<>("dispatch2",
                                                                                                         "value");

            AxonConfiguration interceptingConfig = createMessagingConfigurer()
                    .registerQueryDispatchInterceptor(config -> dispatchInterceptor1)
                    .registerQueryDispatchInterceptor(config -> dispatchInterceptor2)
                    .build();
            QueryBus interceptingQueryBus = interceptingConfig.getComponent(QueryBus.class);

            RecordingQueryHandler handler = new RecordingQueryHandler();
            interceptingQueryBus.subscribe(QUERY_NAME, handler);

            QueryMessage testQuery = new GenericQueryMessage(TEST_QUERY_TYPE, "test");

            // when
            MessageStream<QueryResponseMessage> result = interceptingQueryBus.query(testQuery,
                                                                                    StubProcessingContext.forMessage(
                                                                                            testQuery));

            // then - Verify RESPONSE interceptor: interception added metadata to the response AFTER handler executed
            QueryResponseMessage response = result.first().asCompletableFuture().join().message();
            assertTrue(response.metadata().containsKey("dispatch1"),
                       "Expected dispatch1 interceptor to add metadata to RESPONSE");
            assertTrue(response.metadata().containsKey("dispatch2"),
                       "Expected dispatch2 interceptor to add metadata to RESPONSE");

            interceptingConfig.shutdown();
        }

        @Test
        void dispatchInterceptorsAreInvokedForEveryQuery() {
            // given
            AtomicInteger counter = new AtomicInteger(0);
            MessageDispatchInterceptor<Message> countingInterceptor = (message, context, chain) -> {
                counter.incrementAndGet();
                return chain.proceed(message, context);
            };

            AxonConfiguration interceptingConfig = createMessagingConfigurer()
                    .registerQueryDispatchInterceptor(config -> countingInterceptor)
                    .build();
            QueryBus interceptingQueryBus = interceptingConfig.getComponent(QueryBus.class);

            QueryHandler handler = (query, context) -> MessageStream.just(new GenericQueryResponseMessage(
                    TEST_RESPONSE_TYPE,
                    "ok"));
            interceptingQueryBus.subscribe(QUERY_NAME, handler);

            QueryMessage firstQuery = new GenericQueryMessage(TEST_QUERY_TYPE, "first");
            QueryMessage secondQuery = new GenericQueryMessage(TEST_QUERY_TYPE, "second");

            // when
            interceptingQueryBus.query(firstQuery, StubProcessingContext.forMessage(firstQuery));
            interceptingQueryBus.query(secondQuery, StubProcessingContext.forMessage(secondQuery));

            // then
            assertThat(counter.get()).isEqualTo(2);

            interceptingConfig.shutdown();
        }

        @Test
        void exceptionInDispatchInterceptorPreventsHandlerInvocation() {
            // given
            MessageDispatchInterceptor<Message> throwingInterceptor = (message, context, chain) -> {
                throw new MockException("Simulating exception in dispatch interceptor");
            };

            AxonConfiguration interceptingConfig = createMessagingConfigurer()
                    .registerQueryDispatchInterceptor(config -> throwingInterceptor)
                    .build();
            QueryBus interceptingQueryBus = interceptingConfig.getComponent(QueryBus.class);

            RecordingQueryHandler handler = new RecordingQueryHandler();
            interceptingQueryBus.subscribe(QUERY_NAME, handler);

            QueryMessage testQuery = new GenericQueryMessage(TEST_QUERY_TYPE, "test");

            // when
            MessageStream<QueryResponseMessage> result = interceptingQueryBus.query(testQuery,
                                                                                    StubProcessingContext.forMessage(
                                                                                            testQuery));

            // then
            assertTrue(result.first().asCompletableFuture().isCompletedExceptionally());
            assertInstanceOf(MockException.class, result.first().asCompletableFuture().exceptionNow());
            assertThat(handler.getRecordedQueries()).isEmpty(); // Handler should not be invoked when interceptor throws

            interceptingConfig.shutdown();
        }

        @Test
        void failedStreamInDispatchInterceptorPreventsHandlerInvocation() {
            // given
            MessageDispatchInterceptor<Message> failingInterceptor = (message, context, chain) ->
                    MessageStream.failed(new MockException("Simulating failed stream in interceptor"));

            AxonConfiguration interceptingConfig = createMessagingConfigurer()
                    .registerQueryDispatchInterceptor(config -> failingInterceptor)
                    .build();
            QueryBus interceptingQueryBus = interceptingConfig.getComponent(QueryBus.class);

            RecordingQueryHandler handler = new RecordingQueryHandler();
            interceptingQueryBus.subscribe(QUERY_NAME, handler);

            QueryMessage testQuery = new GenericQueryMessage(TEST_QUERY_TYPE, "test");

            // when
            MessageStream<QueryResponseMessage> result = interceptingQueryBus.query(testQuery,
                                                                                    StubProcessingContext.forMessage(
                                                                                            testQuery));

            // then
            assertTrue(result.first().asCompletableFuture().isCompletedExceptionally());
            assertInstanceOf(MockException.class, result.first().asCompletableFuture().exceptionNow());
            assertThat(handler.getRecordedQueries()).isEmpty(); // Handler should not be invoked when interceptor returns failed stream

            interceptingConfig.shutdown();
        }
    }

    @Nested
    @DisplayName("Handler interceptor tests")
    class HandlerInterceptorTests {

        @Test
        void handlerInterceptorsModifyRequestMessage() {
            // given
            MessageHandlerInterceptor<QueryMessage> handlerInterceptor1 = new AddMetadataCountInterceptor<>("handler1",
                                                                                                            "value");
            MessageHandlerInterceptor<QueryMessage> handlerInterceptor2 = new AddMetadataCountInterceptor<>("handler2",
                                                                                                            "value");

            AxonConfiguration interceptingConfig = createMessagingConfigurer()
                    .registerQueryHandlerInterceptor(config -> handlerInterceptor1)
                    .registerQueryHandlerInterceptor(config -> handlerInterceptor2)
                    .build();
            QueryBus interceptingQueryBus = interceptingConfig.getComponent(QueryBus.class);

            RecordingQueryHandler handler = new RecordingQueryHandler();
            interceptingQueryBus.subscribe(QUERY_NAME, handler);

            QueryMessage testQuery = new GenericQueryMessage(TEST_QUERY_TYPE, "test");
            ProcessingContext context = StubProcessingContext.forMessage(testQuery);

            // when
            interceptingQueryBus.query(testQuery, context).first().asCompletableFuture().join();

            // then - Verify REQUEST interceptor: handler interception added metadata to the query BEFORE handler saw it
            assertThat(handler.getRecordedQueries()).hasSize(1);
            QueryMessage recordedQuery = handler.getRecordedQueries().getFirst();
            assertTrue(recordedQuery.metadata().containsKey("handler1"),
                       "Expected handler1 interceptor to add metadata to REQUEST");
            assertTrue(recordedQuery.metadata().containsKey("handler2"),
                       "Expected handler2 interceptor to add metadata to REQUEST");

            interceptingConfig.shutdown();
        }

        @Test
        void handlerInterceptorsModifyResponseMessage() {
            // given
            MessageHandlerInterceptor<QueryMessage> handlerInterceptor1 = new AddMetadataCountInterceptor<>("handler1",
                                                                                                            "value");
            MessageHandlerInterceptor<QueryMessage> handlerInterceptor2 = new AddMetadataCountInterceptor<>("handler2",
                                                                                                            "value");

            AxonConfiguration interceptingConfig = createMessagingConfigurer()
                    .registerQueryHandlerInterceptor(config -> handlerInterceptor1)
                    .registerQueryHandlerInterceptor(config -> handlerInterceptor2)
                    .build();
            QueryBus interceptingQueryBus = interceptingConfig.getComponent(QueryBus.class);

            RecordingQueryHandler handler = new RecordingQueryHandler();
            interceptingQueryBus.subscribe(QUERY_NAME, handler);

            QueryMessage testQuery = new GenericQueryMessage(TEST_QUERY_TYPE, "test");
            ProcessingContext context = StubProcessingContext.forMessage(testQuery);

            // when
            MessageStream<QueryResponseMessage> result = interceptingQueryBus.query(testQuery, context);

            // then - Verify RESPONSE interception: handler interception added metadata to the response AFTER handler executed
            QueryResponseMessage response = result.first().asCompletableFuture().join().message();
            assertTrue(response.metadata().containsKey("handler1"),
                       "Expected handler1 interceptor to add metadata to RESPONSE");
            assertTrue(response.metadata().containsKey("handler2"),
                       "Expected handler2 interceptor to add metadata to RESPONSE");

            interceptingConfig.shutdown();
        }

        @Test
        void handlerInterceptorsAreInvokedForEveryQuery() {
            // given
            AtomicInteger counter = new AtomicInteger(0);
            MessageHandlerInterceptor<QueryMessage> countingInterceptor = (message, context, chain) -> {
                counter.incrementAndGet();
                return chain.proceed(message, context);
            };

            AxonConfiguration interceptingConfig = createMessagingConfigurer()
                    .registerQueryHandlerInterceptor(config -> countingInterceptor)
                    .build();
            QueryBus interceptingQueryBus = interceptingConfig.getComponent(QueryBus.class);

            QueryHandler handler = (query, context) -> MessageStream.just(new GenericQueryResponseMessage(
                    TEST_RESPONSE_TYPE,
                    "ok"));
            interceptingQueryBus.subscribe(QUERY_NAME, handler);

            QueryMessage firstQuery = new GenericQueryMessage(TEST_QUERY_TYPE, "first");
            QueryMessage secondQuery = new GenericQueryMessage(TEST_QUERY_TYPE, "second");

            // when
            interceptingQueryBus.query(firstQuery, StubProcessingContext.forMessage(firstQuery)).first()
                                .asCompletableFuture().join();
            interceptingQueryBus.query(secondQuery, StubProcessingContext.forMessage(secondQuery)).first()
                                .asCompletableFuture().join();

            // then
            assertThat(counter.get()).isEqualTo(2);

            interceptingConfig.shutdown();
        }

        @Test
        void exceptionsInHandlerReturnFailedStream() {
            // given

            AxonConfiguration interceptingConfig = createMessagingConfigurer()
                    .build();
            QueryBus interceptingQueryBus = interceptingConfig.getComponent(QueryBus.class);

            QueryHandler handler = (query, context) -> MessageStream.failed(new MockException(
                    "Simulating failure in interceptor"));
            interceptingQueryBus.subscribe(QUERY_NAME, handler);

            QueryMessage testQuery = new GenericQueryMessage(TEST_QUERY_TYPE, "Request");
            ProcessingContext context = StubProcessingContext.forMessage(testQuery);

            // when
            var result = interceptingQueryBus.query(testQuery, context);

            // then
            Awaitility.await().untilAsserted(() -> {
                assertTrue(result.isCompleted());
                assertTrue(result.error().isPresent());
            });

            interceptingConfig.shutdown();
        }

        @Test
        void exceptionsInHandlerInterceptorReturnFailedStream() {
            // given
            MessageHandlerInterceptor<QueryMessage> failingInterceptor = (message, context, chain) -> MessageStream.failed(
                    new MockException("Simulating failure in interceptor"));

            AxonConfiguration interceptingConfig = createMessagingConfigurer()
                    .registerQueryHandlerInterceptor(config -> failingInterceptor)
                    .build();
            QueryBus interceptingQueryBus = interceptingConfig.getComponent(QueryBus.class);

            QueryHandler handler = (query, context) -> MessageStream.just(new GenericQueryResponseMessage(
                    TEST_RESPONSE_TYPE,
                    "ok"));
            interceptingQueryBus.subscribe(QUERY_NAME, handler);

            QueryMessage testQuery = new GenericQueryMessage(TEST_QUERY_TYPE, "Request");
            ProcessingContext context = StubProcessingContext.forMessage(testQuery);

            // when
            var result = interceptingQueryBus.query(testQuery, context);

            // then
            Awaitility.await().untilAsserted(() -> {
                assertTrue(result.isCompleted());
                assertTrue(result.error().isPresent());
            });

            interceptingConfig.shutdown();
        }

        @Test
        void exceptionsInDispatchInterceptorReturnFailedStream() {
            // given
            MessageDispatchInterceptor<Message> failingInterceptor = (message, context, chain) -> {
                throw new MockException("Simulating failure in interceptor");
            };

            AxonConfiguration interceptingConfig = createMessagingConfigurer()
                    .registerDispatchInterceptor(config -> failingInterceptor)
                    .build();
            QueryBus interceptingQueryBus = interceptingConfig.getComponent(QueryBus.class);

            QueryHandler handler = (query, context) -> MessageStream.just(new GenericQueryResponseMessage(
                    TEST_RESPONSE_TYPE,
                    "ok"));
            interceptingQueryBus.subscribe(QUERY_NAME, handler);

            QueryMessage testQuery = new GenericQueryMessage(TEST_QUERY_TYPE, "Request");
            ProcessingContext context = StubProcessingContext.forMessage(testQuery);

            // when
            var result = interceptingQueryBus.query(testQuery, context);

            // then
            assertTrue(result.error().isPresent());
            assertInstanceOf(MockException.class, result.error().get());

            interceptingConfig.shutdown();
        }
    }

    @Nested
    @DisplayName("Subscription query interceptor tests")
    class SubscriptionQueryInterceptorTests {

        @Test
        void subscriptionQueryDelegatesToUnderlyingBus() {
            // given
            QueryHandler handler = (query, context) -> MessageStream.just(new GenericQueryResponseMessage(
                    TEST_RESPONSE_TYPE,
                    "initial"));

            QueryBus testQueryBus = queryBus();
            testQueryBus.subscribe(QUERY_NAME, handler);

            QueryMessage testQuery = new GenericQueryMessage(
                    TEST_QUERY_TYPE, "test"
            );
            ProcessingContext context = StubProcessingContext.forMessage(testQuery);

            // when
            MessageStream<QueryResponseMessage> result = testQueryBus.subscriptionQuery(testQuery, context, 10);

            // then
            QueryResponseMessage response = result.first().asCompletableFuture().join().message();
            assertThat(response.payloadAs(String.class)).isEqualTo("initial");
        }

        @Test
        void subscriptionQueryHandlerInterceptorsApplied() {
            // given
            MessageHandlerInterceptor<QueryMessage> handlerInterceptor1 = new AddMetadataCountInterceptor<>("handler1",
                                                                                                            "value");
            MessageHandlerInterceptor<QueryMessage> handlerInterceptor2 = new AddMetadataCountInterceptor<>("handler2",
                                                                                                            "value");

            AxonConfiguration interceptingConfig = createMessagingConfigurer()
                    .registerQueryHandlerInterceptor(config -> handlerInterceptor1)
                    .registerQueryHandlerInterceptor(config -> handlerInterceptor2)
                    .build();
            QueryBus interceptingQueryBus = interceptingConfig.getComponent(QueryBus.class);

            RecordingQueryHandler handler = new RecordingQueryHandler();
            interceptingQueryBus.subscribe(QUERY_NAME, handler);

            QueryMessage testQuery = new GenericQueryMessage(TEST_QUERY_TYPE, "test");
            ProcessingContext context = StubProcessingContext.forMessage(testQuery);

            // when
            MessageStream<QueryResponseMessage> result = interceptingQueryBus.subscriptionQuery(testQuery, context, 10);

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

            interceptingConfig.shutdown();
        }

        @Test
        void subscriptionQueryDispatchInterceptorsApplied() {
            // given
            MessageDispatchInterceptor<Message> dispatchInterceptor1 = new AddMetadataCountInterceptor<>("dispatch1",
                                                                                                         "value");
            MessageDispatchInterceptor<Message> dispatchInterceptor2 = new AddMetadataCountInterceptor<>("dispatch2",
                                                                                                         "value");

            AxonConfiguration interceptingConfig = createMessagingConfigurer()
                    .registerQueryDispatchInterceptor(config -> dispatchInterceptor1)
                    .registerQueryDispatchInterceptor(config -> dispatchInterceptor2)
                    .build();
            QueryBus interceptingQueryBus = interceptingConfig.getComponent(QueryBus.class);

            RecordingQueryHandler handler = new RecordingQueryHandler();
            interceptingQueryBus.subscribe(QUERY_NAME, handler);

            QueryMessage testQuery = new GenericQueryMessage(
                    TEST_QUERY_TYPE, "test"
            );
            ProcessingContext context = StubProcessingContext.forMessage(testQuery);

            // when
            MessageStream<QueryResponseMessage> result = interceptingQueryBus.subscriptionQuery(testQuery, context, 10);

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

            interceptingConfig.shutdown();
        }

        @Test
        void subscribeToUpdatesDispatchInterceptorsApplied() {
            // given
            AtomicInteger interceptor1Invocations = new AtomicInteger(0);
            AtomicInteger interceptor2Invocations = new AtomicInteger(0);

            MessageDispatchInterceptor<Message> dispatchInterceptor1 = (message, context, chain) -> {
                interceptor1Invocations.incrementAndGet();
                return chain.proceed(message.andMetadata(Map.of("dispatch1", "value")), context);
            };
            MessageDispatchInterceptor<Message> dispatchInterceptor2 = (message, context, chain) -> {
                interceptor2Invocations.incrementAndGet();
                return chain.proceed(message.andMetadata(Map.of("dispatch2", "value")), context);
            };

            AxonConfiguration interceptingConfig = createMessagingConfigurer()
                    .registerQueryDispatchInterceptor(config -> dispatchInterceptor1)
                    .registerQueryDispatchInterceptor(config -> dispatchInterceptor2)
                    .build();
            QueryBus interceptingQueryBus = interceptingConfig.getComponent(QueryBus.class);

            QueryMessage testQuery = new GenericQueryMessage(
                    TEST_QUERY_TYPE, "test"
            );

            // when
            MessageStream<SubscriptionQueryUpdateMessage> updateStream =
                    interceptingQueryBus.subscribeToUpdates(testQuery, 10);

            // then - Verify dispatch interception were invoked
            assertThat(interceptor1Invocations.get()).isEqualTo(1);
            assertThat(interceptor2Invocations.get()).isEqualTo(1);

            // Verify that the update stream was created successfully
            assertNotNull(updateStream, "Expected subscribeToUpdates to return a non-null stream");

            interceptingConfig.shutdown();
        }
    }

    @Nested
    @DisplayName("Subscription update interception tests")
    class SubscriptionUpdateInterceptorTests {

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

            @SuppressWarnings("unchecked")
            MessageDispatchInterceptor<Message> genericInterceptor1 =
                    (MessageDispatchInterceptor<Message>) (MessageDispatchInterceptor<?>) updateInterceptor1;
            @SuppressWarnings("unchecked")
            MessageDispatchInterceptor<Message> genericInterceptor2 =
                    (MessageDispatchInterceptor<Message>) (MessageDispatchInterceptor<?>) updateInterceptor2;

            AxonConfiguration interceptingConfig = createMessagingConfigurer()
                    .registerDispatchInterceptor(config -> genericInterceptor1)
                    .registerDispatchInterceptor(config -> genericInterceptor2)
                    .build();
            QueryBus interceptingQueryBus = interceptingConfig.getComponent(QueryBus.class);

            GenericSubscriptionQueryUpdateMessage updateMessage =
                    new GenericSubscriptionQueryUpdateMessage(TEST_RESPONSE_TYPE, "update");

            Predicate<QueryMessage> matchAll = query -> true;

            // when
            CompletableFuture<Void> result = interceptingQueryBus.emitUpdate(
                    matchAll,
                    () -> updateMessage,
                    null
            );

            // then
            assertDoesNotThrow(result::join);
            assertThat(interceptor1Invocations.get()).isEqualTo(1);
            assertThat(interceptor2Invocations.get()).isEqualTo(1);

            interceptingConfig.shutdown();
        }

        @Test
        void emptyUpdateInterceptorListBypassesInterception() {
            // given
            QueryBus testQueryBus = queryBus();

            GenericSubscriptionQueryUpdateMessage updateMessage =
                    new GenericSubscriptionQueryUpdateMessage(TEST_RESPONSE_TYPE, "update");

            Predicate<QueryMessage> matchAll = query -> true;

            // when
            CompletableFuture<Void> result = testQueryBus.emitUpdate(
                    matchAll,
                    () -> updateMessage,
                    null
            );

            // then
            // Verify that emitUpdate works without interception
            assertDoesNotThrow(result::join);
        }

        @Test
        void exceptionsInUpdateInterceptorReturnFailedFuture() {
            // given
            MessageDispatchInterceptor<SubscriptionQueryUpdateMessage> failingInterceptor =
                    (message, context, chain) -> {
                        throw new MockException("Simulating failure in update interceptor");
                    };

            @SuppressWarnings("unchecked")
            MessageDispatchInterceptor<Message> genericFailingInterceptor =
                    (MessageDispatchInterceptor<Message>) (MessageDispatchInterceptor<?>) failingInterceptor;

            AxonConfiguration interceptingConfig = createMessagingConfigurer()
                    .registerDispatchInterceptor(config -> genericFailingInterceptor)
                    .build();
            QueryBus interceptingQueryBus = interceptingConfig.getComponent(QueryBus.class);

            QueryMessage testQuery = new GenericQueryMessage(
                    TEST_QUERY_TYPE, "test"
            );
            GenericSubscriptionQueryUpdateMessage updateMessage =
                    new GenericSubscriptionQueryUpdateMessage(TEST_RESPONSE_TYPE, "update");

            // when
            CompletableFuture<Void> result = interceptingQueryBus.emitUpdate(
                    query -> query.equals(testQuery),
                    () -> updateMessage,
                    null
            );

            // then
            assertTrue(result.isCompletedExceptionally(),
                       "Expected result to be completed exceptionally");
            assertInstanceOf(MockException.class, result.exceptionNow());

            interceptingConfig.shutdown();
        }
    }

    @Test
    void subscribeAllowsHandlingQueries() {
        // given
        QueryHandler handler = (query, context) -> MessageStream.just(new GenericQueryResponseMessage(
                TEST_RESPONSE_TYPE,
                "subscribed-ok"));

        QueryBus testQueryBus = queryBus();

        // when
        testQueryBus.subscribe(QUERY_NAME, handler);

        QueryMessage testQuery = new GenericQueryMessage(TEST_QUERY_TYPE, "test");
        MessageStream<QueryResponseMessage> result = testQueryBus.query(testQuery,
                                                                        StubProcessingContext.forMessage(testQuery));

        // then
        QueryResponseMessage response = result.first().asCompletableFuture().join().message();
        assertThat(response.payloadAs(String.class)).isEqualTo("subscribed-ok");
    }

    @Test
    void describeToProvidesDelegateInformation() {
        // given
        org.axonframework.common.infra.MockComponentDescriptor mockComponentDescriptor =
                new org.axonframework.common.infra.MockComponentDescriptor();

        QueryBus testQueryBus = queryBus();

        // when
        testQueryBus.describeTo(mockComponentDescriptor);

        // then
        Map<String, Object> properties = mockComponentDescriptor.getDescribedProperties();
        assertThat(properties).containsKey("delegate");
    }
}
