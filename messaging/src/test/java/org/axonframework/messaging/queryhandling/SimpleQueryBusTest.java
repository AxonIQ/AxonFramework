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
package org.axonframework.messaging.queryhandling;

import org.axonframework.common.infra.MockComponentDescriptor;
import org.axonframework.common.util.MockException;
import org.axonframework.messaging.core.FluxUtils;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.TransactionalUnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkTestUtils;
import org.axonframework.messaging.core.unitofwork.transaction.TransactionManager;
import org.junit.jupiter.api.*;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.util.concurrent.Queues;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link SimpleQueryBus}.
 *
 * @author Marc Gathier
 * @author Steven van Beelen
 */
class SimpleQueryBusTest {

    private static final QualifiedName QUERY_NAME = new QualifiedName("query");
    private static final MessageType QUERY_TYPE = new MessageType(QUERY_NAME);
    private static final String QUERY_PAYLOAD = "query";
    private static final QualifiedName RESPONSE_NAME = new QualifiedName(String.class);
    private static final MessageType RESPONSE_TYPE = new MessageType(RESPONSE_NAME);
    private static final QualifiedName UPDATE_NAME = new QualifiedName("update");
    private static final MessageType UPDATE_PAYLOAD_TYPE = new MessageType(UPDATE_NAME);
    private static final String UPDATE_PAYLOAD = "Update";

    private static final QueryHandler SINGLE_RESPONSE_HANDLER = (query, context) -> {
        QueryResponseMessage response = new GenericQueryResponseMessage(RESPONSE_TYPE, query.payload() + "1234");
        return MessageStream.just(response);
    };
    private static final QueryHandler MULTI_RESPONSE_HANDLER = (query, context) -> {
        QueryResponseMessage responseOne = new GenericQueryResponseMessage(RESPONSE_TYPE, query.payload() + "1234");
        QueryResponseMessage responseTwo = new GenericQueryResponseMessage(RESPONSE_TYPE, query.payload() + "5678");
        return MessageStream.fromIterable(List.of(responseOne, responseTwo));
    };

    private SimpleQueryBus testSubject;

    private TransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        transactionManager = mock(TransactionManager.class);

        UnitOfWorkFactory unitOfWorkFactory =
                new TransactionalUnitOfWorkFactory(transactionManager, UnitOfWorkTestUtils.SIMPLE_FACTORY);

        testSubject = new SimpleQueryBus(unitOfWorkFactory);
    }

    @Nested
    class HandlerSubscriptions {

        @Test
        void subscribeAddsQueryHandlers() {
            // given...
            MockComponentDescriptor testDescriptor = new MockComponentDescriptor();
            // when first subscription...
            testSubject.subscribe(QUERY_NAME, SINGLE_RESPONSE_HANDLER);
            // then...
            testSubject.describeTo(testDescriptor);
            Map<QualifiedName, QueryHandler> subscriptions = testDescriptor.getProperty("subscriptions");
            assertThat(subscriptions.size()).isEqualTo(1);
            assertThat(subscriptions).containsValue(SINGLE_RESPONSE_HANDLER);
            // when second subscription with different query name...
            testSubject.subscribe(new QualifiedName("test2"), SINGLE_RESPONSE_HANDLER);
            // then...
            testSubject.describeTo(testDescriptor);
            subscriptions = testDescriptor.getProperty("subscriptions");
            assertThat(subscriptions.size()).isEqualTo(2);
            assertThat(subscriptions).containsValue(SINGLE_RESPONSE_HANDLER);

            // when third subscription with the same query name...
            testSubject.subscribe(QUERY_NAME, SINGLE_RESPONSE_HANDLER);
            // then...
            testSubject.describeTo(testDescriptor);
            subscriptions = testDescriptor.getProperty("subscriptions");
            assertThat(subscriptions.size()).isEqualTo(2);
        }

        @Test
        void subscribingSameHandlerTwiceDoesNotThrowDuplicateQueryHandlerSubscriptionException() {
            // given...
            testSubject.subscribe(QUERY_NAME, SINGLE_RESPONSE_HANDLER);
            // when/then...
            assertDoesNotThrow(() -> testSubject.subscribe(QUERY_NAME, SINGLE_RESPONSE_HANDLER));
        }

        @Test
        void subscribingDifferentHandlerForSameNameThrowsDuplicateQueryHandlerSubscriptionException() {
            // given...
            QueryHandler testHandler = (query, context) -> MessageStream.empty().cast();
            testSubject.subscribe(QUERY_NAME, SINGLE_RESPONSE_HANDLER);
            // when/then...
            assertThatThrownBy(() -> testSubject.subscribe(QUERY_NAME, testHandler))
                    .isInstanceOf(DuplicateQueryHandlerSubscriptionException.class);
        }
    }

    @Nested
    class DirectQuery {

        @Test
        void directQueryForUnknownQueryNameReturnsFailedNoHandlerForQueryExceptionStream() {
            // given...
            QueryMessage testQuery = new GenericQueryMessage(QUERY_TYPE, QUERY_PAYLOAD);
            // when...
            MessageStream<QueryResponseMessage> result = testSubject.query(testQuery, null);
            // then...
            assertThat(result.isCompleted()).isTrue();
            Optional<Throwable> optionalError = result.error();
            assertThat(optionalError).isPresent();
            assertThat(optionalError.get()).isInstanceOf(NoHandlerForQueryException.class);
        }

        @Test
        void directQueryReturnsMessageStreamWithSingleEntry() {
            // given...
            QueryMessage testQuery = new GenericQueryMessage(QUERY_TYPE, QUERY_PAYLOAD);
            testSubject.subscribe(QUERY_NAME, SINGLE_RESPONSE_HANDLER);
            // when...
            MessageStream<QueryResponseMessage> result = testSubject.query(testQuery, null);
            // then...
            assertThat(result.isCompleted()).isFalse();
            assertThat(result.hasNextAvailable()).isTrue();
            Optional<MessageStream.Entry<QueryResponseMessage>> nextResponse = result.next();
            assertThat(nextResponse).isPresent();
            assertThat(nextResponse.get().message().payload()).isEqualTo("query1234");
            assertThat(result.isCompleted()).isFalse();  // not yet fully consumed, so not completed
            assertThat(result.hasNextAvailable()).isFalse();  // results in full consumption
            assertThat(result.isCompleted()).isTrue();
        }

        @Test
        void directQueryReturnsMessageStreamWithMultipleEntries() {
            // given...
            QueryMessage testQuery = new GenericQueryMessage(QUERY_TYPE, QUERY_PAYLOAD);
            testSubject.subscribe(QUERY_NAME, MULTI_RESPONSE_HANDLER);
            // when...
            MessageStream<QueryResponseMessage> result = testSubject.query(testQuery, null);
            // then...
            Optional<MessageStream.Entry<QueryResponseMessage>> nextResponse = result.next();
            assertThat(nextResponse).isPresent();
            assertThat(nextResponse.get().message().payload()).isEqualTo("query1234");
            nextResponse = result.next();
            assertThat(nextResponse).isPresent();
            assertThat(nextResponse.get().message().payload()).isEqualTo("query5678");
            assertThat(result.hasNextAvailable()).isFalse();
            assertThat(result.isCompleted()).isTrue();
        }

        @Test
        void directQueryReturnsFailedMessageStreamFromThrowingQueryHandler() {
            // given...
            QueryMessage testQuery = new GenericQueryMessage(QUERY_TYPE, QUERY_PAYLOAD);
            QueryHandler failingHandler = (query, context) -> {
                throw new MockException("Mock");
            };
            testSubject.subscribe(QUERY_NAME, failingHandler);
            // when...
            MessageStream<QueryResponseMessage> result = testSubject.query(testQuery, null);
            // then...
            assertThat(result.isCompleted()).isTrue();
            assertThat(result.hasNextAvailable()).isFalse();
            Optional<Throwable> optionalError = result.error();
            assertThat(optionalError).isPresent();
            assertThat(optionalError.get()).isInstanceOf(MockException.class);
            assertThat(optionalError.get().getMessage()).isEqualTo("Mock");
        }

        @Test
        void directQueryReturnsFailedMessageStreamFromFailingStreamResultQueryHandler() {
            // given...
            QueryMessage testQuery = new GenericQueryMessage(QUERY_TYPE, QUERY_PAYLOAD);
            QueryHandler failingHandler = (query, context) -> MessageStream.failed(new MockException("Mock"));
            testSubject.subscribe(QUERY_NAME, failingHandler);
            // when...
            MessageStream<QueryResponseMessage> result = testSubject.query(testQuery, null);
            // then...
            assertThat(result.isCompleted()).isTrue();
            assertThat(result.hasNextAvailable()).isFalse();
            Optional<Throwable> optionalError = result.error();
            assertThat(optionalError).isPresent();
            assertThat(optionalError.get()).isInstanceOf(MockException.class);
            assertThat(optionalError.get().getMessage()).isEqualTo("Mock");
        }

        @Test
        void directQueryResultsInEmptyMessageStream() {
            // given...
            QueryMessage testQuery = new GenericQueryMessage(QUERY_TYPE, QUERY_PAYLOAD);
            testSubject.subscribe(QUERY_NAME, (query, context) -> MessageStream.empty().cast());
            // when...
            MessageStream<QueryResponseMessage> result = testSubject.query(testQuery, null);
            // then...
            assertThat(result.isCompleted()).isTrue();
            assertThat(result.error()).isNotPresent();
            assertThat(result.hasNextAvailable()).isFalse();
        }

        @Test
        void directQuerySingleResponseWithTransaction() throws Exception {
            // given...
            QueryMessage testQuery = new GenericQueryMessage(QUERY_TYPE, QUERY_PAYLOAD);
            testSubject.subscribe(QUERY_NAME, SINGLE_RESPONSE_HANDLER);
            // when...
            CompletableFuture<Object> result = testSubject.query(testQuery, null)
                                                          .first()
                                                          .asCompletableFuture()
                                                          .thenApply(entry -> entry.message().payload());
            // then...
            assertEquals("query1234", result.get());
            verify(transactionManager).attachToProcessingLifecycle(any(UnitOfWork.class));
        }

        @Test
        void directQueryMultipleResponsesWithTransaction() throws Exception {
            // given...
            QueryMessage testQuery = new GenericQueryMessage(QUERY_TYPE, QUERY_PAYLOAD);
            testSubject.subscribe(QUERY_NAME, MULTI_RESPONSE_HANDLER);
            // when...
            CompletableFuture<List<String>> result =
                    testSubject.query(testQuery, null)
                               .reduce(new ArrayList<>(), (results, entry) -> {
                                   results.add(entry.message().payloadAs(String.class));
                                   return results;
                               });
            // then...
            assertTrue(result.isDone());
            List<String> completedResult = result.get();
            assertTrue(completedResult.contains("query1234"));
            assertTrue(completedResult.contains("query5678"));
            verify(transactionManager).attachToProcessingLifecycle(any(UnitOfWork.class));
        }
    }

    @Nested
    class StreamingQuery {

        @Test
        void streamingQueryForUnknownQueryNameReturnsFailedNoHandlerForQueryExceptionPublisherStream() {
            // given...
            QueryMessage testQuery = new GenericQueryMessage(QUERY_TYPE, QUERY_PAYLOAD);
            // when/then...
            MessageStream<QueryResponseMessage> actual = testSubject.query(testQuery, null);

            await().atMost(Duration.ofSeconds(1)).until(actual::isCompleted);
            assertThat(actual.error()).isPresent();
            assertThat(actual.error()).containsInstanceOf(NoHandlerForQueryException.class);
        }

        @Test
        void streamingQueryReturnsPublisherWithSingleEntry() {
            // given...
            QueryMessage testQuery = new GenericQueryMessage(QUERY_TYPE, QUERY_PAYLOAD);
            testSubject.subscribe(QUERY_NAME, SINGLE_RESPONSE_HANDLER);
            // when/then...
            MessageStream<QueryResponseMessage> actual = testSubject.query(testQuery, null);

            await().until(actual::hasNextAvailable);
            assertThat(actual.next().map(e -> e.message().payload())).contains("query1234");
            await().until(() -> !actual.hasNextAvailable());
            await().until(actual::isCompleted);
        }

        @Test
        void streamingQueryReturnsFailedPublisherFromFailingStreamResultQueryHandler() {
            // given...
            QueryMessage testQuery = new GenericQueryMessage(QUERY_TYPE, QUERY_PAYLOAD);
            QueryHandler failingHandler = (query, context) -> MessageStream.failed(new MockException("Mock"));
            testSubject.subscribe(QUERY_NAME, failingHandler);
            // when/then...
            MessageStream<QueryResponseMessage> actual = testSubject.query(testQuery, null);

            await().atMost(1, TimeUnit.SECONDS).until(actual::isCompleted);
            assertThat(actual.error()).isPresent();
            assertThat(actual.error()).containsInstanceOf(MockException.class);
            assertThat(actual.error().map(Throwable::getMessage)).contains("Mock");
        }

        @Test
        void streamingQueryResultsInEmptyMessageStream() {
            // given...
            QueryMessage testQuery = new GenericQueryMessage(QUERY_TYPE, QUERY_PAYLOAD);
            testSubject.subscribe(QUERY_NAME, (query, context) -> MessageStream.empty().cast());
            // when/then...
            MessageStream<QueryResponseMessage> actual = testSubject.query(testQuery, null);

            await().atMost(1, TimeUnit.SECONDS).until(actual::isCompleted);
            assertThat(actual.error()).isEmpty();
        }
    }

    @Nested
    class SubscriptionQuery {

        @Test
        void subscriptionQueryForUnknownQueryNameReturnsFailedNoHandlerForQueryExceptionInitialResult() {
            // given...
            QueryMessage testQuery =
                    new GenericQueryMessage(QUERY_TYPE, QUERY_PAYLOAD);
            // when...
            MessageStream<QueryResponseMessage> result =
                    testSubject.subscriptionQuery(testQuery, null, Queues.SMALL_BUFFER_SIZE);
            // then...
            StepVerifier.create(FluxUtils.of(result).map(MessageStream.Entry::message))
                        .expectError(NoHandlerForQueryException.class)
                        .verify();
        }

        @Test
        void subscriptionQueryInitialResultReturnsSingleEntry() {
            // given...
            QueryMessage testQuery =
                    new GenericQueryMessage(QUERY_TYPE, QUERY_PAYLOAD);
            testSubject.subscribe(QUERY_NAME, SINGLE_RESPONSE_HANDLER);
            // when...
            MessageStream<QueryResponseMessage> result =
                    testSubject.subscriptionQuery(testQuery, null, Queues.SMALL_BUFFER_SIZE);
            testSubject.completeSubscriptions(testQuery::equals, null);
            // then...
            StepVerifier.create(FluxUtils.of(result).map(MessageStream.Entry::message))
                        .expectNextMatches(response -> Objects.equals(response.payload(), "query1234"))
                        .verifyComplete();
        }

        @Test
        void subscriptionQueryInitialResultReturnsMultipleEntries() {
            // given...
            QueryMessage testQuery =
                    new GenericQueryMessage(QUERY_TYPE, QUERY_PAYLOAD);
            testSubject.subscribe(QUERY_NAME, MULTI_RESPONSE_HANDLER);
            // when...
            MessageStream<QueryResponseMessage> result =
                    testSubject.subscriptionQuery(testQuery, null, Queues.SMALL_BUFFER_SIZE);
            testSubject.completeSubscriptions(testQuery::equals, null);

            // then...
            StepVerifier.create(FluxUtils.of(result).map(MessageStream.Entry::message))
                        .expectNextMatches(response -> Objects.equals(response.payload(), "query1234"))
                        .expectNextMatches(response -> Objects.equals(response.payload(), "query5678"))
                        .verifyComplete();
        }

        @Test
        void subscriptionQueryInitialResultReturnsEmptyMessageStream() {
            // given...
            QueryMessage testQuery =
                    new GenericQueryMessage(QUERY_TYPE, QUERY_PAYLOAD);
            testSubject.subscribe(QUERY_NAME, (query, context) -> MessageStream.empty().cast());
            // when...
            MessageStream<QueryResponseMessage> result =
                    testSubject.subscriptionQuery(testQuery, null, Queues.SMALL_BUFFER_SIZE);
            testSubject.completeSubscriptions(testQuery::equals, null);
            // when/then...
            StepVerifier.create(FluxUtils.of(result).map(MessageStream.Entry::message))
                        .verifyComplete();
        }

        @Test
        void subscriptionQueryInitialResultReportsException() {
            // given...
            QueryMessage testQuery =
                    new GenericQueryMessage(QUERY_TYPE, QUERY_PAYLOAD);
            QueryHandler failingHandler = (query, context) -> {
                throw new MockException("Mock");
            };
            testSubject.subscribe(QUERY_NAME, failingHandler);
            // when...
            MessageStream<QueryResponseMessage> result =
                    testSubject.subscriptionQuery(testQuery, null, Queues.SMALL_BUFFER_SIZE);
            // then...
            StepVerifier.create(FluxUtils.of(result).map(MessageStream.Entry::message))
                        .expectError(MockException.class)
                        .verify();
        }
    }

    @Nested
    class SubscribeToUpdates {

        @Test
        void subscribeToUpdatesForSameQueryTwiceThrowsSubscriptionQueryAlreadyRegisteredException() {
            // given...
            QueryMessage testQuery =
                    new GenericQueryMessage(QUERY_TYPE, QUERY_PAYLOAD);
            testSubject.subscribeToUpdates(testQuery, Queues.SMALL_BUFFER_SIZE);
            // when/then...
            assertThatThrownBy(() -> testSubject.subscribeToUpdates(testQuery, Queues.SMALL_BUFFER_SIZE))
                    .isInstanceOf(SubscriptionQueryAlreadyRegisteredException.class);
        }

        @Test
        void completingUpdateHandlerFromSubscribeToUpdatesCompletesUpdates() {
            // given...
            QueryMessage testQuery =
                    new GenericQueryMessage(QUERY_TYPE, QUERY_PAYLOAD);
            SubscriptionQueryUpdateMessage updateMessage =
                    new GenericSubscriptionQueryUpdateMessage(UPDATE_PAYLOAD_TYPE, UPDATE_PAYLOAD);
            MessageStream<SubscriptionQueryUpdateMessage> result = testSubject.subscribeToUpdates(testQuery,
                                                                                                  Queues.SMALL_BUFFER_SIZE);
            testSubject.emitUpdate(query -> true, () -> updateMessage, null).join();
            // when...
            result.close();
            // then...
            StepVerifier.create(FluxUtils.of(result).map(MessageStream.Entry::message)
                                         .mapNotNull(Message::payload))
                        .verifyComplete();
        }
    }

    @Nested
    class EmitUpdate {

        @Test
        void emittedUpdateLandsInSubscriptionQueryUpdates() {
            // given...
            QueryMessage testQuery =
                    new GenericQueryMessage(QUERY_TYPE, QUERY_PAYLOAD);
            Predicate<QueryMessage> queryFilter =
                    query -> query.identifier().equals(testQuery.identifier());
            SubscriptionQueryUpdateMessage updateMessage =
                    new GenericSubscriptionQueryUpdateMessage(UPDATE_PAYLOAD_TYPE, UPDATE_PAYLOAD);
            testSubject.subscribe(QUERY_NAME, (query, context) -> MessageStream.empty().cast());
            MessageStream<QueryResponseMessage> responses = testSubject.subscriptionQuery(testQuery,
                                                                                          null,
                                                                                          Queues.SMALL_BUFFER_SIZE);
            // when...
            testSubject.emitUpdate(queryFilter, () -> updateMessage, null).join();
            // then...
            StepVerifier.create(FluxUtils.of(responses).map(MessageStream.Entry::message))
                        .expectNextMatches(response -> Objects.equals(response.payload(), UPDATE_PAYLOAD))
                        .verifyTimeout(Duration.ofMillis(100));
        }

        @Test
        void emittedUpdateLandsInAllMatchingSubscriptionQueryUpdates() {
            // given...
            QueryMessage testQueryOne =
                    new GenericQueryMessage(QUERY_TYPE, QUERY_PAYLOAD);
            QueryMessage testQueryTwo =
                    new GenericQueryMessage(QUERY_TYPE, QUERY_PAYLOAD);
            QueryMessage testQueryThree =
                    new GenericQueryMessage(QUERY_TYPE, QUERY_PAYLOAD);
            // Filter allows emitting update to subscription queries one and two
            Predicate<QueryMessage> queryFilter = query ->
                    query.identifier().equals(testQueryOne.identifier())
                            || query.identifier().equals(testQueryTwo.identifier());
            testSubject.subscribe(QUERY_NAME, (query, context) -> MessageStream.empty().cast());

            SubscriptionQueryUpdateMessage updateMessage =
                    new GenericSubscriptionQueryUpdateMessage(UPDATE_PAYLOAD_TYPE, UPDATE_PAYLOAD);
            MessageStream<QueryResponseMessage> firstResponses = testSubject.subscriptionQuery(testQueryOne,
                                                                                               null,
                                                                                               Queues.SMALL_BUFFER_SIZE);
            MessageStream<QueryResponseMessage> secondResponses =
                    testSubject.subscriptionQuery(testQueryTwo, null, Queues.SMALL_BUFFER_SIZE);
            MessageStream<QueryResponseMessage> thirdResponses =
                    testSubject.subscriptionQuery(testQueryThree, null, Queues.SMALL_BUFFER_SIZE);
            // when...
            testSubject.emitUpdate(queryFilter, () -> updateMessage, null).join();
            // then...
            StepVerifier.create(FluxUtils.of(firstResponses).map(MessageStream.Entry::message))
                        .expectNextMatches(response -> Objects.equals(response.payload(), UPDATE_PAYLOAD))
                        .verifyTimeout(Duration.ofMillis(100));
            StepVerifier.create(FluxUtils.of(secondResponses).map(MessageStream.Entry::message))
                        .expectNextMatches(response -> Objects.equals(response.payload(), UPDATE_PAYLOAD))
                        .verifyTimeout(Duration.ofMillis(100));
            StepVerifier.create(FluxUtils.of(thirdResponses).map(MessageStream.Entry::message))
                        .verifyTimeout(Duration.ofMillis(100));
        }

        @Test
        void emittingUpdatesConcurrently() {
            // given...
            QueryMessage testQuery =
                    new GenericQueryMessage(QUERY_TYPE, QUERY_PAYLOAD);
            SubscriptionQueryUpdateMessage updateMessage =
                    new GenericSubscriptionQueryUpdateMessage(UPDATE_PAYLOAD_TYPE, UPDATE_PAYLOAD);
            MessageStream<SubscriptionQueryUpdateMessage> updateHandler = testSubject.subscribeToUpdates(testQuery,
                                                                                                         128);
            // when...
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < 100; i++) {
                    executor.submit(() -> testSubject.emitUpdate(q -> true, () -> updateMessage, null));
                }
                executor.shutdown();
            }
            // then...
            Flux<SubscriptionQueryUpdateMessage> updates = FluxUtils.of(updateHandler)
                                                                    .map(MessageStream.Entry::message);
            StepVerifier.create(updates)
                        .expectNextCount(100)
                        .then(() -> testSubject.completeSubscriptions(query -> true, null))
                        .verifyComplete();
        }

        @Test
        void emittingUpdatesWithProcessingContextStagesEmitsToAfterCommit() {
            // given...
            AtomicBoolean filterInvoked = new AtomicBoolean(false);
            QueryMessage testQuery =
                    new GenericQueryMessage(QUERY_TYPE, QUERY_PAYLOAD);
            Predicate<QueryMessage> queryFilter =
                    query -> {
                        filterInvoked.set(true);
                        return query.identifier().equals(testQuery.identifier());
                    };
            testSubject.subscribe(QUERY_NAME, (query, context) -> MessageStream.empty().cast());
            SubscriptionQueryUpdateMessage updateMessage =
                    new GenericSubscriptionQueryUpdateMessage(UPDATE_PAYLOAD_TYPE, UPDATE_PAYLOAD);
            // We should have a subscription query for an update handler to even exist.
            MessageStream<QueryResponseMessage> result = testSubject.subscriptionQuery(testQuery,
                                                                                       null,
                                                                                       Queues.SMALL_BUFFER_SIZE);
            UnitOfWork uow = UnitOfWorkTestUtils.aUnitOfWork();
            // when emitting on invocation...
            uow.onInvocation(context -> testSubject.emitUpdate(queryFilter, () -> updateMessage, context));
            // then before the after commit phase validate the filter was not invoked yet...
            List<String> updateList = new ArrayList<>();
            FluxUtils.of(result).map(MessageStream.Entry::message).mapNotNull(m -> m.payloadAs(String.class)).subscribe(
                    updateList::add);
            assertThat(filterInvoked).isFalse();
            assertThat(updateList).isEmpty();
            // when executing the UnitOfWork, we pass the after commit phase
            uow.execute().join();
            // then we expect the update to be emitted, validated by the filter being invoked...
            assertThat(filterInvoked).isTrue();
            assertThat(updateList).isNotEmpty();
            assertThat(updateList).containsExactlyInAnyOrder(UPDATE_PAYLOAD);
        }

        @Test
        void emittingUpdatesDoesNotRetrieveUpdateWhenNoQueriesMatch() {
            // given...
            QueryMessage testQuery =
                    new GenericQueryMessage(QUERY_TYPE, QUERY_PAYLOAD);
            Predicate<QueryMessage> queryFilter = query -> false;
            AtomicBoolean updateSupplierInvoked = new AtomicBoolean(false);
            Supplier<SubscriptionQueryUpdateMessage> updateSupplier = () -> {
                updateSupplierInvoked.set(true);
                return null;
            };
            // We should have a subscription query for an update handler to even exist.
            testSubject.subscriptionQuery(testQuery, null, Queues.SMALL_BUFFER_SIZE);
            // when...
            testSubject.emitUpdate(queryFilter, updateSupplier, null).join();
            // then...
            assertThat(updateSupplierInvoked).isFalse();
        }
    }

    @Nested
    class CompleteSubscription {

        @Test
        void completingSubscriptionCompletesMatchingSubscriptionQueriesOnly() {
            // given...
            QueryMessage testQueryOne =
                    new GenericQueryMessage(QUERY_TYPE, QUERY_PAYLOAD);
            QueryMessage testQueryTwo =
                    new GenericQueryMessage(QUERY_TYPE, QUERY_PAYLOAD);
            QueryMessage testQueryThree =
                    new GenericQueryMessage(QUERY_TYPE, QUERY_PAYLOAD);
            // Filter will complete subscription queries one and two
            Predicate<QueryMessage> queryFilter = query ->
                    query.identifier().equals(testQueryOne.identifier())
                            || query.identifier().equals(testQueryTwo.identifier());
            testSubject.subscribe(QUERY_NAME, (query, context) -> MessageStream.empty().cast());

            SubscriptionQueryUpdateMessage updateMessage =
                    new GenericSubscriptionQueryUpdateMessage(UPDATE_PAYLOAD_TYPE, UPDATE_PAYLOAD);
            MessageStream<QueryResponseMessage> firstResponses = testSubject.subscriptionQuery(testQueryOne,
                                                                                               null,
                                                                                               Queues.SMALL_BUFFER_SIZE);
            MessageStream<QueryResponseMessage> secondResponses = testSubject.subscriptionQuery(testQueryTwo,
                                                                                                null,
                                                                                                Queues.SMALL_BUFFER_SIZE);
            MessageStream<QueryResponseMessage> thirdResponses = testSubject.subscriptionQuery(testQueryThree,
                                                                                               null,
                                                                                               Queues.SMALL_BUFFER_SIZE);
            // when...
            testSubject.completeSubscriptions(queryFilter, null).join();
            // then...
            testSubject.emitUpdate(query -> true, () -> updateMessage, null).join();
            StepVerifier.create(FluxUtils.of(firstResponses).map(MessageStream.Entry::message))
                        .verifyComplete();
            StepVerifier.create(FluxUtils.of(secondResponses).map(MessageStream.Entry::message))
                        .verifyComplete();
            StepVerifier.create(FluxUtils.of(thirdResponses).map(MessageStream.Entry::message))
                        .expectNextMatches(response -> Objects.equals(response.payload(), UPDATE_PAYLOAD))
                        .verifyTimeout(Duration.ofMillis(100));
        }

        @Test
        void completingSubscriptionsWithProcessingContextStagesCompletionToAfterCommit() {
            // given...
            AtomicBoolean filterInvoked = new AtomicBoolean(false);
            QueryMessage testQuery =
                    new GenericQueryMessage(QUERY_TYPE, QUERY_PAYLOAD);
            Predicate<QueryMessage> queryFilter =
                    query -> {
                        filterInvoked.set(true);
                        return query.identifier().equals(testQuery.identifier());
                    };
            // We should have a subscription query for an update handler to even exist.
            testSubject.subscriptionQuery(testQuery, null, Queues.SMALL_BUFFER_SIZE);
            UnitOfWork uow = UnitOfWorkTestUtils.aUnitOfWork();
            // when emitting on invocation...
            uow.onInvocation(context -> testSubject.completeSubscriptions(queryFilter, context));
            // then before the after commit phase validate the filter was not invoked yet...
            assertThat(filterInvoked).isFalse();
            // when executing the UnitOfWork, we pass the after commit phase
            uow.execute().join();
            // then we expect the update to be emitted, validated by the filter being invoked...
            assertThat(filterInvoked).isTrue();
        }
    }

    @Nested
    class CompleteSubscriptionExceptionally {

        @Test
        void completingSubscriptionExceptionallyCompletesMatchingSubscriptionQueriesOnly() {
            // given...
            QueryMessage testQueryOne =
                    new GenericQueryMessage(QUERY_TYPE, QUERY_PAYLOAD);
            QueryMessage testQueryTwo =
                    new GenericQueryMessage(QUERY_TYPE, QUERY_PAYLOAD);
            QueryMessage testQueryThree =
                    new GenericQueryMessage(QUERY_TYPE, QUERY_PAYLOAD);

            testSubject.subscribe(QUERY_NAME, SINGLE_RESPONSE_HANDLER);

            // Filter will complete subscription queries one and two
            Predicate<QueryMessage> queryFilter = query ->
                    query.identifier().equals(testQueryOne.identifier())
                            || query.identifier().equals(testQueryTwo.identifier());
            MockException mockException = new MockException("Mock");
            SubscriptionQueryUpdateMessage updateMessage =
                    new GenericSubscriptionQueryUpdateMessage(UPDATE_PAYLOAD_TYPE, UPDATE_PAYLOAD);
            MessageStream<QueryResponseMessage> firstResponses = testSubject.subscriptionQuery(testQueryOne,
                                                                                               null,
                                                                                               Queues.SMALL_BUFFER_SIZE);
            MessageStream<QueryResponseMessage> secondResponses = testSubject.subscriptionQuery(testQueryTwo,
                                                                                                null,
                                                                                                Queues.SMALL_BUFFER_SIZE);
            MessageStream<QueryResponseMessage> thirdResponses = testSubject.subscriptionQuery(testQueryThree,
                                                                                               null,
                                                                                               Queues.SMALL_BUFFER_SIZE);
            // when...
            testSubject.completeSubscriptionsExceptionally(queryFilter, mockException, null).join();
            // then...
            testSubject.emitUpdate(query -> true, () -> updateMessage, null).join();
            StepVerifier.create(FluxUtils.of(firstResponses).map(MessageStream.Entry::message))
                        .expectNextCount(1)
                        .verifyError(MockException.class);
            StepVerifier.create(FluxUtils.of(secondResponses).map(MessageStream.Entry::message))
                        .expectNextCount(1)
                        .verifyError(MockException.class);
            StepVerifier.create(FluxUtils.of(thirdResponses).map(MessageStream.Entry::message))
                        .expectNextCount(1)
                        .expectNextMatches(response -> Objects.equals(response.payload(), UPDATE_PAYLOAD))
                        .verifyTimeout(Duration.ofMillis(100));
        }

        @Test
        void completingSubscriptionsExceptionallyWithProcessingContextStagesCompletionToAfterCommit() {
            // given...
            AtomicBoolean filterInvoked = new AtomicBoolean(false);
            QueryMessage testQuery =
                    new GenericQueryMessage(QUERY_TYPE, QUERY_PAYLOAD);
            Predicate<QueryMessage> queryFilter =
                    query -> {
                        filterInvoked.set(true);
                        return query.identifier().equals(testQuery.identifier());
                    };
            MockException mockException = new MockException("Mock");
            // We should have a subscription query for an update handler to even exist.
            testSubject.subscriptionQuery(testQuery, null, Queues.SMALL_BUFFER_SIZE);
            UnitOfWork uow = UnitOfWorkTestUtils.aUnitOfWork();
            // when emitting on invocation...
            uow.onInvocation(context -> testSubject.completeSubscriptionsExceptionally(
                    queryFilter, mockException, context
            ));
            // then before the after commit phase validate the filter was not invoked yet...
            assertThat(filterInvoked).isFalse();
            // when executing the UnitOfWork, we pass the after commit phase
            uow.execute().join();
            // then we expect the update to be emitted, validated by the filter being invoked...
            assertThat(filterInvoked).isTrue();
        }
    }
}
