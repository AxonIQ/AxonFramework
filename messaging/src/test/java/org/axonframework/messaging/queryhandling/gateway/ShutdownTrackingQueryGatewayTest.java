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

import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.common.lifecycle.ShutdownInProgressException;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.queryhandling.QueryResponseMessage;
import org.axonframework.messaging.queryhandling.QueryShutdownManager;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.*;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Test class verifying correct workings of the {@link ShutdownTrackingQueryGateway}.
 *
 * @author Allard Buijze
 */
class ShutdownTrackingQueryGatewayTest {

    private static final String QUERY = "query";
    private static final String RESPONSE = "response";

    private StubQueryGateway stubGateway;
    private QueryShutdownManager subscriptionManager;
    private QueryShutdownManager streamingManager;

    @BeforeEach
    void setUp() {
        stubGateway = new StubQueryGateway();
        subscriptionManager = QueryShutdownManager.closeImmediately();
        streamingManager = QueryShutdownManager.closeImmediately();
    }

    private static class StubQueryGateway implements QueryGateway {

        Object lastQuery;
        Class<?> lastResponseType;
        ProcessingContext lastContext;
        int lastBufferSize;

        @SuppressWarnings("rawtypes")
        CompletableFuture queryResult = CompletableFuture.completedFuture(null);
        @SuppressWarnings("rawtypes")
        CompletableFuture queryManyResult = CompletableFuture.completedFuture(List.of());
        Publisher<?> streamingQueryPublisher = Flux.empty();
        Publisher<?> subscriptionQueryPublisher = Flux.empty();
        Publisher<?> subscriptionQueryWithMapperPublisher = Flux.empty();

        @Override
        @SuppressWarnings("unchecked")
        @NonNull
        public <R> CompletableFuture<R> query(@NonNull Object query, @NonNull Class<R> responseType,
                                              ProcessingContext context) {
            lastQuery = query;
            lastResponseType = responseType;
            lastContext = context;
            return (CompletableFuture<R>) queryResult;
        }

        @Override
        @SuppressWarnings("unchecked")
        @NonNull
        public <R> CompletableFuture<List<R>> queryMany(@NonNull Object query, @NonNull Class<R> responseType,
                                                        ProcessingContext context) {
            lastQuery = query;
            lastResponseType = responseType;
            lastContext = context;
            return (CompletableFuture<List<R>>) queryManyResult;
        }

        @Override
        @SuppressWarnings("unchecked")
        @NonNull
        public <R> Publisher<R> streamingQuery(@NonNull Object query, @NonNull Class<R> responseType,
                                               ProcessingContext context) {
            lastQuery = query;
            lastResponseType = responseType;
            lastContext = context;
            return (Publisher<R>) streamingQueryPublisher;
        }

        @Override
        @SuppressWarnings("unchecked")
        @NonNull
        public <R> Publisher<R> subscriptionQuery(@NonNull Object query, @NonNull Class<R> responseType,
                                                  ProcessingContext context,
                                                  int updateBufferSize) {
            lastQuery = query;
            lastResponseType = responseType;
            lastContext = context;
            lastBufferSize = updateBufferSize;
            return (Publisher<R>) subscriptionQueryPublisher;
        }

        @Override
        @SuppressWarnings("unchecked")
        @NonNull
        public <R> Publisher<R> subscriptionQuery(@NonNull Object query, @NonNull Class<R> responseType,
                                                  @NonNull Function<QueryResponseMessage, R> mapper,
                                                  ProcessingContext context, int updateBufferSize) {
            lastQuery = query;
            lastResponseType = responseType;
            lastContext = context;
            lastBufferSize = updateBufferSize;
            return (Publisher<R>) subscriptionQueryWithMapperPublisher;
        }

        @Override
        public void describeTo(ComponentDescriptor descriptor) {
            descriptor.describeProperty("type", "StubQueryGateway");
        }
    }

    @Nested
    class Construction {

        @SuppressWarnings("DataFlowIssue")
        @Test
        void throwsNullPointerExceptionWhenDelegateIsNull() {
            // when / then
            assertThatNullPointerException()
                    .isThrownBy(() -> new ShutdownTrackingQueryGateway(null, subscriptionManager, streamingManager));
        }

        @Test
        void allowsNullSubscriptionQueryShutdownManager() {
            // when / then
            assertThatNoException()
                    .isThrownBy(() -> new ShutdownTrackingQueryGateway(stubGateway, null, streamingManager));
        }

        @Test
        void allowsNullStreamingQueryShutdownManager() {
            // when / then
            assertThatNoException()
                    .isThrownBy(() -> new ShutdownTrackingQueryGateway(stubGateway, subscriptionManager, null));
        }
    }

    @Nested
    class QueryDelegation {

        @Test
        void delegatesQueryToUnderlyingGateway() {
            // given
            stubGateway.queryResult = CompletableFuture.completedFuture(RESPONSE);
            ShutdownTrackingQueryGateway testSubject =
                    new ShutdownTrackingQueryGateway(stubGateway, null, null);

            // when
            CompletableFuture<String> result = testSubject.query(QUERY, String.class, null);

            // then
            assertThat(result).isCompletedWithValue(RESPONSE);
            assertThat(stubGateway.lastQuery).isEqualTo(QUERY);
            assertThat(stubGateway.lastResponseType).isEqualTo(String.class);
        }

        @Test
        void passesProcessingContextToDelegate() {
            // given
            stubGateway.queryResult = CompletableFuture.completedFuture(RESPONSE);
            ShutdownTrackingQueryGateway testSubject =
                    new ShutdownTrackingQueryGateway(stubGateway, null, null);
            ProcessingContext ctx = mock(ProcessingContext.class);

            // when
            testSubject.query(QUERY, String.class, ctx);

            // then
            assertThat(stubGateway.lastContext).isSameAs(ctx);
        }

        @Test
        void propagatesExceptionalResultFromDelegate() {
            // given
            RuntimeException cause = new RuntimeException("query failed");
            stubGateway.queryResult = CompletableFuture.failedFuture(cause);
            ShutdownTrackingQueryGateway testSubject =
                    new ShutdownTrackingQueryGateway(stubGateway, null, null);

            // when
            CompletableFuture<String> result = testSubject.query(QUERY, String.class, null);

            // then
            assertThat(result).isCompletedExceptionally();
            assertThat(result.exceptionNow()).isSameAs(cause);
        }
    }

    @Nested
    class QueryManyDelegation {

        @Test
        void delegatesQueryManyToUnderlyingGateway() {
            // given
            stubGateway.queryManyResult = CompletableFuture.completedFuture(List.of(RESPONSE));
            ShutdownTrackingQueryGateway testSubject =
                    new ShutdownTrackingQueryGateway(stubGateway, null, null);

            // when
            CompletableFuture<List<String>> result = testSubject.queryMany(QUERY, String.class, null);

            // then
            assertThat(result).isCompletedWithValue(List.of(RESPONSE));
            assertThat(stubGateway.lastQuery).isEqualTo(QUERY);
        }

        @Test
        void passesProcessingContextToDelegateForQueryMany() {
            // given
            stubGateway.queryManyResult = CompletableFuture.completedFuture(List.of());
            ShutdownTrackingQueryGateway testSubject =
                    new ShutdownTrackingQueryGateway(stubGateway, null, null);
            ProcessingContext ctx = mock(ProcessingContext.class);

            // when
            testSubject.queryMany(QUERY, String.class, ctx);

            // then
            assertThat(stubGateway.lastContext).isSameAs(ctx);
        }

        @Test
        void propagatesExceptionalResultFromDelegateForQueryMany() {
            // given
            RuntimeException cause = new RuntimeException("query failed");
            stubGateway.queryManyResult = CompletableFuture.failedFuture(cause);
            ShutdownTrackingQueryGateway testSubject =
                    new ShutdownTrackingQueryGateway(stubGateway, null, null);

            // when
            CompletableFuture<List<String>> result = testSubject.queryMany(QUERY, String.class, null);

            // then
            assertThat(result).isCompletedExceptionally();
            assertThat(result.exceptionNow()).isSameAs(cause);
        }
    }

    @Nested
    class StreamingQuery {

        @Test
        void delegatesStreamingQueryToUnderlyingGateway() {
            // given
            Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
            stubGateway.streamingQueryPublisher = sink.asFlux();
            ShutdownTrackingQueryGateway testSubject =
                    new ShutdownTrackingQueryGateway(stubGateway, null, null);

            // when
            Publisher<String> result = testSubject.streamingQuery(QUERY, String.class, null);

            // then
            StepVerifier.create(result)
                        .then(() -> sink.tryEmitNext(RESPONSE))
                        .expectNext(RESPONSE)
                        .then(sink::tryEmitComplete)
                        .verifyComplete();
            assertThat(stubGateway.lastQuery).isEqualTo(QUERY);
        }

        @Test
        void passesProcessingContextToDelegateForStreamingQuery() {
            // given
            stubGateway.streamingQueryPublisher = Flux.empty();
            ShutdownTrackingQueryGateway testSubject =
                    new ShutdownTrackingQueryGateway(stubGateway, null, null);
            ProcessingContext ctx = mock(ProcessingContext.class);

            // when
            //noinspection ReactiveStreamsUnusedPublisher
            testSubject.streamingQuery(QUERY, String.class, ctx);

            // then
            assertThat(stubGateway.lastContext).isSameAs(ctx);
        }

        @Test
        void tracksStreamingQueryWithManagerWhenManagerIsSet() {
            // given
            Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
            stubGateway.streamingQueryPublisher = sink.asFlux();
            ShutdownTrackingQueryGateway testSubject =
                    new ShutdownTrackingQueryGateway(stubGateway, null, streamingManager);

            // when
            Publisher<String> result = testSubject.streamingQuery(QUERY, String.class, null);

            // then — manager cancels the tracked stream on shutdown
            StepVerifier.create(result)
                        .then(() -> sink.tryEmitNext(RESPONSE))
                        .expectNext(RESPONSE)
                        .then(streamingManager::shutdown)
                        .expectErrorSatisfies(e -> assertThat(e)
                                .isInstanceOf(ShutdownInProgressException.class))
                        .verify(Duration.ofSeconds(2));
        }

        @Test
        void doesNotTrackStreamingQueryWhenManagerIsNull() {
            // given
            Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
            stubGateway.streamingQueryPublisher = sink.asFlux();
            ShutdownTrackingQueryGateway testSubject =
                    new ShutdownTrackingQueryGateway(stubGateway, null, null);

            // when
            Publisher<String> result = testSubject.streamingQuery(QUERY, String.class, null);

            // then — stream completes normally without any shutdown interference
            StepVerifier.create(result)
                        .then(() -> sink.tryEmitNext(RESPONSE))
                        .expectNext(RESPONSE)
                        .then(sink::tryEmitComplete)
                        .verifyComplete();
        }

        @Test
        void subscriptionManagerDoesNotAffectStreamingQuery() {
            // given — only subscriptionManager is set, streamingManager is null
            Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
            stubGateway.streamingQueryPublisher = sink.asFlux();
            ShutdownTrackingQueryGateway testSubject =
                    new ShutdownTrackingQueryGateway(stubGateway, subscriptionManager, null);

            // when
            Publisher<String> result = testSubject.streamingQuery(QUERY, String.class, null);

            // then — shutting down subscriptionManager should not terminate the streaming query
            StepVerifier.create(result)
                        .then(subscriptionManager::shutdown)
                        .then(() -> sink.tryEmitNext(RESPONSE))
                        .expectNext(RESPONSE)
                        .then(sink::tryEmitComplete)
                        .verifyComplete();
        }
    }

    @Nested
    class SubscriptionQueryWithResponseType {

        @Test
        void delegatesSubscriptionQueryToUnderlyingGateway() {
            // given
            Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
            stubGateway.subscriptionQueryPublisher = sink.asFlux();
            ShutdownTrackingQueryGateway testSubject =
                    new ShutdownTrackingQueryGateway(stubGateway, null, null);

            // when
            Publisher<String> result = testSubject.subscriptionQuery(QUERY,
                                                                     String.class,
                                                                     (ProcessingContext) null,
                                                                     256);

            // then
            StepVerifier.create(result)
                        .then(() -> sink.tryEmitNext(RESPONSE))
                        .expectNext(RESPONSE)
                        .then(sink::tryEmitComplete)
                        .verifyComplete();
            assertThat(stubGateway.lastQuery).isEqualTo(QUERY);
        }

        @Test
        void passesBufferSizeAndContextToDelegate() {
            // given
            stubGateway.subscriptionQueryPublisher = Flux.empty();
            ShutdownTrackingQueryGateway testSubject =
                    new ShutdownTrackingQueryGateway(stubGateway, null, null);
            ProcessingContext ctx = mock(ProcessingContext.class);

            // when
            //noinspection ReactiveStreamsUnusedPublisher
            testSubject.subscriptionQuery(QUERY, String.class, ctx, 512);

            // then
            assertThat(stubGateway.lastBufferSize).isEqualTo(512);
            assertThat(stubGateway.lastContext).isSameAs(ctx);
        }

        @Test
        void tracksSubscriptionQueryWithManagerWhenManagerIsSet() {
            // given
            Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
            stubGateway.subscriptionQueryPublisher = sink.asFlux();
            ShutdownTrackingQueryGateway testSubject =
                    new ShutdownTrackingQueryGateway(stubGateway, subscriptionManager, null);

            // when
            Publisher<String> result = testSubject.subscriptionQuery(QUERY,
                                                                     String.class,
                                                                     (ProcessingContext) null,
                                                                     256);

            // then — manager cancels the tracked stream on shutdown
            StepVerifier.create(result)
                        .then(() -> sink.tryEmitNext(RESPONSE))
                        .expectNext(RESPONSE)
                        .then(subscriptionManager::shutdown)
                        .expectErrorSatisfies(e -> assertThat(e)
                                .isInstanceOf(ShutdownInProgressException.class))
                        .verify(Duration.ofSeconds(2));
        }

        @Test
        void doesNotTrackSubscriptionQueryWhenManagerIsNull() {
            // given
            Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
            stubGateway.subscriptionQueryPublisher = sink.asFlux();
            ShutdownTrackingQueryGateway testSubject =
                    new ShutdownTrackingQueryGateway(stubGateway, null, null);

            // when
            Publisher<String> result = testSubject.subscriptionQuery(QUERY,
                                                                     String.class,
                                                                     (ProcessingContext) null,
                                                                     256);

            // then — stream completes normally without any shutdown interference
            StepVerifier.create(result)
                        .then(() -> sink.tryEmitNext(RESPONSE))
                        .expectNext(RESPONSE)
                        .then(sink::tryEmitComplete)
                        .verifyComplete();
        }

        @Test
        void streamingManagerDoesNotAffectSubscriptionQuery() {
            // given — only streamingManager is set, subscriptionManager is null
            Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
            stubGateway.subscriptionQueryPublisher = sink.asFlux();
            ShutdownTrackingQueryGateway testSubject =
                    new ShutdownTrackingQueryGateway(stubGateway, null, streamingManager);

            // when
            Publisher<String> result = testSubject.subscriptionQuery(QUERY,
                                                                     String.class,
                                                                     (ProcessingContext) null,
                                                                     256);

            // then — shutting down streamingManager should not terminate the subscription query
            StepVerifier.create(result)
                        .then(streamingManager::shutdown)
                        .then(() -> sink.tryEmitNext(RESPONSE))
                        .expectNext(RESPONSE)
                        .then(sink::tryEmitComplete)
                        .verifyComplete();
        }
    }

    @Nested
    class SubscriptionQueryWithMapper {

        @Test
        void delegatesSubscriptionQueryWithMapperToUnderlyingGateway() {
            // given
            Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
            stubGateway.subscriptionQueryWithMapperPublisher = sink.asFlux();
            ShutdownTrackingQueryGateway testSubject =
                    new ShutdownTrackingQueryGateway(stubGateway, null, null);

            // when
            Publisher<String> result = testSubject.subscriptionQuery(QUERY, String.class, msg -> RESPONSE, null, 256);

            // then
            StepVerifier.create(result)
                        .then(() -> sink.tryEmitNext(RESPONSE))
                        .expectNext(RESPONSE)
                        .then(sink::tryEmitComplete)
                        .verifyComplete();
            assertThat(stubGateway.lastQuery).isEqualTo(QUERY);
        }

        @Test
        void passesBufferSizeAndContextToDelegateForSubscriptionQueryWithMapper() {
            // given
            stubGateway.subscriptionQueryWithMapperPublisher = Flux.empty();
            ShutdownTrackingQueryGateway testSubject =
                    new ShutdownTrackingQueryGateway(stubGateway, null, null);
            ProcessingContext ctx = mock(ProcessingContext.class);

            // when
            //noinspection ReactiveStreamsUnusedPublisher
            testSubject.subscriptionQuery(QUERY, String.class, msg -> RESPONSE, ctx, 128);

            // then
            assertThat(stubGateway.lastBufferSize).isEqualTo(128);
            assertThat(stubGateway.lastContext).isSameAs(ctx);
        }

        @Test
        void tracksSubscriptionQueryWithMapperWhenManagerIsSet() {
            // given
            Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
            stubGateway.subscriptionQueryWithMapperPublisher = sink.asFlux();
            ShutdownTrackingQueryGateway testSubject =
                    new ShutdownTrackingQueryGateway(stubGateway, subscriptionManager, null);

            // when
            Publisher<String> result = testSubject.subscriptionQuery(QUERY, String.class, msg -> RESPONSE, null, 256);

            // then — manager cancels the tracked stream on shutdown
            StepVerifier.create(result)
                        .then(() -> sink.tryEmitNext(RESPONSE))
                        .expectNext(RESPONSE)
                        .then(subscriptionManager::shutdown)
                        .expectErrorSatisfies(e -> assertThat(e)
                                .isInstanceOf(ShutdownInProgressException.class))
                        .verify(Duration.ofSeconds(2));
        }

        @Test
        void doesNotTrackSubscriptionQueryWithMapperWhenManagerIsNull() {
            // given
            Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
            stubGateway.subscriptionQueryWithMapperPublisher = sink.asFlux();
            ShutdownTrackingQueryGateway testSubject =
                    new ShutdownTrackingQueryGateway(stubGateway, null, null);

            // when
            Publisher<String> result = testSubject.subscriptionQuery(QUERY, String.class, msg -> RESPONSE, null, 256);

            // then — stream completes normally without any shutdown interference
            StepVerifier.create(result)
                        .then(() -> sink.tryEmitNext(RESPONSE))
                        .expectNext(RESPONSE)
                        .then(sink::tryEmitComplete)
                        .verifyComplete();
        }
    }

    @Nested
    class DescribeTo {

        @Test
        void alwaysDescribesDelegate() {
            // given
            ShutdownTrackingQueryGateway testSubject =
                    new ShutdownTrackingQueryGateway(stubGateway, null, null);
            ComponentDescriptor descriptor = mock(ComponentDescriptor.class);

            // when
            testSubject.describeTo(descriptor);

            // then
            verify(descriptor).describeWrapperOf(stubGateway);
        }

        @Test
        void describesSubscriptionQueryManagerWhenNonNull() {
            // given
            ShutdownTrackingQueryGateway testSubject =
                    new ShutdownTrackingQueryGateway(stubGateway, subscriptionManager, null);
            ComponentDescriptor descriptor = mock(ComponentDescriptor.class);

            // when
            testSubject.describeTo(descriptor);

            // then
            verify(descriptor).describeProperty("subscriptionQueryShutdownManager", subscriptionManager);
            verify(descriptor, never()).describeProperty(eq("streamingQueryShutdownManager"), (Object) any());
        }

        @Test
        void describesStreamingQueryManagerWhenNonNull() {
            // given
            ShutdownTrackingQueryGateway testSubject =
                    new ShutdownTrackingQueryGateway(stubGateway, null, streamingManager);
            ComponentDescriptor descriptor = mock(ComponentDescriptor.class);

            // when
            testSubject.describeTo(descriptor);

            // then
            verify(descriptor).describeProperty("streamingQueryShutdownManager", streamingManager);
            verify(descriptor, never()).describeProperty(eq("subscriptionQueryShutdownManager"), (Object) any());
        }

        @Test
        void describesBothManagersWhenBothAreSet() {
            // given
            ShutdownTrackingQueryGateway testSubject =
                    new ShutdownTrackingQueryGateway(stubGateway, subscriptionManager, streamingManager);
            ComponentDescriptor descriptor = mock(ComponentDescriptor.class);

            // when
            testSubject.describeTo(descriptor);

            // then
            verify(descriptor).describeWrapperOf(stubGateway);
            verify(descriptor).describeProperty("subscriptionQueryShutdownManager", subscriptionManager);
            verify(descriptor).describeProperty("streamingQueryShutdownManager", streamingManager);
        }

        @Test
        void doesNotDescribeNullManagers() {
            // given
            ShutdownTrackingQueryGateway testSubject =
                    new ShutdownTrackingQueryGateway(stubGateway, null, null);
            ComponentDescriptor descriptor = mock(ComponentDescriptor.class);

            // when
            testSubject.describeTo(descriptor);

            // then
            verify(descriptor).describeWrapperOf(stubGateway);
            verify(descriptor, never()).describeProperty(eq("subscriptionQueryShutdownManager"), (Object) any());
            verify(descriptor, never()).describeProperty(eq("streamingQueryShutdownManager"), (Object) any());
        }
    }
}
