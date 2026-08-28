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
package configuration.enhancer;

import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.queryhandling.QueryBus;
import org.axonframework.messaging.queryhandling.QueryHandler;
import org.axonframework.messaging.queryhandling.QueryMessage;
import org.axonframework.messaging.queryhandling.QueryResponseMessage;
import org.axonframework.messaging.queryhandling.SubscriptionQueryUpdateMessage;
import org.axonframework.messaging.tracing.Span;
import org.axonframework.messaging.tracing.SpanFactory;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.function.Supplier;

class TracingQueryBusDecorator implements QueryBus {

    private final QueryBus delegate;
    private final SpanFactory spanFactory;

    TracingQueryBusDecorator(QueryBus delegate, SpanFactory spanFactory) {
        this.delegate = delegate;
        this.spanFactory = spanFactory;
    }

    @NonNull
    @Override
    public MessageStream<QueryResponseMessage> query(@NonNull QueryMessage query, @Nullable ProcessingContext context) {
        Span span = spanFactory.createDispatchSpan(
                "QueryBus.query " + query.type().qualifiedName().name(), query, context
        );
        return span.branchStream(context, ctx -> delegate.query(span.propagateContext(query), ctx));
    }

    @NonNull
    @Override
    public MessageStream<QueryResponseMessage> subscriptionQuery(@NonNull QueryMessage query,
                                                                 @Nullable ProcessingContext context,
                                                                 int updateBufferSize) {
        return delegate.subscriptionQuery(query, context, updateBufferSize);
    }

    @NonNull
    @Override
    public MessageStream<SubscriptionQueryUpdateMessage> subscribeToUpdates(@NonNull QueryMessage query,
                                                                            int updateBufferSize) {
        return delegate.subscribeToUpdates(query, updateBufferSize);
    }

    @NonNull
    @Override
    public CompletableFuture<Void> emitUpdate(@NonNull Predicate<QueryMessage> filter,
                                              @NonNull Supplier<SubscriptionQueryUpdateMessage> updateSupplier,
                                              @Nullable ProcessingContext context) {
        return delegate.emitUpdate(filter, updateSupplier, context);
    }

    @NonNull
    @Override
    public CompletableFuture<Void> completeSubscriptions(@NonNull Predicate<QueryMessage> filter,
                                                         @Nullable ProcessingContext context) {
        return delegate.completeSubscriptions(filter, context);
    }

    @NonNull
    @Override
    public CompletableFuture<Void> completeSubscriptionsExceptionally(@NonNull Predicate<QueryMessage> filter,
                                                                      @NonNull Throwable cause,
                                                                      @Nullable ProcessingContext context) {
        return delegate.completeSubscriptionsExceptionally(filter, cause, context);
    }

    @Override
    public QueryBus subscribe(@NonNull QualifiedName name, @NonNull QueryHandler queryHandler) {
        delegate.subscribe(name, queryHandler);
        return this;
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeWrapperOf(delegate);
        descriptor.describeProperty("spanFactory", spanFactory);
    }
}
