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

import org.jspecify.annotations.Nullable;
import org.axonframework.common.infra.DescribableComponent;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The mechanism that dispatches {@link QueryMessage queries} to their appropriate {@link QueryHandler query handler}.
 * <p>
 * Query handlers can {@link #subscribe(QualifiedName, QueryHandler) subscribe} to the query bus to handle queries
 * matching the {@link QualifiedName}.
 * <p>
 * Hence, queries dispatched (through either {@link #query(QueryMessage, ProcessingContext)},
 * {@link #query(QueryMessage, ProcessingContext)}, and
 * {@link #subscriptionQuery(QueryMessage, ProcessingContext, int)}) match a subscribed query handler based
 * on "query name".
 * <p>
 * There may be multiple handlers for each query.
 *
 * @author Marc Gathier
 * @author Allard Buijze
 * @since 3.1
 */
public interface QueryBus extends QueryHandlerRegistry<QueryBus>, DescribableComponent {

    /**
     * Sentinel value returned by {@link #emitUpdateAndCount(Predicate, Supplier, ProcessingContext)},
     * {@link #completeSubscriptionsAndCount(Predicate, ProcessingContext)}, and
     * {@link #completeSubscriptionsExceptionallyAndCount(Predicate, Throwable, ProcessingContext)} by
     * implementations that cannot report how many subscription queries were affected.
     */
    int UNKNOWN_MATCH_COUNT = -1;

    /**
     * Dispatch the given {@code query} to a {@link QueryHandler}
     * {@link #subscribe(QualifiedName, QueryHandler) subscribed} to the given {@code query}'s
     * {@link MessageType#qualifiedName() query name}, returning a
     * {@link MessageStream} of {@link QueryResponseMessage responses} to the given {@code query}.
     * <p>
     * The resulting {@code MessageStream} will contain 0, 1, or N {@link QueryResponseMessage QueryResponseMessages},
     * depending on the {@code QueryHandler} that handled the given {@code query}.
     * <p>
     * As several {@code QueryHandlers} can be registered for the same query name, this method will
     * loop through them (in insert order) until one has a suitable return value. A suitable response is any value or
     * user exception returned from a {@code QueryHandler}. When no handlers are available that can answer the given
     * {@code query}, the returned {@code MessageStream} will have {@link MessageStream#failed(Throwable) failed} with a
     * {@link NoHandlerForQueryException}.
     *
     * @param query   The query to dispatch.
     * @param context The processing context under which the query is being published (can be {@code null}).
     * @return A {@code MessageStream} containing either 0, 1, or N {@link QueryResponseMessage QueryResponseMessages}.
     * @throws NoHandlerForQueryException When no {@link QueryHandler} is registered for the given {@code query}'s
     *                                    {@link MessageType#qualifiedName() query name}.
     */
    MessageStream<QueryResponseMessage> query(QueryMessage query, @Nullable ProcessingContext context);

    /**
     * Dispatch the given {@code query} to a single QueryHandler subscribed to the given {@code query}'s
     * queryName/initialResponseType/updateResponseType. The result is lazily created and there will be no execution of
     * the query handler before there is a subscription to the initial result. In order not to miss update, the query
     * bus will queue all update which happen after the subscription query is done and once the subscription to the
     * flux is made, these update will be emitted.
     * <p>
     * If there is an error during retrieving or consuming initial result, stream for incremental update is NOT
     * interrupted.
     * <p>
     * If there is an error during emitting an update, subscription is cancelled causing further emits not reaching the
     * destination.
     * <p>
     * If a subscription query with the same {@code query} identifier is already registered, the returned
     * {@link MessageStream} will be {@link MessageStream#failed(Throwable) failed} with a
     * {@link SubscriptionQueryAlreadyRegisteredException} instead of throwing the exception. This allows callers to
     * handle double subscription scenarios gracefully through the stream API.
     *
     * @param query            The subscription query to dispatch.
     * @param context          The processing context under which the query is being published (can be {@code null}).
     * @param updateBufferSize The size of the buffer which accumulates update.
     * @return query result containing initial result and incremental update, or a failed {@link MessageStream} with
     * {@link SubscriptionQueryAlreadyRegisteredException} if a subscription with the same query identifier already
     * exists.
     */
    MessageStream<QueryResponseMessage> subscriptionQuery(QueryMessage query,
                                                          @Nullable ProcessingContext context,
                                                          int updateBufferSize);

    /**
     * Subscribes the given {@code query} with the given {@code updateBufferSize}, and returns the MessageStream
     * that provides the update of the subscription query.
     * <p>
     * Can be used directly instead when fine-grained control of update handlers is required. If using the
     * update directly is not mandatory for your use case, we strongly recommend using
     * {@link #subscriptionQuery(QueryMessage, ProcessingContext, int)} instead.
     * <p>
     * Note that the returned MessageStream must be consumed from before the buffer fills up. Once the buffer is full,
     * any attempt to add an update will complete the stream with an exception.
     * <p>
     * If a subscription query with the same {@code query} identifier is already registered, the returned
     * {@link MessageStream} will be {@link MessageStream#failed(Throwable) failed} with a
     * {@link SubscriptionQueryAlreadyRegisteredException} instead of throwing the exception.
     *
     * @param query            The subscription query for which we register an update handler.
     * @param updateBufferSize The size of the buffer that accumulates update.
     * @return a MessageStream of update for the given subscription query, or a failed {@link MessageStream} with
     * {@link SubscriptionQueryAlreadyRegisteredException} if a subscription with the same query identifier already
     * exists.
     */
    MessageStream<SubscriptionQueryUpdateMessage> subscribeToUpdates(QueryMessage query,
                                                                     int updateBufferSize);

    /**
     * Emits the outcome of the {@code updateSupplier} to
     * {@link QueryBus#subscriptionQuery(QueryMessage, ProcessingContext, int) subscription queries}
     * matching the given {@code queryName} and given {@code filter}.
     *
     * @param filter         A predicate filtering on {@link QueryMessage QueryMessages}. The
     *                       {@code updateSupplier} will only be sent to subscription queries matching this filter.
     * @param updateSupplier The update supplier to emit for
     *                       {@link QueryBus#subscriptionQuery(QueryMessage, ProcessingContext, int)
     *                       subscription queries} matching the given {@code filter}.
     * @param context        The processing context under which the updateSupplier is being emitted (can be
     *                       {@code null}).
     * @return A future completing whenever the updateSupplier has been emitted.
     */
    CompletableFuture<Void> emitUpdate(Predicate<QueryMessage> filter,
                                       Supplier<SubscriptionQueryUpdateMessage> updateSupplier,
                                       @Nullable ProcessingContext context);

    /**
     * Emits the outcome of the {@code updateSupplier} to
     * {@link QueryBus#subscriptionQuery(QueryMessage, ProcessingContext, int) subscription queries} matching the given
     * {@code queryName} and given {@code filter}, returning the number of subscription queries the update was emitted
     * to.
     * <p>
     * Implementations that cannot determine this number return {@link #UNKNOWN_MATCH_COUNT} instead.
     *
     * @param filter         a predicate filtering on {@link QueryMessage QueryMessages}. The {@code updateSupplier}
     *                       will only be sent to subscription queries matching this filter
     * @param updateSupplier the update supplier to emit for
     *                       {@link QueryBus#subscriptionQuery(QueryMessage, ProcessingContext, int) subscription
     *                       queries} matching the given {@code filter}
     * @param context        the processing context under which the updateSupplier is being emitted (can be
     *                       {@code null})
     * @return a future completing with the number of subscription queries the update was emitted to, or
     * {@link #UNKNOWN_MATCH_COUNT} if this implementation cannot determine that number
     */
    default CompletableFuture<Integer> emitUpdateAndCount(Predicate<QueryMessage> filter,
                                                          Supplier<SubscriptionQueryUpdateMessage> updateSupplier,
                                                          @Nullable ProcessingContext context) {
        return emitUpdate(filter, updateSupplier, context).thenApply(v -> UNKNOWN_MATCH_COUNT);
    }

    /**
     * Completes
     * {@link QueryBus#subscriptionQuery(QueryMessage, ProcessingContext, int) subscription queries}
     * matching the given {@code filter}.
     * <p>
     * To be used whenever there are no subsequent update to
     * {@link #emitUpdate(Predicate, Supplier, ProcessingContext) emit} left.
     *
     * @param filter  A predicate filtering on {@link QueryMessage QueryMessages}. Subscription
     *                queries matching this filter will be completed.
     * @param context The processing context within which to complete subscription queries (can be {@code null}).
     * @return A future completing whenever all matching
     * {@link QueryBus#subscriptionQuery(QueryMessage, ProcessingContext, int) subscription queries} have
     * been completed.
     */
    CompletableFuture<Void> completeSubscriptions(Predicate<QueryMessage> filter,
                                                  @Nullable ProcessingContext context);

    /**
     * Completes {@link QueryBus#subscriptionQuery(QueryMessage, ProcessingContext, int) subscription queries} matching
     * the given {@code filter}, returning the number of subscription queries that were completed.
     * <p>
     * Implementations that cannot determine this number return {@link #UNKNOWN_MATCH_COUNT} instead.
     *
     * @param filter  a predicate filtering on {@link QueryMessage QueryMessages}. Subscription queries matching this
     *                filter will be completed
     * @param context the processing context within which to complete subscription queries (can be {@code null})
     * @return a future completing with the number of subscription queries that were completed, or
     * {@link #UNKNOWN_MATCH_COUNT} if this implementation cannot determine that number
     */
    default CompletableFuture<Integer> completeSubscriptionsAndCount(Predicate<QueryMessage> filter,
                                                                     @Nullable ProcessingContext context) {
        return completeSubscriptions(filter, context).thenApply(v -> UNKNOWN_MATCH_COUNT);
    }

    /**
     * Completes
     * {@link QueryBus#subscriptionQuery(QueryMessage, ProcessingContext, int) subscription queries}
     * matching the given {@code filter} exceptionally with the given {@code cause}.
     * <p>
     * To be used whenever {@link #emitUpdate(Predicate, Supplier, ProcessingContext) emitting update} should be
     * stopped due to some exception.
     *
     * @param filter  A predicate filtering on {@link QueryMessage QueryMessages}. Subscription
     *                queries matching this filter will be completed exceptionally.
     * @param cause   the cause of an error
     * @param context The processing context within which to complete subscription queries exceptionally (can be
     *                {@code null}).
     * @return A future completing whenever all matching
     * {@link QueryBus#subscriptionQuery(QueryMessage, ProcessingContext, int) subscription queries} have
     * been completed exceptionally.
     */
    CompletableFuture<Void> completeSubscriptionsExceptionally(Predicate<QueryMessage> filter,
                                                               Throwable cause,
                                                               @Nullable ProcessingContext context);

    /**
     * Completes {@link QueryBus#subscriptionQuery(QueryMessage, ProcessingContext, int) subscription queries} matching
     * the given {@code filter} exceptionally with the given {@code cause}, returning the number of subscription queries
     * that were completed exceptionally.
     * <p>
     * Implementations that cannot determine this number return {@link #UNKNOWN_MATCH_COUNT} instead.
     *
     * @param filter  a predicate filtering on {@link QueryMessage QueryMessages}. Subscription queries matching this
     *                filter will be completed exceptionally
     * @param cause   the cause of an error
     * @param context the processing context within which to complete subscription queries exceptionally (can be
     *                {@code null})
     * @return a future completing with the number of subscription queries that were completed exceptionally, or
     * {@link #UNKNOWN_MATCH_COUNT} if this implementation cannot determine that number
     */
    default CompletableFuture<Integer> completeSubscriptionsExceptionallyAndCount(
            Predicate<QueryMessage> filter,
            Throwable cause,
            @Nullable ProcessingContext context
    ) {
        return completeSubscriptionsExceptionally(filter, cause, context).thenApply(v -> UNKNOWN_MATCH_COUNT);
    }
}
