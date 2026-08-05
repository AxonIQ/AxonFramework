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

import org.axonframework.common.FutureUtils;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.core.Context;
import org.axonframework.messaging.core.Context.ResourceKey;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.QueueMessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Implementation of the {@code QueryBus} that dispatches queries (through
 * {@link #query(QueryMessage, ProcessingContext)} or {@link #subscriptionQuery(QueryMessage, ProcessingContext, int)})
 * to the {@link QueryHandler QueryHandlers} subscribed to that specific query's {@link QualifiedName name} and
 * {@link QualifiedName response type} combination.
 * <p>
 * Allows fine-grained control over
 * {@link #subscriptionQuery(QueryMessage, ProcessingContext, int) subscription queries} through
 * {@link #subscribeToUpdates(QueryMessage, int)}, {@link #emitUpdate(Predicate, Supplier, ProcessingContext)},
 * {@link #completeSubscriptions(Predicate, ProcessingContext)}, and
 * {@link #completeSubscriptionsExceptionally(Predicate, Throwable, ProcessingContext)}.
 * <p>
 * Furthermore, it is in charge of invoking the {@link #subscribe(QualifiedName, QueryHandler)}  subscribed}
 * {@link QueryHandler query handlers} when a query is being dispatched.
 *
 * @author Marc Gathier
 * @author Allard Buijze
 * @author Steven van Beelen
 * @author Milan Savic
 * @since 3.1.0
 */
public class SimpleQueryBus implements QueryBus {

    private static final Logger logger = LoggerFactory.getLogger(SimpleQueryBus.class);

    private static final ResourceKey<List<Runnable>> UPDATE_TASKS_KEY = ResourceKey.withLabel("update-tasks");

    private final UnitOfWorkFactory unitOfWorkFactory;
    private final ConcurrentMap<QualifiedName, QueryHandler> subscriptions = new ConcurrentHashMap<>();
    private final ConcurrentMap<QueryMessage, QueueMessageStream<SubscriptionQueryUpdateMessage>> updateHandlers =
            new ConcurrentHashMap<>();

    /**
     * Construct a {@code SimpleQueryBus} with the given {@code unitOfWorkFactory} and {@code queryUpdateEmitter}.
     *
     * @param unitOfWorkFactory The factory constructing {@link UnitOfWork units of work} to dispatch and handle queries
     *                          in.
     */
    public SimpleQueryBus(UnitOfWorkFactory unitOfWorkFactory) {
        this.unitOfWorkFactory = Objects.requireNonNull(unitOfWorkFactory, "The UnitOfWorkFactory must be provided.");
    }

    @Override
    public QueryBus subscribe(QualifiedName queryName, QueryHandler queryHandler) {
        logger.debug("Subscribing query handler for name [{}].", queryName);
        QueryHandler existingHandler = subscriptions.putIfAbsent(queryName, queryHandler);
        if (existingHandler != null && existingHandler != queryHandler) {
            throw new DuplicateQueryHandlerSubscriptionException(queryName, existingHandler, queryHandler);
        }
        return this;
    }

    @Override
    public MessageStream<QueryResponseMessage> query(QueryMessage query, @Nullable ProcessingContext context) {
        if (logger.isDebugEnabled()) {
            logger.debug("Dispatching direct-query for query name [{}].",
                         query.type().name());
        }
        try {
            MessageStream<QueryResponseMessage> responseStream = handle(query, handlerFor(query)).get();
            return containsResponseOrUserException(responseStream)
                    ? responseStream
                    : MessageStream.empty().cast();
        } catch (Exception e) {
            return MessageStream.failed(e);
        }
    }

    private CompletableFuture<MessageStream<QueryResponseMessage>> handle(QueryMessage query,
                                                                          QueryHandler handler) {
        if (logger.isDebugEnabled()) {
            logger.debug("Handling query [{} {name={}]",
                         query.identifier(), query.type());
        }

        UnitOfWork unitOfWork = unitOfWorkFactory.create();
        return unitOfWork.executeWithResult(
                context -> {
                    MessageStream<QueryResponseMessage> result;
                    try {
                        result = handler.handle(query, context);
                    } catch (Exception e) {
                        result = MessageStream.failed(e);
                    }
                    return CompletableFuture.completedFuture(result);
                }
        );
    }

    /**
     * Validates whether the given {@code responseStream} is <b>not</b> completed or has an exception thrown by the
     * user's {@link QueryHandler}.
     * <p>
     * If it has not completed yet, we can assume responses will be returned, making it a valuable response. If it has
     * an exception that has been (consciously) thrown by the user, they should know about it, making it a valuable
     * response.
     *
     * @param responseStream The response stream to check whether it is not completed or had an exception.
     * @return {@code true} when the given {@code responseStream} is <b>not</b> completed or has an
     * {@link MessageStream#error() error} (consciously) thrown by the user, {@code false} otherwise.
     */
    private static boolean containsResponseOrUserException(MessageStream<QueryResponseMessage> responseStream) {
        return !responseStream.isCompleted()
                || responseStream.error()
                                 .map(e -> !(e instanceof NoHandlerForQueryException))
                                 .orElse(false);
    }

    @Override
    public MessageStream<QueryResponseMessage> subscriptionQuery(QueryMessage query,
                                                                 @Nullable ProcessingContext context,
                                                                 int updateBufferSize) {
        MessageStream<SubscriptionQueryUpdateMessage> updates = subscribeToUpdates(query, updateBufferSize);
        MessageStream<QueryResponseMessage> initialResult = query(query, context);

        return initialResult.concatWith(updates.cast());
    }

    @Override
    public MessageStream<SubscriptionQueryUpdateMessage> subscribeToUpdates(QueryMessage query,
                                                                            int updateBufferSize) {
        if (hasHandlerFor(query.identifier())) {
            throw new SubscriptionQueryAlreadyRegisteredException(query.identifier());
        }
        QueueMessageStream<SubscriptionQueryUpdateMessage> output = new QueueMessageStream<>(new ArrayBlockingQueue<>(
                updateBufferSize));
        QueueMessageStream<SubscriptionQueryUpdateMessage> previous = updateHandlers.put(query, output);
        if (previous != null) {
            previous.close();
        }

        return output.onClose(() -> updateHandlers.remove(query, output));
    }

    private boolean hasHandlerFor(String queryId) {
        return updateHandlers.keySet().stream().anyMatch(m -> m.identifier().equals(queryId));
    }

    private QueryHandler handlerFor(QueryMessage query) {
        QualifiedName handlerName = query.type().qualifiedName();
        if (!subscriptions.containsKey(handlerName)) {
            throw NoHandlerForQueryException.forBus(query);
        }
        return subscriptions.get(handlerName);
    }

    @Override
    public CompletableFuture<Void> emitUpdate(Predicate<QueryMessage> filter,
                                              Supplier<SubscriptionQueryUpdateMessage> updateSupplier,
                                              @Nullable ProcessingContext context) {
        return emitUpdateAndCount(filter, updateSupplier, context).thenApply(FutureUtils::ignoreResult);
    }

    @Override
    public CompletableFuture<OptionalInt> emitUpdateAndCount(Predicate<QueryMessage> filter,
                                                             Supplier<SubscriptionQueryUpdateMessage> updateSupplier,
                                                             @Nullable ProcessingContext context) {
        return runAfterCommitOrImmediately(context, filter, () -> emitUpdate(filter, updateSupplier));
    }

    private int emitUpdate(Predicate<QueryMessage> filter,
                           Supplier<SubscriptionQueryUpdateMessage> updateSupplier) {
        int deliveredCount = 0;
        Map<QueryMessage, QueueMessageStream<SubscriptionQueryUpdateMessage>> matchingHandlers =
                updateHandlers.entrySet()
                              .stream()
                              .filter(entry -> filter.test(entry.getKey()))
                              .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        if (matchingHandlers.isEmpty()) {
            return deliveredCount;
        }

        SubscriptionQueryUpdateMessage update = updateSupplier.get();
        for (Map.Entry<QueryMessage, QueueMessageStream<SubscriptionQueryUpdateMessage>> entry
                : matchingHandlers.entrySet()) {
            QueryMessage query = entry.getKey();
            QueueMessageStream<SubscriptionQueryUpdateMessage> updateHandler = entry.getValue();
            try {
                if (updateHandler.offer(update, Context.empty())) {
                    deliveredCount++;
                } else {
                    updateHandler.sealExceptionally(new QueryExecutionException(
                            "Subscription update buffer overflow", null
                    ));
                    updateHandlers.remove(query, updateHandler);
                }
            } catch (Exception e) {
                logger.info("An error occurred while trying to emit an update to a query '{}'. " +
                                    "The subscription will be cancelled. Exception summary: {}",
                            query.type(), e.toString());
                updateHandler.sealExceptionally(e);
                updateHandlers.remove(query, updateHandler);
            }
        }
        return deliveredCount;
    }

    @Override
    public CompletableFuture<Void> completeSubscriptions(Predicate<QueryMessage> filter,
                                                         @Nullable ProcessingContext context) {
        return completeSubscriptionsAndCount(filter, context).thenApply(FutureUtils::ignoreResult);
    }

    @Override
    public CompletableFuture<OptionalInt> completeSubscriptionsAndCount(Predicate<QueryMessage> filter,
                                                                        @Nullable ProcessingContext context) {
        return runAfterCommitOrImmediately(context, filter, () -> completeSubscriptions(filter));
    }

    private int completeSubscriptions(Predicate<QueryMessage> filter) {
        List<Map.Entry<QueryMessage, QueueMessageStream<SubscriptionQueryUpdateMessage>>> matchingHandlers =
                updateHandlers.entrySet()
                              .stream()
                              .filter(entry -> filter.test(entry.getKey()))
                              .toList();

        int completedCount = 0;
        for (Map.Entry<QueryMessage, QueueMessageStream<SubscriptionQueryUpdateMessage>> entry : matchingHandlers) {
            QueueMessageStream<SubscriptionQueryUpdateMessage> updateHandler = entry.getValue();
            try {
                updateHandler.seal();
                completedCount++;
            } catch (Exception e) {
                updateHandler.sealExceptionally(e);
            }
            updateHandlers.remove(entry.getKey(), updateHandler);
        }

        return completedCount;
    }

    @Override
    public CompletableFuture<Void> completeSubscriptionsExceptionally(
            Predicate<QueryMessage> filter,
            Throwable cause,
            @Nullable ProcessingContext context
    ) {
        return completeSubscriptionsExceptionallyAndCount(filter, cause, context).thenApply(FutureUtils::ignoreResult);
    }

    @Override
    public CompletableFuture<OptionalInt> completeSubscriptionsExceptionallyAndCount(
            Predicate<QueryMessage> filter,
            Throwable cause,
            @Nullable ProcessingContext context
    ) {
        return runAfterCommitOrImmediately(context, filter, () -> completeSubscriptionsExceptionally(filter, cause));
    }

    private int completeSubscriptionsExceptionally(Predicate<QueryMessage> filter, Throwable cause) {
        List<Map.Entry<QueryMessage, QueueMessageStream<SubscriptionQueryUpdateMessage>>> matchingHandlers =
                updateHandlers.entrySet()
                              .stream()
                              .filter(entry -> filter.test(entry.getKey()))
                              .toList();

        int completedCount = 0;
        for (Map.Entry<QueryMessage, QueueMessageStream<SubscriptionQueryUpdateMessage>> entry : matchingHandlers) {
            if (emitError(entry.getValue(), cause, entry.getKey())) {
                completedCount++;
            }
        }

        return completedCount;
    }

    /**
     * Runs the given {@code updateTask} immediately, or defers it until the given {@code context} commits,
     * matching {@code updateTask}'s subscriptions to the given {@code filter}.
     * <p>
     * When run immediately, the returned count reflects the actual outcome of the {@code updateTask} - e.g. the
     * number of subscribers an update was successfully delivered to, as opposed to the number of subscribers merely
     * matching the {@code filter} before delivery was attempted.
     * <p>
     * When deferred to after commit, the {@code updateTask} itself runs later and its outcome is not available to
     * this method. In that case, the returned count is a match count against the given {@code filter}, computed at
     * call time - not the outcome of the eventual {@code updateTask} run - since waiting for that outcome would
     * require blocking until after the given {@code context} commits, which could deadlock a caller invoking this
     * from within that same commit pipeline.
     * <p>
     * The match count is only computed when the {@code updateTask} will actually run (now or after commit) - not
     * for an already-errored {@code context}, since the update is silently dropped in that case and the
     * {@code filter} must not observe any side effects.
     */
    private CompletableFuture<OptionalInt> runAfterCommitOrImmediately(@Nullable ProcessingContext context,
                                                                       Predicate<QueryMessage> filter,
                                                                       IntSupplier updateTask) {
        if (context == null || context.isCommitted()) {
            return CompletableFuture.completedFuture(OptionalInt.of(updateTask.getAsInt()));
        } else if (!context.isCompleted()) {
            int matchCount = matchCount(filter);
            context.computeResourceIfAbsent(
                           UPDATE_TASKS_KEY,
                           () -> {
                               List<Runnable> subscriptionQueryTasks = new ArrayList<>();
                               context.runOnAfterCommit(c -> subscriptionQueryTasks.forEach(Runnable::run));
                               return subscriptionQueryTasks;
                           }
                   )
                   .add(updateTask::getAsInt);
            return CompletableFuture.completedFuture(OptionalInt.of(matchCount));
        }
        // else: context completed with error - drop the update
        return CompletableFuture.completedFuture(OptionalInt.empty());
    }

    private int matchCount(Predicate<QueryMessage> filter) {
        return (int) updateHandlers.keySet()
                                   .stream()
                                   .filter(filter)
                                   .count();
    }

    private boolean emitError(QueueMessageStream<SubscriptionQueryUpdateMessage> updateHandler,
                              Throwable cause,
                              QueryMessage query) {
        try {
            updateHandler.sealExceptionally(cause);
            return true;
        } catch (Exception e) {
            logger.error("An error happened while trying to inform an update handler about the error. Query: {}",
                         query);
            return false;
        }
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeProperty("unitOfWorkFactory", unitOfWorkFactory);
        descriptor.describeProperty("subscriptions", subscriptions);
        descriptor.describeProperty("updateHandlers", updateHandlers);
    }
}
