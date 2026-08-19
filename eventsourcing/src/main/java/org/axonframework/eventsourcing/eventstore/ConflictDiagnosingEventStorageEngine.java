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

package org.axonframework.eventsourcing.eventstore;

import org.axonframework.common.annotation.Internal;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventstreaming.StreamingCondition;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Decorator for an {@link EventStorageEngine} that, upon an {@link AppendEventsTransactionRejectedException},
 * identifies an event that caused the rejection.
 * <p>
 * It does so by sourcing the same {@link AppendCondition#criteria() criteria} used for the rejected append, starting
 * from the violated {@link AppendCondition#consistencyMarker() consistency marker}, and taking the first event
 * returned. Any event returned by that lookup matches the criteria beyond the marker, which is exactly what an append
 * rejection reports - so the lookup always identifies a genuine conflicting event, regardless of how many criteria
 * the condition combines, and without needing any storage-engine-specific knowledge of how the conflict was detected.
 * Only the first match is fetched, closing the underlying read as soon as it is found, since a single example is
 * enough to confirm and reproduce a conflict.
 * <p>
 * This lookup is an additional read against the wrapped {@code delegate}, performed only after a rejection has
 * already occurred, so it does not add cost to the common, successful append path. It is not enabled by default;
 * wrap an {@link EventStorageEngine} with this decorator to opt into it, for example while reproducing a specific
 * issue.
 * <p>
 * All other operations are delegated to the wrapped engine unchanged.
 *
 * @author John Hendrikx
 * @since 5.3.2
 */
@Internal
public class ConflictDiagnosingEventStorageEngine implements EventStorageEngine {

    private final EventStorageEngine delegate;

    /**
     * Constructs a {@code ConflictDiagnosingEventStorageEngine} wrapping the given {@code delegate}.
     *
     * @param delegate the {@link EventStorageEngine} to delegate to, and to source conflicting events from, cannot
     *                 be {@code null}
     */
    public ConflictDiagnosingEventStorageEngine(EventStorageEngine delegate) {
        this.delegate = Objects.requireNonNull(delegate, "The delegate parameter cannot be null.");
    }

    @Override
    public CompletableFuture<AppendTransaction<?>> appendEvents(AppendCondition condition,
                                                                 @Nullable ProcessingContext context,
                                                                 List<TaggedEventMessage<?>> events) {
        return delegate.appendEvents(condition, context, events)
                       .exceptionallyCompose(failure -> diagnose(failure, condition, context))
                       .thenApply(transaction -> wrap(transaction, condition, context));
    }

    private AppendTransaction<?> wrap(AppendTransaction<?> transaction, AppendCondition condition,
                                       @Nullable ProcessingContext context) {
        return wrapTyped(transaction, condition, context);
    }

    private <R> AppendTransaction<R> wrapTyped(AppendTransaction<R> transaction, AppendCondition condition,
                                               @Nullable ProcessingContext context) {
        return new DiagnosingAppendTransaction<>(transaction, condition, context);
    }

    /**
     * Identifies an event that conflicted with the given {@code condition}, if the given {@code failure} is an
     * {@link AppendEventsTransactionRejectedException}, and re-fails with an enriched copy of it.
     * <p>
     * Best-effort: if the diagnostic lookup itself fails, the original {@code rejection} is re-thrown unenriched
     * rather than letting the lookup's failure mask it. Any other kind of {@code failure} is re-thrown unchanged.
     *
     * @param condition the {@link AppendCondition} of the rejected append, used to source the conflicting events
     * @param context   the {@link ProcessingContext} active during the rejected append, may be {@code null}
     * @param <T>       the value type of the returned, always-failed {@code CompletableFuture}
     * @return a {@code CompletableFuture} that always completes exceptionally
     */
    private <T> CompletableFuture<T> diagnose(Throwable failure, AppendCondition condition,
                                              @Nullable ProcessingContext context) {
        if (!(failure instanceof AppendEventsTransactionRejectedException rejection)) {
            return CompletableFuture.failedFuture(failure);
        }

        SourcingCondition sourcingCondition =
                SourcingCondition.conditionFor(condition.consistencyMarker().position(), condition.criteria());

        return delegate.source(sourcingCondition, context)
                       .filter(entry -> !(entry.message() instanceof TerminalEventMessage))
                       .first()
                       .asCompletableFuture()
                       .handle((entry, sourcingError) -> sourcingError == null
                                ? rejection.withConflictingEvent(entry == null ? null : entry.message())
                                : rejection)
                       .thenCompose(enriched -> CompletableFuture.<T>failedFuture(enriched));
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
    }

    private final class DiagnosingAppendTransaction<R> implements AppendTransaction<R> {

        private final AppendTransaction<R> delegate;
        private final AppendCondition condition;
        private final ProcessingContext context;

        private DiagnosingAppendTransaction(AppendTransaction<R> delegate, AppendCondition condition,
                                             @Nullable ProcessingContext context) {
            this.delegate = delegate;
            this.condition = condition;
            this.context = context;
        }

        @Override
        public CompletableFuture<R> commit() {
            return delegate.commit()
                            .exceptionallyCompose(failure -> diagnose(failure, condition, context));
        }

        @Override
        public void rollback() {
            delegate.rollback();
        }

        @Override
        public CompletableFuture<ConsistencyMarker> afterCommit(R commitResult) {
            return delegate.afterCommit(commitResult);
        }
    }
}
