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

package org.axonframework.eventsourcing.eventstore.inmemory;

import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.eventsourcing.eventstore.AppendCondition;
import org.axonframework.eventsourcing.eventstore.ConsistencyMarker;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.GlobalIndexConsistencyMarker;
import org.axonframework.eventsourcing.eventstore.GlobalIndexPosition;
import org.axonframework.eventsourcing.eventstore.Position;
import org.axonframework.eventsourcing.eventstore.SourcingCondition;
import org.axonframework.eventsourcing.eventstore.SourcingStrategy;
import org.axonframework.eventsourcing.eventstore.TaggedEventMessage;
import org.axonframework.eventsourcing.eventstore.TerminalEventMessage;
import org.axonframework.messaging.core.Context;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.SimpleEntry;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.processing.streaming.token.GlobalSequenceTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventstreaming.EventsCondition;
import org.axonframework.messaging.eventstreaming.StreamingCondition;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static org.axonframework.eventsourcing.eventstore.AppendEventsTransactionRejectedException.conflictingEventsDetected;
import static org.axonframework.messaging.core.MessageStreamUtils.NO_OP_CALLBACK;

/**
 * Thread-safe {@link EventStorageEngine} implementation storing events in memory.
 *
 * @author Allard Buijze
 * @author Rene de Waele
 * @author Milan Savić
 * @author Steven van Beelen
 * @since 3.0.0
 */
public class InMemoryEventStorageEngine implements EventStorageEngine {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private static final boolean WITHOUT_MARKER = false;

    private final NavigableMap<Long, TaggedEventMessage<? extends EventMessage>> eventStorage =
            new ConcurrentSkipListMap<>();
    private final long offset;
    private final ReentrantLock appendLock = new ReentrantLock();
    private final Set<MapBackedMessageStream> openStreams = new CopyOnWriteArraySet<>();

    /**
     * Initializes an in-memory {@link EventStorageEngine}.
     * <p>
     * The engine will be empty, and there is no offset for the first token.
     */
    public InMemoryEventStorageEngine() {
        this(0L);
    }

    /**
     * Initializes an in-memory {@link EventStorageEngine} using given {@code offset} to initialize the tokens.
     *
     * @param offset The value to use for the token of the first event appended.
     */
    public InMemoryEventStorageEngine(long offset) {
        this.offset = offset;
    }

    @Override
    public CompletableFuture<AppendTransaction<?>> appendEvents(AppendCondition condition,
                                                                @Nullable ProcessingContext processingContext,
                                                                List<TaggedEventMessage<?>> events) {
        if (containsConflicts(condition)) {
            // early failure, since we know conflicts already exist at insert-time
            return CompletableFuture.failedFuture(conflictingEventsDetected(condition.consistencyMarker()));
        }

        return CompletableFuture.completedFuture(new AppendTransaction<ConsistencyMarker>() {

            private final AtomicBoolean finished = new AtomicBoolean(false);

            @Override
            public CompletableFuture<ConsistencyMarker> commit() {
                if (finished.getAndSet(true)) {
                    return CompletableFuture.failedFuture(new IllegalStateException("Already committed or rolled back"));
                }

                appendLock.lock();
                try {
                    if (containsConflicts(condition)) {
                        return CompletableFuture.failedFuture(conflictingEventsDetected(condition.consistencyMarker()));
                    }
                    ConsistencyMarker newLatest =
                            events.stream()
                                  .map(event -> {
                                      long next = nextIndex();
                                      long marker = next + 1;
                                      eventStorage.put(next, event);

                                      if (logger.isDebugEnabled()) {
                                          logger.debug("Appended event [{}] with position [{}] and timestamp [{}].",
                                                       event.event().identifier(),
                                                       next,
                                                       event.event().timestamp());
                                      }
                                      return (ConsistencyMarker) new GlobalIndexConsistencyMarker(marker);
                                  })
                                  .reduce(ConsistencyMarker::upperBound)
                                  .orElse(ConsistencyMarker.ORIGIN);

                    openStreams.forEach(m -> m.callback().run());
                    return CompletableFuture.completedFuture(newLatest);
                } finally {
                    appendLock.unlock();
                }
            }

            @Override
            public CompletableFuture<ConsistencyMarker> afterCommit(ConsistencyMarker marker) {
                return CompletableFuture.completedFuture(marker);
            }

            @Override
            public void rollback() {
                finished.set(true);
            }
        });
    }

    private long nextIndex() {
        return eventStorage.isEmpty() ? 0 : eventStorage.lastKey() + 1;
    }

    private boolean containsConflicts(AppendCondition condition) {
        if (Objects.equals(condition.consistencyMarker(), ConsistencyMarker.INFINITY)) {
            return WITHOUT_MARKER;
        }

        return this.eventStorage.tailMap(GlobalIndexConsistencyMarker.position(condition.consistencyMarker()))
                                .values()
                                .stream()
                                .map(event -> (TaggedEventMessage<?>) event)
                                .anyMatch(taggedEvent -> condition.matches(
                                        taggedEvent.event().type().qualifiedName(), taggedEvent.tags()
                                ));
    }

    @Override
    public MessageStream<EventMessage> source(SourcingCondition condition) {
        if (logger.isDebugEnabled()) {
            logger.debug("Start sourcing events with condition [{}].", condition);
        }

        // Get start position and ensure it is within valid bounds for this implementation:
        long start = switch (condition.sourcingStrategy()) {
            case SourcingStrategy.Absolute(Position position) -> Math.max(0, GlobalIndexPosition.toIndex(position));
            default -> throw new UnsupportedOperationException("Unsupported sourcing strategy: " + condition.sourcingStrategy());
        };

        // Set end to the CURRENT last position, to reflect it's a finite stream.
        MapBackedMessageStream messageStream = new MapBackedSourcingEventMessageStream(
                start, eventStorage.isEmpty() ? -1 : eventStorage.lastKey(), condition
        );
        openStreams.add(messageStream);
        return messageStream;
    }

    @Override
    public MessageStream<EventMessage> stream(StreamingCondition condition) {
        if (logger.isDebugEnabled()) {
            logger.debug("Start streaming events with condition [{}].", condition);
        }

        // Set end to the Long.MAX-VALUE, to reflect it's an infinite stream.
        MapBackedMessageStream messageStream =
                new MapBackedStreamingEventMessageStream(condition.position().position().orElse(-1), condition);
        openStreams.add(messageStream);
        return messageStream;
    }

    private static boolean match(TaggedEventMessage<?> taggedEvent, EventsCondition condition) {
        QualifiedName qualifiedName = taggedEvent.event().type().qualifiedName();
        return condition.matches(qualifiedName, taggedEvent.tags());
    }

    @Override
    public CompletableFuture<TrackingToken> firstToken() {
        if (logger.isDebugEnabled()) {
            logger.debug("Operation firstToken() is invoked.");
        }

        return CompletableFuture.completedFuture(
                eventStorage.isEmpty()
                        ? new GlobalSequenceTrackingToken(offset - 1)
                        : new GlobalSequenceTrackingToken(eventStorage.firstKey())
        );
    }

    @Override
    public CompletableFuture<TrackingToken> latestToken() {
        if (logger.isDebugEnabled()) {
            logger.debug("Operation latestToken() is invoked.");
        }

        return CompletableFuture.completedFuture(
                eventStorage.isEmpty()
                        ? new GlobalSequenceTrackingToken(offset - 1)
                        : new GlobalSequenceTrackingToken(eventStorage.lastKey() + 1)
        );
    }

    @Override
    public CompletableFuture<TrackingToken> tokenAt(Instant at) {
        if (logger.isDebugEnabled()) {
            logger.debug("Operation tokenAt() is invoked with Instant [{}].", at);
        }

        return eventStorage.entrySet()
                           .stream()
                           .filter(positionToEventEntry -> {
                               EventMessage event = positionToEventEntry.getValue().event();
                               Instant eventTimestamp = event.timestamp();
                               return eventTimestamp.equals(at) || eventTimestamp.isAfter(at);
                           })
                           .map(Map.Entry::getKey)
                           .min(Comparator.comparingLong(Long::longValue))
                           .map(position -> position - 1)
                           .map(GlobalSequenceTrackingToken::new)
                           .map(tt -> (TrackingToken) tt)
                           .map(CompletableFuture::completedFuture)
                           .orElseGet(this::latestToken);
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeProperty("offset", offset);
    }

    private abstract class MapBackedMessageStream implements MessageStream<EventMessage> {

        private final AtomicLong position;
        protected final long end;
        private final EventsCondition condition;
        private final AtomicReference<Runnable> callback;

        private MapBackedMessageStream(long start,
                                       long end,
                                       EventsCondition condition) {
            this.position = new AtomicLong(start);
            this.end = end;
            this.condition = condition;
            this.callback = new AtomicReference<>(NO_OP_CALLBACK);
        }

        @Override
        public Optional<Entry<EventMessage>> next() {
            long currentPosition = this.position.get();
            long lookupPosition = Math.max(0, currentPosition);
            while (lookupPosition <= this.end
                    && eventStorage.containsKey(lookupPosition)
                    && this.position.compareAndSet(currentPosition, lookupPosition + 1)) {
                TaggedEventMessage<?> nextEvent = eventStorage.get(lookupPosition);
                if (match(nextEvent, this.condition)) {
                    Context context = Context.empty();
                    context = TrackingToken.addToContext(context, new GlobalSequenceTrackingToken(lookupPosition + 1));
                    return Optional.of(new SimpleEntry<>(nextEvent.event(), context));
                }
                currentPosition = this.position.get();
                lookupPosition = Math.max(0, currentPosition);
            }
            return lastEntry();
        }

        @Override
        public Optional<Entry<EventMessage>> peek() {
            long currentPosition = Math.max(0, this.position.get());
            while (currentPosition <= this.end && eventStorage.containsKey(currentPosition)) {
                TaggedEventMessage<?> nextEvent = eventStorage.get(currentPosition);
                if (match(nextEvent, this.condition)) {
                    Context context = Context.empty();
                    context = TrackingToken.addToContext(context, new GlobalSequenceTrackingToken(currentPosition + 1));
                    return Optional.of(new SimpleEntry<>(nextEvent.event(), context));
                }
                currentPosition++;
            }
            return lastEntry();
        }

        abstract Optional<Entry<EventMessage>> lastEntry();

        @Override
        public void setCallback(Runnable callback) {
            this.callback.set(callback);
            if (isCompleted() || hasNextAvailable()) {
                callback.run();
            }
        }

        @Override
        public Optional<Throwable> error() {
            return Optional.empty();
        }

        @Override
        public boolean isCompleted() {
            long currentPosition = this.position.get();
            return currentPosition > this.end;
        }

        @Override
        public boolean hasNextAvailable() {
            long currentPosition = Math.max(0, this.position.get());
            return currentPosition <= this.end && eventStorage.containsKey(currentPosition);
        }

        @Override
        public void close() {
            this.position.set(this.end + 1);
        }

        Runnable callback() {
            return this.callback.get();
        }
    }

    private class MapBackedSourcingEventMessageStream extends MapBackedMessageStream {

        private final AtomicBoolean sharedLastEntry = new AtomicBoolean(false);

        private MapBackedSourcingEventMessageStream(long start,
                                                    long end,
                                                    EventsCondition condition) {
            super(start, end, condition);
        }

        @Override
        Optional<Entry<EventMessage>> lastEntry() {
            if (sharedLastEntry.compareAndSet(false, true)) {
                Context context = Context.with(ConsistencyMarker.RESOURCE_KEY, new GlobalIndexConsistencyMarker(end + 1));
                return Optional.of(new SimpleEntry<>(TerminalEventMessage.INSTANCE, context));
            } else {
                return Optional.empty();
            }
        }

        @Override
        public boolean isCompleted() {
            return super.isCompleted() && sharedLastEntry.get();
        }

        @Override
        public boolean hasNextAvailable() {
            return super.hasNextAvailable() || !sharedLastEntry.get();
        }
    }

    private class MapBackedStreamingEventMessageStream extends MapBackedMessageStream {

        private MapBackedStreamingEventMessageStream(long start,
                                                     EventsCondition condition) {
            super(start, Long.MAX_VALUE, condition);
        }

        @Override
        Optional<Entry<EventMessage>> lastEntry() {
            return Optional.empty();
        }
    }
}
