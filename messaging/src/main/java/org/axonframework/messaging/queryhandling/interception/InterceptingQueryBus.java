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

import org.jspecify.annotations.Nullable;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.DecoratorDefinition;
import org.axonframework.messaging.core.DefaultMessageDispatchInterceptorChain;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageDispatchInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.queryhandling.QueryBus;
import org.axonframework.messaging.queryhandling.QueryHandler;
import org.axonframework.messaging.queryhandling.QueryMessage;
import org.axonframework.messaging.queryhandling.QueryResponseMessage;
import org.axonframework.messaging.queryhandling.SubscriptionQueryUpdateMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * A {@code QueryBus} wrapper that supports both {@link MessageHandlerInterceptor MessageHandlerInterceptors} and
 * {@link MessageDispatchInterceptor MessageDispatchInterceptors}. Actual dispatching and handling of queries is done by
 * a delegate.
 * <p>
 * This {@code InterceptingQueryBus} is typically registered as a
 * {@link ComponentRegistry#registerDecorator(DecoratorDefinition) decorator} and automatically kicks in whenever
 * {@link QueryMessage} specific {@code MessageHandlerInterceptors} or any {@code MessageDispatchInterceptors} are
 * present.
 *
 * @author Allard Buijze
 * @author Mateusz Nowak
 * @author Steven van Beelen
 * @since 5.0.0
 */
public class InterceptingQueryBus implements QueryBus {

    /**
     * The order in which the {@link InterceptingQueryBus} is applied as a
     * {@link ComponentRegistry#registerDecorator(DecoratorDefinition) decorator} to the {@link QueryBus}.
     * <p>
     * As such, any decorator with a lower value will be applied to the delegate, and any higher value will be applied
     * to the {@code InterceptingQueryBus} itself. Using the same value can either lead to application of the decorator
     * to the delegate or the {@code InterceptingQueryBus}, depending on the order of registration.
     * <p>
     * The order of the {@code InterceptingQueryBus} is set to {@code Integer.MIN_VALUE + 100} to ensure it is applied
     * very early in the configuration process, but not the earliest to allow for other decorators to be applied.
     */
    public static final int DECORATION_ORDER = Integer.MIN_VALUE + 100;

    private final QueryBus delegate;
    private final List<MessageHandlerInterceptor<? super QueryMessage>> handlerInterceptors;
    private final List<MessageDispatchInterceptor<? super QueryMessage>> dispatchInterceptors;
    private final List<MessageDispatchInterceptor<? super SubscriptionQueryUpdateMessage>> updateDispatchInterceptors;

    private final QueryInterceptingDispatcher queryInterceptingDispatcher;
    private final SubscriptionQueryInterceptingDispatcher subscriptionQueryInterceptingDispatcher;
    private final SubscribeToUpdatesInterceptingDispatcher subscribeToUpdatesInterceptingDispatcher;
    private final InterceptingResponseUpdateDispatcher interceptingResponseUpdateDispatcher;

    /**
     * Constructs a {@code InterceptingQueryBus}, delegating dispatching and handling logic to the given
     * {@code delegate}. The given {@code handlerInterceptors} are wrapped around the
     * {@link QueryHandler query handlers} when subscribing. The given {@code dispatchInterceptors} are invoked before
     * dispatching is provided to the given {@code delegate}. The given {@code updateDispatchInterceptors} are invoked
     * before emitting subscription query update.
     *
     * @param delegate                   The delegate {@code QueryBus} that will handle all dispatching and handling
     *                                   logic.
     * @param handlerInterceptors        The interception to invoke before handling a query and if present on the query
     *                                   result.
     * @param dispatchInterceptors       The interception to invoke before dispatching a query and on the query result.
     * @param updateDispatchInterceptors The interception to invoke before emitting subscription query update.
     */
    public InterceptingQueryBus(
            QueryBus delegate,
            List<MessageHandlerInterceptor<? super QueryMessage>> handlerInterceptors,
            List<MessageDispatchInterceptor<? super QueryMessage>> dispatchInterceptors,
            List<MessageDispatchInterceptor<? super SubscriptionQueryUpdateMessage>> updateDispatchInterceptors
    ) {
        this.delegate = requireNonNull(delegate, "The query bus delegate must not be null.");
        this.handlerInterceptors = new ArrayList<>(
                requireNonNull(handlerInterceptors, "The handler interception must not be null.")
        );
        this.dispatchInterceptors = new ArrayList<>(
                requireNonNull(dispatchInterceptors, "The dispatch interception must not be null.")
        );
        this.updateDispatchInterceptors = new ArrayList<>(
                requireNonNull(updateDispatchInterceptors, "The update dispatch interception must not be null.")
        );
        this.queryInterceptingDispatcher = new QueryInterceptingDispatcher(dispatchInterceptors, this::dispatchQuery);
        this.subscriptionQueryInterceptingDispatcher = new SubscriptionQueryInterceptingDispatcher(dispatchInterceptors,
                                                                                                   delegate);
        this.subscribeToUpdatesInterceptingDispatcher = new SubscribeToUpdatesInterceptingDispatcher(dispatchInterceptors,
                                                                                                      delegate);
        this.interceptingResponseUpdateDispatcher = new InterceptingResponseUpdateDispatcher(updateDispatchInterceptors);
    }

    @Override
    public InterceptingQueryBus subscribe(QualifiedName queryName,
                                          QueryHandler queryHandler) {
        if (handlerInterceptors.isEmpty()) {
            delegate.subscribe(queryName, queryHandler);
        } else {
            delegate.subscribe(queryName, new InterceptingHandler(queryHandler, handlerInterceptors));
        }
        return this;
    }

    @Override
    public MessageStream<QueryResponseMessage> query(QueryMessage query,
                                                     @Nullable ProcessingContext context) {
        return queryInterceptingDispatcher.dispatch(query, context);
    }

    @Override
    public MessageStream<QueryResponseMessage> subscriptionQuery(QueryMessage query,
                                                                 @Nullable ProcessingContext context,
                                                                 int updateBufferSize) {
        return subscriptionQueryInterceptingDispatcher.dispatch(query, context, updateBufferSize);
    }

    @Override
    public MessageStream<SubscriptionQueryUpdateMessage> subscribeToUpdates(QueryMessage query,
                                                                            int updateBufferSize) {
        return subscribeToUpdatesInterceptingDispatcher.dispatch(query, updateBufferSize);
    }

    @Override
    public CompletableFuture<Void> emitUpdate(Predicate<QueryMessage> filter,
                                              Supplier<SubscriptionQueryUpdateMessage> updateSupplier,
                                              @Nullable ProcessingContext context) {
        return interceptUpdate(updateSupplier, context,
                               supplier -> delegate.emitUpdate(filter, supplier, context));
    }

    @Override
    public CompletableFuture<OptionalInt> emitUpdateAndCount(Predicate<QueryMessage> filter,
                                                             Supplier<SubscriptionQueryUpdateMessage> updateSupplier,
                                                             @Nullable ProcessingContext context) {
        return interceptUpdate(updateSupplier, context,
                               supplier -> delegate.emitUpdateAndCount(filter, supplier, context));
    }

    private <R> CompletableFuture<R> interceptUpdate(
            Supplier<SubscriptionQueryUpdateMessage> updateSupplier,
            @Nullable ProcessingContext context,
            Function<Supplier<SubscriptionQueryUpdateMessage>, CompletableFuture<R>> emit
    ) {
        if (updateDispatchInterceptors.isEmpty()) {
            return emit.apply(updateSupplier);
        }

        try {
            SubscriptionQueryUpdateMessage update = updateSupplier.get();
            SubscriptionQueryUpdateMessage intercepted = interceptingResponseUpdateDispatcher.intercept(update,
                                                                                                        context);
            return emit.apply(() -> intercepted);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public CompletableFuture<Void> completeSubscriptions(Predicate<QueryMessage> filter,
                                                         @Nullable ProcessingContext context) {
        return delegate.completeSubscriptions(filter, context);
    }

    @Override
    public CompletableFuture<OptionalInt> completeSubscriptionsAndCount(Predicate<QueryMessage> filter,
                                                                        @Nullable ProcessingContext context) {
        return delegate.completeSubscriptionsAndCount(filter, context);
    }

    @Override
    public CompletableFuture<Void> completeSubscriptionsExceptionally(
            Predicate<QueryMessage> filter,
            Throwable cause,
            @Nullable ProcessingContext context
    ) {
        return delegate.completeSubscriptionsExceptionally(filter, cause, context);
    }

    @Override
    public CompletableFuture<OptionalInt> completeSubscriptionsExceptionallyAndCount(
            Predicate<QueryMessage> filter,
            Throwable cause,
            @Nullable ProcessingContext context
    ) {
        return delegate.completeSubscriptionsExceptionallyAndCount(filter, cause, context);
    }

    private MessageStream<?> dispatchQuery(Message message,
                                           @Nullable ProcessingContext processingContext) {
        if (!(message instanceof QueryMessage query)) {
            // The compiler should avoid this from happening.
            throw new IllegalArgumentException("Unsupported message implementation: " + message);
        }
        return delegate.query(query, processingContext);
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeWrapperOf(delegate);
        descriptor.describeProperty("handlerInterceptors", handlerInterceptors);
        descriptor.describeProperty("dispatchInterceptors", dispatchInterceptors);
        descriptor.describeProperty("updateDispatchInterceptors", updateDispatchInterceptors);
    }

    private static class InterceptingHandler implements QueryHandler {

        private final QueryMessageHandlerInterceptorChain interceptorChain;

        private InterceptingHandler(QueryHandler handler,
                                    List<MessageHandlerInterceptor<? super QueryMessage>> interceptors) {
            this.interceptorChain = new QueryMessageHandlerInterceptorChain(interceptors, handler);
        }

        @Override
        public MessageStream<QueryResponseMessage> handle(QueryMessage query,
                                                          ProcessingContext context) {
            return interceptorChain.proceed(query, context)
                                   .cast();
        }
    }

    private static class QueryInterceptingDispatcher {

        private final DefaultMessageDispatchInterceptorChain<? super QueryMessage> interceptorChain;

        private QueryInterceptingDispatcher(
                List<MessageDispatchInterceptor<? super QueryMessage>> interceptors,
                BiFunction<? super QueryMessage, ProcessingContext, MessageStream<?>> dispatcher
        ) {
            this.interceptorChain = new DefaultMessageDispatchInterceptorChain<>(interceptors, dispatcher);
        }

        private MessageStream<QueryResponseMessage> dispatch(
                QueryMessage query,
                @Nullable ProcessingContext context
        ) {
            return interceptorChain.proceed(query, context)
                                   .cast();
        }
    }

    private static class SubscriptionQueryInterceptingDispatcher {

        private final List<MessageDispatchInterceptor<? super QueryMessage>> interceptors;
        private final QueryBus delegate;

        private SubscriptionQueryInterceptingDispatcher(
                List<MessageDispatchInterceptor<? super QueryMessage>> interceptors,
                QueryBus delegate
        ) {
            this.interceptors = interceptors;
            this.delegate = delegate;
        }

        private MessageStream<QueryResponseMessage> dispatch(
                QueryMessage query,
                @Nullable ProcessingContext context,
                int updateBufferSize
        ) {
            // Create a new chain per call because the dispatcher needs the updateBufferSize parameter
            // which varies per invocation and is not part of the BiFunction signature.
            // We cannot use Processing Context to pass this value, because Processing Context can be null.
            BiFunction<? super QueryMessage, ProcessingContext, MessageStream<?>> subscriptionDispatcher =
                    (interceptedQuery, interceptedContext) -> delegate.subscriptionQuery(interceptedQuery,
                                                                                         interceptedContext,
                                                                                         updateBufferSize);

            return new DefaultMessageDispatchInterceptorChain<>(
                    interceptors,
                    subscriptionDispatcher
            ).proceed(query, context).cast();
        }
    }

    private static class SubscribeToUpdatesInterceptingDispatcher {

        private final List<MessageDispatchInterceptor<? super QueryMessage>> interceptors;
        private final QueryBus delegate;

        private SubscribeToUpdatesInterceptingDispatcher(
                List<MessageDispatchInterceptor<? super QueryMessage>> interceptors,
                QueryBus delegate
        ) {
            this.interceptors = interceptors;
            this.delegate = delegate;
        }

        private MessageStream<SubscriptionQueryUpdateMessage> dispatch(
                QueryMessage query,
                int updateBufferSize
        ) {
            // Create a new chain per call because the dispatcher needs the updateBufferSize parameter
            // which varies per invocation and is not part of the BiFunction signature.
            // We cannot use Processing Context to pass this value, because Processing Context can be null.
            BiFunction<? super QueryMessage, ProcessingContext, MessageStream<?>> subscribeToUpdatesDispatcher =
                    (interceptedQuery, interceptedContext) -> delegate.subscribeToUpdates(interceptedQuery, updateBufferSize);

            return new DefaultMessageDispatchInterceptorChain<>(
                    interceptors,
                    subscribeToUpdatesDispatcher
            ).proceed(query, null).cast();
        }
    }

    private static class InterceptingResponseUpdateDispatcher {

        private final DefaultMessageDispatchInterceptorChain<? super SubscriptionQueryUpdateMessage> interceptorChain;

        private InterceptingResponseUpdateDispatcher(
                List<MessageDispatchInterceptor<? super SubscriptionQueryUpdateMessage>> interceptors
        ) {
            BiFunction<? super SubscriptionQueryUpdateMessage, ProcessingContext, MessageStream<?>> dispatcher =
                    (message, context) -> MessageStream.just(message).cast();
            this.interceptorChain = new DefaultMessageDispatchInterceptorChain<>(interceptors, dispatcher);
        }

        private SubscriptionQueryUpdateMessage intercept(
                SubscriptionQueryUpdateMessage update,
                @Nullable ProcessingContext context
        ) {
            @SuppressWarnings("unchecked")
            MessageStream<SubscriptionQueryUpdateMessage> intercepted =
                    (MessageStream<SubscriptionQueryUpdateMessage>) interceptorChain.proceed(update, context);
            return intercepted.first()
                              .asCompletableFuture()
                              .join()
                              .message();
        }
    }
}
