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

import org.axonframework.messaging.tracing.Span;
import org.axonframework.messaging.tracing.SpanFactory;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.queryhandling.QueryBus;
import org.axonframework.messaging.queryhandling.QueryHandler;
import org.axonframework.messaging.queryhandling.QueryMessage;
import org.axonframework.messaging.queryhandling.QueryResponseMessage;
import org.axonframework.messaging.queryhandling.SubscriptionQueryUpdateMessage;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Delegating {@link QueryBus} decorator that opens a tracing span around query dispatch, query handling, subscription
 * queries, and query-update operations.
 * <p>
 * Every {@code query} dispatch opens a dispatch span (kind producer), the active tracing context is propagated onto the
 * query's metadata so a remote handler can continue the trace, and the span is ended when the result stream completes.
 * Each subscribed {@link QueryHandler} is wrapped so that handling opens a handler span -- parented on the dispatch span
 * via the propagated context -- bound to the handling {@link ProcessingContext}'s lifecycle.
 * <p>
 * Update emission (the AF4 {@code QueryUpdateEmitter} concern) is traced here too: AF5's
 * {@code SimpleQueryUpdateEmitter} delegates {@code emit} / {@code complete} / {@code completeExceptionally} to the
 * matching {@link QueryBus} methods, so wrapping the bus naturally traces the emitter as well.
 * <p>
 * This decorator is registered by {@code MessagingTracingConfigurationEnhancer}; it is never instantiated directly by
 * applications.
 *
 * @author Mateusz Nowak
 * @author Mitchell Herrijgers
 * @since 5.2.0
 */
@Internal
public final class TracingQueryBus implements QueryBus {

    /** Prefix for the query-dispatch span ({@code "QueryBus.query <name>"}). */
    public static final String DISPATCH_SPAN = "QueryBus.query";

    /** Prefix for the query-handle span ({@code "QueryBus.handleQuery <name>"}). */
    public static final String HANDLE_SPAN = "QueryBus.handleQuery";

    /** Prefix for the subscription-query-dispatch span ({@code "QueryBus.subscriptionQuery <name>"}). */
    public static final String SUBSCRIPTION_QUERY_SPAN = "QueryBus.subscriptionQuery";

    /** Name of the query-update-emit span. */
    public static final String EMIT_UPDATE_SPAN = "QueryBus.emitUpdate";

    /** Name of the subscription-completion span. */
    public static final String COMPLETE_SUBSCRIPTIONS_SPAN = "QueryBus.completeSubscriptions";

    /** Name of the subscription-exceptional-completion span. */
    public static final String COMPLETE_SUBSCRIPTIONS_EXCEPTIONALLY_SPAN = "QueryBus.completeSubscriptionsExceptionally";

    private final QueryBus delegate;
    private final SpanFactory spanFactory;

    /**
     * Initializes a tracing {@link QueryBus} wrapping the given {@code delegate}, obtaining spans from the given
     * {@code spanFactory}.
     *
     * @param delegate    the query bus to delegate to
     * @param spanFactory the factory producing the tracing spans
     */
    public TracingQueryBus(QueryBus delegate, SpanFactory spanFactory) {
        this.delegate = Objects.requireNonNull(delegate, "delegate may not be null");
        this.spanFactory = Objects.requireNonNull(spanFactory, "spanFactory may not be null");
    }

    @Override
    public MessageStream<QueryResponseMessage> query(QueryMessage query, @Nullable ProcessingContext context) {
        Span span = spanFactory.createDispatchSpan(
                DISPATCH_SPAN + " " + query.type().qualifiedName().name(), query, context
        );
        return span.runSupplier(() -> delegate.query(span.propagateContext(query), context));
    }

    @Override
    public MessageStream<QueryResponseMessage> subscriptionQuery(QueryMessage query,
                                                                 @Nullable ProcessingContext context,
                                                                 int updateBufferSize) {
        Span span = spanFactory.createDispatchSpan(
                SUBSCRIPTION_QUERY_SPAN + " " + query.type().qualifiedName().name(), query, context
        );
        return span.runSupplier(
                () -> delegate.subscriptionQuery(span.propagateContext(query), context, updateBufferSize)
        );
    }

    @Override
    public MessageStream<SubscriptionQueryUpdateMessage> subscribeToUpdates(QueryMessage query,
                                                                            int updateBufferSize) {
        return delegate.subscribeToUpdates(query, updateBufferSize);
    }

    @Override
    public CompletableFuture<Void> emitUpdate(Predicate<QueryMessage> filter,
                                              Supplier<SubscriptionQueryUpdateMessage> updateSupplier,
                                              @Nullable ProcessingContext context) {
        Span span = spanFactory.createInternalSpan(EMIT_UPDATE_SPAN, context);
        return span.runSupplierAsync(() -> delegate.emitUpdate(filter, updateSupplier, context));
    }

    @Override
    public CompletableFuture<Void> completeSubscriptions(Predicate<QueryMessage> filter,
                                                         @Nullable ProcessingContext context) {
        Span span = spanFactory.createInternalSpan(COMPLETE_SUBSCRIPTIONS_SPAN, context);
        return span.runSupplierAsync(() -> delegate.completeSubscriptions(filter, context));
    }

    @Override
    public CompletableFuture<Void> completeSubscriptionsExceptionally(Predicate<QueryMessage> filter,
                                                                      Throwable cause,
                                                                      @Nullable ProcessingContext context) {
        Span span = spanFactory.createInternalSpan(COMPLETE_SUBSCRIPTIONS_EXCEPTIONALLY_SPAN, context);
        return span.runSupplierAsync(() -> delegate.completeSubscriptionsExceptionally(filter, cause, context));
    }

    @Override
    public QueryBus subscribe(QualifiedName queryName, QueryHandler queryHandler) {
        delegate.subscribe(queryName, new TracingQueryHandler(queryHandler, spanFactory));
        return this;
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeWrapperOf(delegate);
        descriptor.describeProperty("spanFactory", spanFactory);
    }

    /**
     * Wraps a {@link QueryHandler} to open a handler span around its invocation, bound to the handling context's
     * lifecycle.
     */
    private static final class TracingQueryHandler implements QueryHandler {

        private final QueryHandler delegate;
        private final SpanFactory spanFactory;

        private TracingQueryHandler(QueryHandler delegate, SpanFactory spanFactory) {
            this.delegate = delegate;
            this.spanFactory = spanFactory;
        }

        @Override
        public MessageStream<QueryResponseMessage> handle(QueryMessage query, ProcessingContext context) {
            spanFactory.createHandlerSpan(HANDLE_SPAN + " " + query.type().qualifiedName().name(), query, context)
                       .start(context);
            return delegate.handle(query, context);
        }
    }
}
