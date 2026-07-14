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
 * Update emission is traced here too: {@code SimpleQueryUpdateEmitter} delegates {@code emit} / {@code complete} /
 * {@code completeExceptionally} to the matching {@link QueryBus} methods, so wrapping the bus naturally traces the
 * emitter as well.
 * <p>
 * This decorator is registered by {@code MessagingTracingConfigurationEnhancer}; it is never instantiated directly by
 * applications.
 *
 * @author Mateusz Nowak
 * @author Mitchell Herrijgers
 * @since 5.3.0
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
        // Branch-scoped: the branched context makes connector internals nest under this dispatch span (as for
        // TracingCommandBus). Closing on the result stream's own termination -- not on its mere construction -- also
        // keeps the span's duration honest: a query dispatch span used to close the instant the (lazy) MessageStream
        // was constructed, understating how long the dispatch actually took.
        return span.branchStream(context, dispatchContext -> delegate.query(span.propagateContext(query),
                                                                            dispatchContext));
    }

    @Override
    public MessageStream<QueryResponseMessage> subscriptionQuery(QueryMessage query,
                                                                 @Nullable ProcessingContext context,
                                                                 int updateBufferSize) {
        Span span = spanFactory.createDispatchSpan(
                SUBSCRIPTION_QUERY_SPAN + " " + query.type().qualifiedName().name(), query, context
        );
        // Branch-scoped like query() above, but closed synchronously around the subscription's setup (Span#branch
        // closes on return) rather than on stream termination: a subscription query's update
        // stream is long-lived (potentially unbounded), so a dispatch span spanning its whole lifetime would never
        // end.
        return span.branch(context, dispatchContext -> delegate.subscriptionQuery(span.propagateContext(query),
                                                                                  dispatchContext,
                                                                                  updateBufferSize));
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
        return span.branchAsync(context, scoped -> delegate.emitUpdate(filter, updateSupplier, scoped));
    }

    @Override
    public CompletableFuture<Void> completeSubscriptions(Predicate<QueryMessage> filter,
                                                         @Nullable ProcessingContext context) {
        Span span = spanFactory.createInternalSpan(COMPLETE_SUBSCRIPTIONS_SPAN, context);
        return span.branchAsync(context, scoped -> delegate.completeSubscriptions(filter, scoped));
    }

    @Override
    public CompletableFuture<Void> completeSubscriptionsExceptionally(Predicate<QueryMessage> filter,
                                                                      Throwable cause,
                                                                      @Nullable ProcessingContext context) {
        Span span = spanFactory.createInternalSpan(COMPLETE_SUBSCRIPTIONS_EXCEPTIONALLY_SPAN, context);
        return span.branchAsync(context,
                                scoped -> delegate.completeSubscriptionsExceptionally(filter, cause, scoped));
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
                       .coverLifecycle(context);
            return delegate.handle(query, context);
        }
    }
}
