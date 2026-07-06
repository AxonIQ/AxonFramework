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

package org.axonframework.messaging.queryhandling.tracing;

import org.axonframework.messaging.queryhandling.tracing.TracingQueryBus;
import org.axonframework.messaging.tracing.support.TestSpanFactory;
import org.axonframework.messaging.tracing.support.TestSpanFactory.TestSpanType;
import org.axonframework.common.infra.ComponentDescriptor;
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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class TracingQueryBusTest {

    private static final String DISPATCH_SPAN = "QueryBus.query MyQuery";
    private static final String SUBSCRIPTION_DISPATCH_SPAN = "QueryBus.subscriptionQuery MyQuery";
    private static final String HANDLE_SPAN = "QueryBus.handleQuery MyQuery";

    private TestSpanFactory spanFactory;
    private RecordingQueryBus delegate;
    private TracingQueryBus testSubject;

    private final QueryMessage query =
            new GenericQueryMessage(new MessageType("MyQuery"), "the-payload");
    private final QueryResponseMessage response =
            new GenericQueryResponseMessage(new MessageType("Result"), "result-payload");

    @BeforeEach
    void setUp() {
        spanFactory = new TestSpanFactory();
        delegate = new RecordingQueryBus();
        testSubject = new TracingQueryBus(delegate, spanFactory);
    }

    @Nested
    class Query {

        @Test
        void opensCompletesAndPropagatesADispatchSpan() {
            // given
            delegate.queryResult = MessageStream.just(response);

            // when
            testSubject.query(query, null).first().asCompletableFuture().join();

            // then
            spanFactory.verifySpanCompleted(DISPATCH_SPAN);
            spanFactory.verifySpanHasType(DISPATCH_SPAN, TestSpanType.DISPATCH);
            spanFactory.verifySpanPropagated(DISPATCH_SPAN, query);
        }
    }

    @Nested
    class SubscriptionQuery {

        @Test
        void opensCompletesAndPropagatesADispatchSpan() {
            // given
            delegate.subscriptionQueryResult = MessageStream.just(response);

            // when
            testSubject.subscriptionQuery(query, null, 256).first().asCompletableFuture().join();

            // then
            spanFactory.verifySpanCompleted(SUBSCRIPTION_DISPATCH_SPAN);
            spanFactory.verifySpanHasType(SUBSCRIPTION_DISPATCH_SPAN, TestSpanType.DISPATCH);
            spanFactory.verifySpanPropagated(SUBSCRIPTION_DISPATCH_SPAN, query);
        }
    }

    @Nested
    class Handle {

        @Test
        void wrapsSubscribedHandlerToOpenAHandlerSpan() {
            // given
            testSubject.subscribe(new QualifiedName("MyQuery"),
                                  (q, context) -> MessageStream.just(response));
            QueryHandler wrapped = delegate.subscribedHandler.get();

            // when
            wrapped.handle(query, new StubProcessingContext());

            // then
            assertThat(wrapped).isNotNull();
            spanFactory.verifySpanActive(HANDLE_SPAN);
            spanFactory.verifySpanHasType(HANDLE_SPAN, TestSpanType.HANDLER);
        }
    }

    @Nested
    class UpdateLifecycle {

        @Test
        void opensAnInternalSpanAroundEmitUpdate() {
            // given
            delegate.emitResult = CompletableFuture.completedFuture(null);

            // when
            testSubject.emitUpdate(q -> true, () -> updateMessage(), null).join();

            // then
            spanFactory.verifySpanCompleted("QueryBus.emitUpdate");
            spanFactory.verifySpanHasType("QueryBus.emitUpdate", TestSpanType.INTERNAL);
        }

        @Test
        void opensAnInternalSpanAroundCompleteSubscriptions() {
            // given
            delegate.completeResult = CompletableFuture.completedFuture(null);

            // when
            testSubject.completeSubscriptions(q -> true, null).join();

            // then
            spanFactory.verifySpanCompleted("QueryBus.completeSubscriptions");
            spanFactory.verifySpanHasType("QueryBus.completeSubscriptions", TestSpanType.INTERNAL);
        }

        @Test
        void opensAnInternalSpanAroundCompleteSubscriptionsExceptionally() {
            // given
            delegate.completeExceptionallyResult = CompletableFuture.completedFuture(null);

            // when
            testSubject.completeSubscriptionsExceptionally(q -> true, new IllegalStateException("boom"), null).join();

            // then
            spanFactory.verifySpanCompleted("QueryBus.completeSubscriptionsExceptionally");
            spanFactory.verifySpanHasType("QueryBus.completeSubscriptionsExceptionally", TestSpanType.INTERNAL);
        }
    }

    @Nested
    class Introspection {

        @Test
        void describesItselfAsAWrapperOfTheDelegate() {
            // given
            RecordingComponentDescriptor descriptor = new RecordingComponentDescriptor();

            // when
            testSubject.describeTo(descriptor);

            // then
            assertThat(descriptor.wrapped).isSameAs(delegate);
        }
    }

    private static SubscriptionQueryUpdateMessage updateMessage() {
        return new GenericSubscriptionQueryUpdateMessage(new MessageType("MyUpdate"), "update-payload");
    }

    /**
     * Minimal {@link QueryBus} stub recording subscribed handlers and returning configurable dispatch results.
     */
    private static final class RecordingQueryBus implements QueryBus {

        private final AtomicReference<QueryHandler> subscribedHandler = new AtomicReference<>();
        private MessageStream<QueryResponseMessage> queryResult = MessageStream.empty().cast();
        private MessageStream<QueryResponseMessage> subscriptionQueryResult = MessageStream.empty().cast();
        private CompletableFuture<Void> emitResult = CompletableFuture.completedFuture(null);
        private CompletableFuture<Void> completeResult = CompletableFuture.completedFuture(null);
        private CompletableFuture<Void> completeExceptionallyResult = CompletableFuture.completedFuture(null);

        @Override
        public MessageStream<QueryResponseMessage> query(QueryMessage query, @Nullable ProcessingContext context) {
            return queryResult;
        }

        @Override
        public MessageStream<QueryResponseMessage> subscriptionQuery(QueryMessage query,
                                                                     @Nullable ProcessingContext context,
                                                                     int updateBufferSize) {
            return subscriptionQueryResult;
        }

        @Override
        public MessageStream<SubscriptionQueryUpdateMessage> subscribeToUpdates(QueryMessage query,
                                                                                int updateBufferSize) {
            return MessageStream.empty().cast();
        }

        @Override
        public CompletableFuture<Void> emitUpdate(Predicate<QueryMessage> filter,
                                                  Supplier<SubscriptionQueryUpdateMessage> updateSupplier,
                                                  @Nullable ProcessingContext context) {
            return emitResult;
        }

        @Override
        public CompletableFuture<Void> completeSubscriptions(Predicate<QueryMessage> filter,
                                                             @Nullable ProcessingContext context) {
            return completeResult;
        }

        @Override
        public CompletableFuture<Void> completeSubscriptionsExceptionally(Predicate<QueryMessage> filter,
                                                                          Throwable cause,
                                                                          @Nullable ProcessingContext context) {
            return completeExceptionallyResult;
        }

        @Override
        public QueryBus subscribe(QualifiedName queryName, QueryHandler queryHandler) {
            subscribedHandler.set(queryHandler);
            return this;
        }

        @Override
        public void describeTo(ComponentDescriptor descriptor) {
            // not relevant
        }
    }

    private static final class RecordingComponentDescriptor implements ComponentDescriptor {

        private @Nullable Object wrapped;

        @Override
        public void describeWrapperOf(Object delegate) {
            this.wrapped = delegate;
        }

        @Override
        public void describeProperty(String name, @Nullable Object object) {
        }

        @Override
        public void describeProperty(String name, @Nullable Collection<?> collection) {
        }

        @Override
        public void describeProperty(String name, @Nullable Map<?, ?> map) {
        }

        @Override
        public void describeProperty(String name, @Nullable String value) {
        }

        @Override
        public void describeProperty(String name, @Nullable Long value) {
        }

        @Override
        public void describeProperty(String name, @Nullable Boolean value) {
        }
    }
}
