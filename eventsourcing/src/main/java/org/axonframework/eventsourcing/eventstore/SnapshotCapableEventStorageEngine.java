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
import org.axonframework.eventsourcing.snapshot.api.Snapshot;
import org.axonframework.eventsourcing.snapshot.store.SnapshotStore;
import org.axonframework.messaging.core.DelayedMessageStream;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.StreamingCondition;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Decorator for an {@link EventStorageEngine} that adds support for the {@link SourcingStrategy.Snapshot} sourcing
 * strategy for stores that do not support this strategy natively.
 * <p>
 * When the given {@link SourcingCondition} carries a {@link SourcingStrategy.Snapshot} strategy, this decorator
 * loads the latest snapshot from the given {@link SnapshotStore} and prepends it as a synthetic leading message to
 * the event stream, followed by the events that occurred after the snapshot's position. If no snapshot is found, or
 * if loading fails, it falls back to full event sourcing from the beginning.
 * <p>
 * All other sourcing strategies, as well as all append and streaming operations, are delegated directly to the
 * wrapped engine.
 *
 * @author John Hendrikx
 * @since 5.1.0
 */
@Internal
public class SnapshotCapableEventStorageEngine implements EventStorageEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(SnapshotCapableEventStorageEngine.class);

    private final EventStorageEngine delegate;
    private final SnapshotStore snapshotStore;

    /**
     * Constructs a {@code SnapshotCapableEventStorageEngine} wrapping the given {@code delegate} engine with snapshot
     * loading capability backed by the given {@code snapshotStore}.
     *
     * @param delegate      the {@link EventStorageEngine} to delegate non-snapshot operations to, cannot be
     *                      {@code null}
     * @param snapshotStore the {@link SnapshotStore} used to load snapshots, cannot be {@code null}
     */
    public SnapshotCapableEventStorageEngine(EventStorageEngine delegate, SnapshotStore snapshotStore) {
        this.delegate = Objects.requireNonNull(delegate, "The delegate parameter cannot be null.");
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "The snapshotStore parameter cannot be null.");
    }

    @Override
    public MessageStream<EventMessage> source(SourcingCondition condition) {
        if (condition.sourcingStrategy() instanceof SourcingStrategy.Snapshot s) {
            return DelayedMessageStream.create(
                snapshotStore.load(s.messageType().qualifiedName(), s.identifier())
                    .thenApply(snapshot -> buildStream(snapshot, condition))
                    .exceptionally(e -> {
                        LOGGER.warn("Snapshot loading failed, falling back to full reconstruction for: {} ({})", s.messageType(), s.identifier(), e);

                        return source(Position.START, condition.criteria());
                    })
            );
        }

        return delegate.source(condition);
    }

    private MessageStream<EventMessage> buildStream(@Nullable Snapshot snapshot, SourcingCondition condition) {
        if (snapshot == null) {
            return source(Position.START, condition.criteria());
        }

        return MessageStream.<EventMessage>just(new SnapshotEventMessage(snapshot))
            .concatWith(source(snapshot.position(), condition.criteria()));
    }

    private MessageStream<EventMessage> source(Position position, EventCriteria criteria) {
        return delegate.source(SourcingCondition.conditionFor(position, criteria));
    }

    @Override
    public CompletableFuture<AppendTransaction<?>> appendEvents(AppendCondition condition,
                                                                @Nullable ProcessingContext context,
                                                                List<TaggedEventMessage<?>> events) {
        return delegate.appendEvents(condition, context, events);
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
        descriptor.describeProperty("delegate", delegate);
        descriptor.describeProperty("snapshotStore", snapshotStore);
    }
}
