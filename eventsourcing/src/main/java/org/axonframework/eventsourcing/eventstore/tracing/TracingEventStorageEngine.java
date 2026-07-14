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

package org.axonframework.eventsourcing.eventstore.tracing;

import org.axonframework.common.annotation.Internal;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.eventsourcing.eventstore.AppendCondition;
import org.axonframework.eventsourcing.eventstore.ConsistencyMarker;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.SourcingCondition;
import org.axonframework.eventsourcing.eventstore.TaggedEventMessage;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventstreaming.StreamingCondition;
import org.axonframework.messaging.tracing.SpanFactory;
import org.axonframework.messaging.tracing.SpanScope;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Tracing decorator for an {@link EventStorageEngine}. It traces the complete logical append operation, beginning
 * when {@link EventStorageEngine#appendEvents(AppendCondition, ProcessingContext, List)} is invoked and ending when
 * the returned {@link AppendTransaction} reaches a terminal outcome through
 * {@link AppendTransaction#afterCommit(Object)}, {@link AppendTransaction#rollback()}, or a failure.
 * <p>
 * When a {@link ProcessingContext} is supplied, the span additionally ends with the context as a leak backstop, so an
 * abandoned transaction cannot leave it open. Without a context there is no lifecycle to attach that backstop to: the
 * span then ends only through the transaction's terminal operations, and a caller that abandons such a transaction
 * without committing or rolling back leaves the span unfinished.
 * <p>
 * Read and token operations are delegated unchanged. The decorator is installed by the event-sourcing tracing
 * configuration and is not intended for direct application use.
 *
 * @author Mateusz Nowak
 * @since 5.3.0
 */
@Internal
public final class TracingEventStorageEngine implements EventStorageEngine {

    /** Name of the span covering a complete append transaction. */
    public static final String APPEND_TRANSACTION_SPAN = "EventStorageEngine.appendTransaction";

    private final EventStorageEngine delegate;
    private final SpanFactory spanFactory;

    /**
     * Initializes a tracing decorator for the given {@code delegate}.
     *
     * @param delegate    the event storage engine to delegate to
     * @param spanFactory the factory producing the append span
     */
    public TracingEventStorageEngine(EventStorageEngine delegate, SpanFactory spanFactory) {
        this.delegate = Objects.requireNonNull(delegate, "delegate may not be null");
        this.spanFactory = Objects.requireNonNull(spanFactory, "spanFactory may not be null");
    }

    @Override
    public CompletableFuture<AppendTransaction<?>> appendEvents(AppendCondition condition,
                                                                 @Nullable ProcessingContext context,
                                                                 List<TaggedEventMessage<?>> events) {
        SpanScope scope = spanFactory.createInternalSpan(APPEND_TRANSACTION_SPAN, context).start();
        if (context != null) {
            context.doFinally(processingContext -> scope.close());
        }
        ProcessingContext scopedContext = context == null ? null : SpanScope.addToContext(context, scope);

        CompletableFuture<AppendTransaction<?>> result;
        try {
            result = Objects.requireNonNull(
                    scope.within(() -> delegate.appendEvents(condition, scopedContext, events)),
                    "The EventStorageEngine returned a null CompletableFuture."
            );
        } catch (Throwable error) {
            fail(scope, error);
            throw error;
        }

        result.whenComplete((transaction, error) -> {
            if (error != null) {
                fail(scope, error);
            }
        });
        return result.<AppendTransaction<?>>thenApply(transaction -> {
            try {
                return tracingTransaction(transaction, scope);
            } catch (Throwable error) {
                fail(scope, error);
                throw error;
            }
        });
    }

    private static AppendTransaction<?> tracingTransaction(AppendTransaction<?> transaction, SpanScope scope) {
        return tracingTransactionTyped(transaction, scope);
    }

    private static <R> AppendTransaction<R> tracingTransactionTyped(AppendTransaction<R> transaction,
                                                                    SpanScope scope) {
        return new TracingAppendTransaction<>(
                Objects.requireNonNull(transaction, "transaction may not be null"), scope);
    }

    private static void fail(SpanScope scope, Throwable error) {
        scope.span().recordException(error);
        scope.close();
    }

    @Override
    public MessageStream<EventMessage> source(SourcingCondition condition, @Nullable ProcessingContext context) {
        return delegate.source(condition, context);
    }

    @Override
    public MessageStream<EventMessage> stream(StreamingCondition condition) {
        return delegate.stream(condition);
    }

    @Override
    public CompletableFuture<TrackingToken> firstToken() {
        return delegate.firstToken();
    }

    @Override
    public CompletableFuture<TrackingToken> latestToken() {
        return delegate.latestToken();
    }

    @Override
    public CompletableFuture<TrackingToken> tokenAt(Instant at) {
        return delegate.tokenAt(at);
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeWrapperOf(delegate);
        descriptor.describeProperty("spanFactory", spanFactory);
    }

    private static final class TracingAppendTransaction<R> implements AppendTransaction<R> {

        private final AppendTransaction<R> delegate;
        private final SpanScope scope;

        private TracingAppendTransaction(AppendTransaction<R> delegate, SpanScope scope) {
            this.delegate = delegate;
            this.scope = scope;
        }

        @Override
        public CompletableFuture<R> commit() {
            CompletableFuture<R> result = invoke(delegate::commit);
            return result.whenComplete((commitResult, error) -> {
                if (error != null) {
                    fail(scope, error);
                }
            });
        }

        @Override
        public void rollback() {
            try {
                scope.within(delegate::rollback);
            } catch (Throwable error) {
                scope.span().recordException(error);
                throw error;
            } finally {
                scope.close();
            }
        }

        @Override
        public CompletableFuture<ConsistencyMarker> afterCommit(R commitResult) {
            CompletableFuture<ConsistencyMarker> result = invoke(() -> delegate.afterCommit(commitResult));
            return result.whenComplete((position, error) -> {
                if (error != null) {
                    scope.span().recordException(error);
                }
                scope.close();
            });
        }

        private <T> CompletableFuture<T> invoke(Supplier<CompletableFuture<T>> operation) {
            try {
                return Objects.requireNonNull(
                        scope.within(operation),
                        "The append transaction returned a null CompletableFuture."
                );
            } catch (Throwable error) {
                fail(scope, error);
                throw error;
            }
        }
    }
}
