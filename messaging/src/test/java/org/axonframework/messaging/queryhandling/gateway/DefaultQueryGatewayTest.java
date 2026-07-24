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

package org.axonframework.messaging.queryhandling.gateway;

import org.axonframework.messaging.core.ClassBasedMessageTypeResolver;
import org.axonframework.messaging.core.GenericMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.conversion.DelegatingMessageConverter;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.queryhandling.GenericQueryMessage;
import org.axonframework.messaging.queryhandling.GenericQueryResponseMessage;
import org.axonframework.messaging.queryhandling.GenericSubscriptionQueryUpdateMessage;
import org.axonframework.messaging.queryhandling.QueryBus;
import org.axonframework.messaging.queryhandling.QueryMessage;
import org.axonframework.messaging.queryhandling.QueryPriorityCalculator;
import org.axonframework.messaging.queryhandling.QueryResponseMessage;
import org.axonframework.conversion.PassThroughConverter;
import org.axonframework.common.util.MockException;
import org.junit.jupiter.api.*;
import org.mockito.*;
import org.reactivestreams.Publisher;
import reactor.test.StepVerifier;
import reactor.util.concurrent.Queues;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Test class verifying correct workings of the {@link DefaultQueryGateway}.
 *
 * @author Allard Buijze
 * @author Steven van Beelen
 */
class DefaultQueryGatewayTest {

    private static final MessageType QUERY_TYPE = new MessageType(String.class);
    private static final MessageType UPDATE_TYPE = new MessageType(String.class);
    private static final String QUERY_PAYLOAD = "query";
    private static final MessageType RESPONSE_TYPE = new MessageType(String.class);
    private static final String RESPONSE_PAYLOAD = "answer";

    private QueryBus queryBus;

    private DefaultQueryGateway testSubject;

    private ArgumentCaptor<QueryMessage> queryCaptor;
    private ArgumentCaptor<QueryMessage> streamingQueryCaptor;

    @BeforeEach
    void setUp() {
        queryBus = mock(QueryBus.class);

        testSubject = new DefaultQueryGateway(queryBus,
                                              new ClassBasedMessageTypeResolver(),
                                              QueryPriorityCalculator.defaultCalculator(),
                                              new DelegatingMessageConverter(PassThroughConverter.INSTANCE));

        queryCaptor = ArgumentCaptor.forClass(QueryMessage.class);
        streamingQueryCaptor = ArgumentCaptor.forClass(QueryMessage.class);
    }

    @Nested
    class QuerySingle {

        @Test
        void queryInvokesQueryBusAsExpected() throws Exception {
            // given...
            QueryResponseMessage testResponse = new GenericQueryResponseMessage(RESPONSE_TYPE, RESPONSE_PAYLOAD);
            when(queryBus.query(any(), eq(null))).thenReturn(MessageStream.just(testResponse));
            // when...
            CompletableFuture<String> result = testSubject.query(QUERY_PAYLOAD, String.class, null);
            // then...
            assertThat(result).isDone();
            assertThat(result.get()).isEqualTo(RESPONSE_PAYLOAD);

            verify(queryBus).query(queryCaptor.capture(), eq(null));

            QueryMessage resultMessage = queryCaptor.getValue();
            assertThat(resultMessage.payload()).isEqualTo(QUERY_PAYLOAD);
            assertThat(resultMessage.payloadType()).isEqualTo(String.class);
            assertThat(resultMessage.metadata()).isEqualTo(Metadata.emptyInstance());
        }

        @Test
        void queryWithMetadataInvokesQueryBusWithMetadata() throws Exception {
            // given...
            QueryResponseMessage testResponse = new GenericQueryResponseMessage(RESPONSE_TYPE, RESPONSE_PAYLOAD);
            when(queryBus.query(any(), eq(null))).thenReturn(MessageStream.just(testResponse));
            String expectedKey = "key";
            String expectedValue = "value";
            Metadata testMetadata = Metadata.with(expectedKey, expectedValue);
            Message testQuery = new GenericMessage(QUERY_TYPE, QUERY_PAYLOAD, testMetadata);
            // when...
            CompletableFuture<String> result = testSubject.query(testQuery, String.class, null);
            // then...
            assertThat(result).isDone();
            assertThat(result.get()).isEqualTo(RESPONSE_PAYLOAD);

            verify(queryBus).query(queryCaptor.capture(), eq(null));

            QueryMessage resultMessage = queryCaptor.getValue();
            assertThat(resultMessage.payload()).isEqualTo(QUERY_PAYLOAD);
            assertThat(resultMessage.payloadType()).isEqualTo(String.class);
            Metadata resultMetadata = resultMessage.metadata();
            assertThat(resultMetadata).containsKey(expectedKey);
            assertThat(resultMetadata).containsValue(expectedValue);
        }

        @Test
        void queryWithMetadataParameterAttachesMetadataToPlainPayload() throws Exception {
            // given...
            QueryResponseMessage testResponse = new GenericQueryResponseMessage(RESPONSE_TYPE, RESPONSE_PAYLOAD);
            when(queryBus.query(any(), eq(null))).thenReturn(MessageStream.just(testResponse));
            Metadata testMetadata = Metadata.with("key", "value");
            // when...
            CompletableFuture<String> result = testSubject.query(QUERY_PAYLOAD, String.class, testMetadata, null);
            // then...
            assertThat(result).isDone();
            assertThat(result.get()).isEqualTo(RESPONSE_PAYLOAD);

            verify(queryBus).query(queryCaptor.capture(), eq(null));

            QueryMessage resultMessage = queryCaptor.getValue();
            assertThat(resultMessage.payload()).isEqualTo(QUERY_PAYLOAD);
            assertThat(resultMessage.metadata()).containsKey("key").containsValue("value");
        }

        @Test
        void queryReturningFailedMessageStreamReturnsExceptionalCompletableFuture() {
            // given...
            Throwable expected = new Throwable("oops");
            when(queryBus.query(any(), eq(null))).thenReturn(MessageStream.failed(expected));
            // when...
            CompletableFuture<String> result = testSubject.query(QUERY_PAYLOAD, String.class, null);
            // then...
            assertThat(result).isDone();
            assertThat(result).isCompletedExceptionally();
            assertThat(result.exceptionNow().getMessage()).isEqualTo("oops");
        }

        @Test
        void cancellingResultFromQueryClosesMessageStreamFromQueryBus() {
            // given...
            MessageStream.Single<QueryResponseMessage> testResponseStream =
                    spy(MessageStream.fromFuture(new CompletableFuture<>()));
            when(queryBus.query(any(), eq(null))).thenReturn(testResponseStream);
            // when querying...
            CompletableFuture<String> result = testSubject.query(QUERY_PAYLOAD, String.class, null);
            // then...
            assertThat(result).isNotDone();
            // when canceling result...
            result.cancel(true);
            verify(testResponseStream).close();
        }

        @Test
        void queryReportsPayloadExtractionExceptions() {
            // given...
            when(queryBus.query(any(), eq(null)))
                    .thenReturn(MessageStream.just(new GenericQueryResponseMessage(RESPONSE_TYPE, RESPONSE_PAYLOAD) {
                        @Override
                        public String payload() {
                            throw new MockException("Faking conversion problem");
                        }
                    }));
            // when...
            CompletableFuture<String> result = testSubject.query(QUERY_PAYLOAD, String.class, null);
            // then...
            assertThat(result).isDone();
            assertThat(result).isCompletedExceptionally();
            assertThat(result.exceptionNow().getMessage()).isEqualTo("Faking conversion problem");
        }

        @Test
        void queryWithNullMessageInEntryReturnsNull() throws Exception {
            // given...
            MessageStream.Single<QueryResponseMessage> streamWithNullMessage = MessageStream.fromFuture(
                    CompletableFuture.completedFuture(null)
            );
            when(queryBus.query(any(), eq(null))).thenReturn(streamWithNullMessage);
            // when...
            CompletableFuture<String> result = testSubject.query(QUERY_PAYLOAD, String.class, null);
            // then...
            assertThat(result).isDone();
            assertThat(result.get()).isNull();
        }
    }

    @Nested
    class QueryMany {

        @Test
        void queryManyInvokesQueryBusAsExpected() throws Exception {
            // given...
            QueryResponseMessage testResponse = new GenericQueryResponseMessage(RESPONSE_TYPE, RESPONSE_PAYLOAD);
            when(queryBus.query(any(), eq(null))).thenReturn(MessageStream.fromIterable(List.of(testResponse)));
            // when...
            CompletableFuture<List<String>> result = testSubject.queryMany(QUERY_PAYLOAD, String.class, null);
            // then...
            assertThat(result).isDone();
            List<String> resultList = result.get();
            assertThat(resultList.size()).isEqualTo(1);
            assertThat(resultList.getFirst()).isEqualTo(RESPONSE_PAYLOAD);

            verify(queryBus).query(queryCaptor.capture(), eq(null));

            QueryMessage resultMessage = queryCaptor.getValue();
            assertThat(resultMessage.payload()).isEqualTo(QUERY_PAYLOAD);
            assertThat(resultMessage.payloadType()).isEqualTo(String.class);
            assertThat(resultMessage.metadata()).isEqualTo(Metadata.emptyInstance());
        }

        @Test
        void queryManyWithMetadataInvokesQueryBusWithMetadata() throws Exception {
            // given...
            QueryResponseMessage testResponse = new GenericQueryResponseMessage(RESPONSE_TYPE, RESPONSE_PAYLOAD);
            when(queryBus.query(any(), eq(null))).thenReturn(MessageStream.fromIterable(List.of(testResponse)));
            String expectedKey = "key";
            String expectedValue = "value";
            Metadata testMetadata = Metadata.with(expectedKey, expectedValue);
            Message testQuery = new GenericMessage(QUERY_TYPE, QUERY_PAYLOAD, testMetadata);
            // when...
            CompletableFuture<List<String>> result = testSubject.queryMany(testQuery, String.class, null);
            // then...
            assertThat(result).isDone();
            List<String> resultList = result.get();
            assertThat(resultList.size()).isEqualTo(1);
            assertThat(resultList.getFirst()).isEqualTo(RESPONSE_PAYLOAD);

            verify(queryBus).query(queryCaptor.capture(), eq(null));

            QueryMessage resultMessage = queryCaptor.getValue();
            assertThat(resultMessage.payload()).isEqualTo(QUERY_PAYLOAD);
            assertThat(resultMessage.payloadType()).isEqualTo(String.class);
            Metadata resultMetadata = resultMessage.metadata();
            assertThat(resultMetadata).containsKey(expectedKey);
            assertThat(resultMetadata).containsValue(expectedValue);
        }

        @Test
        void queryManyWithMetadataParameterAttachesMetadataToPlainPayload() throws Exception {
            // given...
            QueryResponseMessage testResponse = new GenericQueryResponseMessage(RESPONSE_TYPE, RESPONSE_PAYLOAD);
            when(queryBus.query(any(), eq(null))).thenReturn(MessageStream.fromIterable(List.of(testResponse)));
            Metadata testMetadata = Metadata.with("key", "value");
            // when...
            CompletableFuture<List<String>> result = testSubject.queryMany(QUERY_PAYLOAD, String.class, testMetadata, null);
            // then...
            assertThat(result).isDone();
            assertThat(result.get()).containsExactly(RESPONSE_PAYLOAD);

            verify(queryBus).query(queryCaptor.capture(), eq(null));

            QueryMessage resultMessage = queryCaptor.getValue();
            assertThat(resultMessage.payload()).isEqualTo(QUERY_PAYLOAD);
            assertThat(resultMessage.metadata()).containsKey("key").containsValue("value");
        }

        @Test
        void queryManyReturningFailedMessageStreamReturnsExceptionalCompletableFuture() {
            // given...
            Throwable expected = new Throwable("oops");
            when(queryBus.query(any(), eq(null))).thenReturn(MessageStream.failed(expected));
            // when...
            CompletableFuture<List<String>> result = testSubject.queryMany(QUERY_PAYLOAD, String.class, null);
            // then...
            assertThat(result).isDone();
            assertThat(result).isCompletedExceptionally();
            assertThat(result.exceptionNow().getMessage()).isEqualTo("oops");
        }

        @Test
        void cancellingResultFromQueryManyClosesMessageStreamFromQueryBus() {
            // given...
            MessageStream.Single<QueryResponseMessage> testResponseStream =
                    spy(MessageStream.fromFuture(new CompletableFuture<>()));
            when(queryBus.query(any(), eq(null))).thenReturn(testResponseStream);
            // when querying...
            CompletableFuture<List<String>> result = testSubject.queryMany(QUERY_PAYLOAD, String.class, null);
            // then...
            assertThat(result).isNotDone();
            // when canceling result...
            result.cancel(true);
            verify(testResponseStream).close();
        }

        @Test
        void queryManyReportsPayloadExtractionExceptions() {
            // given...
            when(queryBus.query(any(), eq(null)))
                    .thenReturn(MessageStream.fromIterable(List.of(
                            new GenericQueryResponseMessage(RESPONSE_TYPE, RESPONSE_PAYLOAD) {
                                @Override
                                public String payload() {
                                    throw new MockException("Faking conversion problem");
                                }
                            }
                    )));
            // when...
            CompletableFuture<List<String>> result = testSubject.queryMany(QUERY_PAYLOAD, String.class, null);
            // then...
            assertThat(result).isDone();
            assertThat(result).isCompletedExceptionally();
            assertThat(result.exceptionNow().getMessage()).isEqualTo("Faking conversion problem");
        }
    }

    @Nested
    class StreamingQuery {

        @Test
        void streamingQueryInvokesQueryBusAsExpected() {
            // given...
            QueryResponseMessage testResponse = new GenericQueryResponseMessage(RESPONSE_TYPE, RESPONSE_PAYLOAD);
            when(queryBus.query(any(), eq(null))).thenReturn(MessageStream.just(testResponse));
            // when...
            StepVerifier.create(testSubject.streamingQuery(QUERY_PAYLOAD, String.class, null))
                        .expectNext(RESPONSE_PAYLOAD)
                        .verifyComplete();
            // then...
            verify(queryBus).query(streamingQueryCaptor.capture(), eq(null));

            QueryMessage resultMessage = streamingQueryCaptor.getValue();
            assertThat(resultMessage.payload()).isEqualTo(QUERY_PAYLOAD);
            assertThat(resultMessage.payloadType()).isEqualTo(String.class);
            assertThat(resultMessage.metadata()).isEqualTo(Metadata.emptyInstance());
        }

        @Test
        void streamingQueryWithMetadataInvokesQueryBusWithMetadata() {
            // given...
            QueryResponseMessage testResponse = new GenericQueryResponseMessage(RESPONSE_TYPE, RESPONSE_PAYLOAD);
            when(queryBus.query(any(), eq(null))).thenReturn(MessageStream.just(testResponse));
            String expectedKey = "key";
            String expectedValue = "value";
            Metadata testMetadata = Metadata.with(expectedKey, expectedValue);
            Message testQuery = new GenericQueryMessage(QUERY_TYPE, QUERY_PAYLOAD)
                    .andMetadata(testMetadata);
            // when...
            StepVerifier.create(testSubject.streamingQuery(testQuery, String.class, null))
                        .expectNext(RESPONSE_PAYLOAD)
                        .verifyComplete();
            // then...
            verify(queryBus).query(streamingQueryCaptor.capture(), eq(null));

            QueryMessage resultMessage = streamingQueryCaptor.getValue();
            assertThat(resultMessage.payload()).isEqualTo(QUERY_PAYLOAD);
            assertThat(resultMessage.payloadType()).isEqualTo(String.class);
            Metadata resultMetadata = resultMessage.metadata();
            assertThat(resultMetadata).containsKey(expectedKey);
            assertThat(resultMetadata).containsValue(expectedValue);
        }

        @Test
        void streamingQueryWithMetadataParameterAttachesMetadataToPlainPayload() {
            // given...
            QueryResponseMessage testResponse = new GenericQueryResponseMessage(RESPONSE_TYPE, RESPONSE_PAYLOAD);
            when(queryBus.query(any(), eq(null))).thenReturn(MessageStream.just(testResponse));
            Metadata testMetadata = Metadata.with("key", "value");
            // when...
            StepVerifier.create(testSubject.streamingQuery(QUERY_PAYLOAD, String.class, testMetadata, null))
                        .expectNext(RESPONSE_PAYLOAD)
                        .verifyComplete();
            // then...
            verify(queryBus).query(streamingQueryCaptor.capture(), eq(null));

            QueryMessage resultMessage = streamingQueryCaptor.getValue();
            assertThat(resultMessage.payload()).isEqualTo(QUERY_PAYLOAD);
            assertThat(resultMessage.metadata()).containsKey("key").containsValue("value");
        }

        @Test
        void streamingQueryIsLazy() {
            // given...
            MessageStream<QueryResponseMessage> response = MessageStream.fromItems(
                    new GenericQueryResponseMessage(QUERY_TYPE, "a"),
                    new GenericQueryResponseMessage(QUERY_TYPE, "b"),
                    new GenericQueryResponseMessage(QUERY_TYPE, "c")
            );
            when(queryBus.query(any(), any())).thenReturn(response);
            // when first try without subscribing...
            //noinspection ReactiveStreamsUnusedPublisher
            testSubject.streamingQuery(QUERY_PAYLOAD, String.class, null);
            // then expect query never sent...
            verify(queryBus, never()).query(any(), eq(null));

            // when second try with subscribing...
            StepVerifier.create(testSubject.streamingQuery(QUERY_PAYLOAD, String.class, null))
                        .expectNext("a", "b", "c")
                        .verifyComplete();
            // then expect query sent...
            verify(queryBus).query(any(QueryMessage.class), eq(null));
        }

        @Test
        void streamingQueryPropagateErrors() {
            // given...
            when(queryBus.query(any(), any())).thenReturn(MessageStream.failed(new IllegalStateException("test")));
            // when and then...
            StepVerifier.create(testSubject.streamingQuery(QUERY_PAYLOAD, String.class, null))
                        .expectErrorMatches(t -> t instanceof IllegalStateException && t.getMessage().equals("test"))
                        .verify();
        }
    }

    @Nested
    class SubscriptionQuerySingleResultType {

        @Test
        void subscriptionQueryInvokesQueryBusAsExpected() {
            // given...
            when(queryBus.subscriptionQuery(any(), eq(null), eq(Queues.SMALL_BUFFER_SIZE)))
                    .thenReturn(MessageStream.fromItems(new GenericQueryResponseMessage(QUERY_TYPE, "a"),
                                                        new GenericQueryResponseMessage(QUERY_TYPE, "b"),
                                                        new GenericQueryResponseMessage(QUERY_TYPE, "c"),
                                                        new GenericSubscriptionQueryUpdateMessage(UPDATE_TYPE, "1"),
                                                        new GenericSubscriptionQueryUpdateMessage(UPDATE_TYPE, "2"),
                                                        new GenericSubscriptionQueryUpdateMessage(UPDATE_TYPE, "3")));
            // when/then ...
            StepVerifier.create(testSubject.subscriptionQuery(QUERY_PAYLOAD, String.class))
                        .expectNext("a", "b", "c", "1", "2", "3")
                        .verifyComplete();
            verify(queryBus).subscriptionQuery(
                    queryCaptor.capture(), eq(null), eq(Queues.SMALL_BUFFER_SIZE)
            );
            QueryMessage resultMessage = queryCaptor.getValue();
            assertThat(resultMessage.payload()).isEqualTo(QUERY_PAYLOAD);
            assertThat(resultMessage.payloadType()).isEqualTo(String.class);
            assertThat(resultMessage.metadata()).isEqualTo(Metadata.emptyInstance());
        }

        @Test
        void subscriptionQueryWithMetadataInvokesQueryBusWithMetadata() {
            // given...
            when(queryBus.subscriptionQuery(any(), eq(null), eq(Queues.SMALL_BUFFER_SIZE)))
                    .thenReturn(MessageStream.fromItems(new GenericQueryResponseMessage(QUERY_TYPE, "a"),
                                                        new GenericQueryResponseMessage(QUERY_TYPE, "b"),
                                                        new GenericQueryResponseMessage(QUERY_TYPE, "c"),
                                                        new GenericSubscriptionQueryUpdateMessage(UPDATE_TYPE, "1"),
                                                        new GenericSubscriptionQueryUpdateMessage(UPDATE_TYPE, "2"),
                                                        new GenericSubscriptionQueryUpdateMessage(UPDATE_TYPE, "3")));
            String expectedKey = "key";
            String expectedValue = "value";
            Metadata testMetadata = Metadata.with(expectedKey, expectedValue);
            Message testQuery = new GenericMessage(QUERY_TYPE, QUERY_PAYLOAD, testMetadata);
            // when/then ...
            StepVerifier.create(testSubject.subscriptionQuery(testQuery, String.class))
                        .expectNext("a", "b", "c", "1", "2", "3")
                        .verifyComplete();
            verify(queryBus).subscriptionQuery(
                    queryCaptor.capture(), eq(null), eq(Queues.SMALL_BUFFER_SIZE)
            );
            QueryMessage resultMessage = queryCaptor.getValue();
            assertThat(resultMessage.payload()).isEqualTo(QUERY_PAYLOAD);
            assertThat(resultMessage.payloadType()).isEqualTo(String.class);
            Metadata resultMetadata = resultMessage.metadata();
            assertThat(resultMetadata).containsKey(expectedKey);
            assertThat(resultMetadata).containsValue(expectedValue);
        }

        @Test
        void subscriptionQueryWithMetadataParameterAttachesMetadataToPlainPayload() {
            // given...
            when(queryBus.subscriptionQuery(any(), eq(null), eq(Queues.SMALL_BUFFER_SIZE)))
                    .thenReturn(MessageStream.fromItems(new GenericQueryResponseMessage(QUERY_TYPE, "a"),
                                                        new GenericSubscriptionQueryUpdateMessage(UPDATE_TYPE, "1")));
            Metadata testMetadata = Metadata.with("key", "value");
            // when/then ...
            StepVerifier.create(testSubject.subscriptionQuery(QUERY_PAYLOAD,
                                                              String.class,
                                                              testMetadata,
                                                              null,
                                                              Queues.SMALL_BUFFER_SIZE))
                        .expectNext("a", "1")
                        .verifyComplete();
            verify(queryBus).subscriptionQuery(
                    queryCaptor.capture(), eq(null), eq(Queues.SMALL_BUFFER_SIZE)
            );
            QueryMessage resultMessage = queryCaptor.getValue();
            assertThat(resultMessage.payload()).isEqualTo(QUERY_PAYLOAD);
            assertThat(resultMessage.metadata()).containsKey("key").containsValue("value");
        }

        @Test
        void subscriptionQueryUpdatesExceptionReportedInFlux() {
            // given...
            when(queryBus.subscriptionQuery(any(), eq(null), eq(Queues.SMALL_BUFFER_SIZE)))
                    .thenReturn(MessageStream.failed(new MockException()));
            // when/then...
            StepVerifier.create(testSubject.subscriptionQuery(QUERY_PAYLOAD, String.class))
                        .verifyError(MockException.class);
        }

        @Test
        void subscriptionQueryNullResultsAreSkipped() {
            // given...

            when(queryBus.subscriptionQuery(any(), eq(null), eq(Queues.SMALL_BUFFER_SIZE)))
                    .thenReturn(MessageStream.fromItems(new GenericQueryResponseMessage(QUERY_TYPE, "a"),
                                                        new GenericQueryResponseMessage(QUERY_TYPE, null),
                                                        new GenericQueryResponseMessage(QUERY_TYPE, "b"),
                                                        new GenericSubscriptionQueryUpdateMessage(QUERY_TYPE, "1"),
                                                        new GenericSubscriptionQueryUpdateMessage(QUERY_TYPE, null),
                                                        new GenericSubscriptionQueryUpdateMessage(QUERY_TYPE, "2")));
            // when/then...
            StepVerifier.create(testSubject.subscriptionQuery(QUERY_PAYLOAD, String.class))
                        .expectNext("a", "b", "1", "2")
                        .verifyComplete();
        }
    }

    @Nested
    class SubscriptionQueryWithDifferentInitialAndUpdateType {

        @Test
        void subscriptionQueryInvokesQueryBusAsExpected() {
            // given...
            when(queryBus.subscriptionQuery(any(), eq(null), eq(Queues.SMALL_BUFFER_SIZE)))
                    .thenReturn(MessageStream.fromItems(new GenericQueryResponseMessage(QUERY_TYPE, "a"),
                                                        new GenericQueryResponseMessage(QUERY_TYPE, "b"),
                                                        new GenericQueryResponseMessage(QUERY_TYPE, "c"),
                                                        new GenericSubscriptionQueryUpdateMessage(UPDATE_TYPE, "1"),
                                                        new GenericSubscriptionQueryUpdateMessage(UPDATE_TYPE, "2"),
                                                        new GenericSubscriptionQueryUpdateMessage(UPDATE_TYPE, "3")
                    ));
            // when...
            Publisher<String> result = testSubject.subscriptionQuery(QUERY_PAYLOAD, String.class);
            // /then...
            StepVerifier.create(result)
                        .expectNext("a", "b", "c")
                        .expectNext("1", "2", "3")
                        .verifyComplete();
            verify(queryBus).subscriptionQuery(
                    queryCaptor.capture(), eq(null), eq(Queues.SMALL_BUFFER_SIZE)
            );
            QueryMessage queryMessage = queryCaptor.getValue();
            assertThat(queryMessage.payload()).isEqualTo(QUERY_PAYLOAD);
            assertThat(queryMessage.payloadType()).isEqualTo(String.class);
            assertThat(queryMessage.metadata()).isEqualTo(Metadata.emptyInstance());
        }

        @Test
        void subscriptionQueryWithMetadataInvokesQueryBusWithMetadata() {
            // given...
            when(queryBus.subscriptionQuery(any(), eq(null), eq(Queues.SMALL_BUFFER_SIZE)))
                    .thenReturn(MessageStream.fromItems(new GenericQueryResponseMessage(QUERY_TYPE, "a"),
                                                        new GenericQueryResponseMessage(QUERY_TYPE, "b"),
                                                        new GenericQueryResponseMessage(QUERY_TYPE, "c"),
                                                        new GenericSubscriptionQueryUpdateMessage(UPDATE_TYPE, "1"),
                                                        new GenericSubscriptionQueryUpdateMessage(UPDATE_TYPE, "2"),
                                                        new GenericSubscriptionQueryUpdateMessage(UPDATE_TYPE, "3")
                    ));
            String expectedKey = "key";
            String expectedValue = "value";
            Metadata testMetadata = Metadata.with(expectedKey, expectedValue);
            Message testQuery = new GenericMessage(QUERY_TYPE, QUERY_PAYLOAD, testMetadata);
            // when...
            Publisher<String> result = testSubject.subscriptionQuery(testQuery, String.class);
            // then ...
            StepVerifier.create(result)
                        .expectNext("a", "b", "c")
                        .expectNext("1", "2", "3")
                        .verifyComplete();
            verify(queryBus).subscriptionQuery(
                    queryCaptor.capture(), eq(null), eq(Queues.SMALL_BUFFER_SIZE)
            );
            QueryMessage resultMessage = queryCaptor.getValue();
            assertThat(resultMessage.payload()).isEqualTo(QUERY_PAYLOAD);
            assertThat(resultMessage.payloadType()).isEqualTo(String.class);
            Metadata resultMetadata = resultMessage.metadata();
            assertThat(resultMetadata).containsKey(expectedKey);
            assertThat(resultMetadata).containsValue(expectedValue);
        }

        @Test
        void subscriptionQueryExceptionReportedInInitialResultFlux() {
            // given...
            when(queryBus.subscriptionQuery(any(), eq(null), eq(Queues.SMALL_BUFFER_SIZE)))
                    .thenReturn(MessageStream.failed(new MockException()));
            // when...
            Publisher<String> result = testSubject.subscriptionQuery(QUERY_PAYLOAD, String.class);
            // then...
            StepVerifier.create(result)
                        .verifyError(MockException.class);
        }

        @Test
        void subscriptionQueryInitialResultNullResultsAreSkipped() {
            // given...
            when(queryBus.subscriptionQuery(any(), eq(null), eq(Queues.SMALL_BUFFER_SIZE)))
                    .thenReturn(MessageStream.fromItems(new GenericQueryResponseMessage(QUERY_TYPE, "a"),
                                                        new GenericQueryResponseMessage(QUERY_TYPE, null),
                                                        new GenericQueryResponseMessage(QUERY_TYPE, "b")
                    ));
            // when...
            Publisher<String> result = testSubject.subscriptionQuery(QUERY_PAYLOAD, String.class);
            // then...
            StepVerifier.create(result)
                        .expectNext("a", "b")
                        .verifyComplete();
        }

        @Test
        void subscriptionQueryUpdatesNullResultsAreSkipped() {
            // given...
            when(queryBus.subscriptionQuery(any(), eq(null), eq(Queues.SMALL_BUFFER_SIZE)))
                    .thenReturn(MessageStream.fromItems(new GenericSubscriptionQueryUpdateMessage(QUERY_TYPE, "a"),
                                                        new GenericSubscriptionQueryUpdateMessage(QUERY_TYPE, null),
                                                        new GenericSubscriptionQueryUpdateMessage(QUERY_TYPE, "b")
                    ));
            // when...
            Publisher<String> result = testSubject.subscriptionQuery(QUERY_PAYLOAD, String.class);
            // then...
            StepVerifier.create(result)
                        .expectNext("a", "b")
                        .verifyComplete();
        }
    }

    @Nested
    class MetadataDefaultsWithoutOverride {

        // A QueryGateway that implements only the non-metadata terminals, leaving the metadata-carrying defaults in
        // place. The default implementations must signal that metadata is unsupported rather than silently dropping it.
        private final QueryGateway gatewayWithoutMetadataSupport = new QueryGateway() {
            @Override
            public <R> CompletableFuture<R> query(Object query, Class<R> responseType, ProcessingContext context) {
                return null;
            }

            @Override
            public <R> CompletableFuture<List<R>> queryMany(Object query, Class<R> responseType,
                                                            ProcessingContext context) {
                return null;
            }

            @Override
            public <R> Publisher<R> streamingQuery(Object query, Class<R> responseType, ProcessingContext context) {
                return null;
            }

            @Override
            public <R> Publisher<R> subscriptionQuery(Object query, Class<R> responseType, ProcessingContext context,
                                                      int updateBufferSize) {
                return null;
            }

            @Override
            public <R> Publisher<R> subscriptionQuery(Object query, Class<R> responseType,
                                                      Function<QueryResponseMessage, R> mapper,
                                                      ProcessingContext context, int updateBufferSize) {
                return null;
            }

            @Override
            public void describeTo(ComponentDescriptor descriptor) {
                // no-op
            }
        };

        @Test
        void queryWithMetadataThrowsUnsupportedOperation() {
            assertThatThrownBy(() -> gatewayWithoutMetadataSupport.query(
                    QUERY_PAYLOAD, String.class, Metadata.with("key", "value"), null
            )).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void queryManyWithMetadataThrowsUnsupportedOperation() {
            assertThatThrownBy(() -> gatewayWithoutMetadataSupport.queryMany(
                    QUERY_PAYLOAD, String.class, Metadata.with("key", "value"), null
            )).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void streamingQueryWithMetadataThrowsUnsupportedOperation() {
            assertThatThrownBy(() -> gatewayWithoutMetadataSupport.streamingQuery(
                    QUERY_PAYLOAD, String.class, Metadata.with("key", "value"), null
            )).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void subscriptionQueryWithMetadataThrowsUnsupportedOperation() {
            assertThatThrownBy(() -> gatewayWithoutMetadataSupport.subscriptionQuery(
                    QUERY_PAYLOAD, String.class, Metadata.with("key", "value"), null, Queues.SMALL_BUFFER_SIZE
            )).isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
