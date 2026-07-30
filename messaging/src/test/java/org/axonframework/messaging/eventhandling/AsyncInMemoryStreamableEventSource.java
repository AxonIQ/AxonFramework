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

package org.axonframework.messaging.eventhandling;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.axonframework.messaging.core.Context;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.SimpleEntry;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessor;
import org.axonframework.messaging.eventhandling.processing.streaming.token.GlobalSequenceTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventstreaming.StreamableEventSource;
import org.axonframework.messaging.eventstreaming.StreamingCondition;
import org.axonframework.messaging.eventstreaming.Tag;

import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.axonframework.messaging.core.MessageStreamUtils.NO_OP_CALLBACK;

/**
 * An in-memory implementation of {@link StreamableEventSource} designed for testing purposes, particularly with the
 * {@link PooledStreamingEventProcessor}.
 * <p>
 * This implementation provides similar functionality to {@code InMemoryStreamableEventSource} but is adapted to work
 * with the {@link MessageStream} API used by the pooled processor. It supports event publishing, ignored event
 * tracking, and optional callback mechanisms for testing asynchronous event processing scenarios.
 * <p>
 * <strong>WARNING - Destructive Testing Behavior:</strong>
 * <p>
 * This implementation intentionally matches the destructive behavior of the original
 * {@code InMemoryStreamableEventSource} for testing purposes:
 * <ul>
 *   <li><strong>All events are deleted when any stream is closed and the `clearOnClose` property is false</strong> - This provides test isolation
 *       and simulates recovery scenarios where processing continues with fresh events published after errors.</li>
 * </ul>
 * <p>
 * Unlike the original implementation, this version properly respects the trackingToken parameter
 * to start streams at the correct position.
 * <p>
 * This behavior is <strong>NOT suitable for production use</strong> but is essential for testing
 * error recovery scenarios where the processor should handle new events published after an error condition.
 * <p>
 * This implementation doesn't support Tags filtering. If you need it, change the implementation to use TagResolver based on EventMessage payload.
 *
 * @author Mateusz Nowak
 * @since 5.0.0
 */
public class AsyncInMemoryStreamableEventSource implements StreamableEventSource {

    /**
     * An {@link EventMessage#payload()} representing a failed event.
     */
    private static final String FAIL_PAYLOAD = "FAIL";

    /**
     * An {@link EventMessage} representing a failed event.
     */
    public static final EventMessage FAIL_EVENT = EventTestUtils.asEventMessage(FAIL_PAYLOAD);
    private boolean clearOnClose = true;

    private volatile NavigableMap<Long, EventMessage> eventStorage = new ConcurrentSkipListMap<>();
    private final AtomicLong nextIndex = new AtomicLong(0);
    private final boolean streamCallbackSupported;
    private final List<EventMessage> ignoredEvents = new CopyOnWriteArrayList<>();
    private final Set<AsyncMessageStream> openStreams = new CopyOnWriteArraySet<>();
    private Runnable onOpen = () -> {
    };
    private Runnable onClose = () -> {
    };

    /**
     * Construct a default {@link AsyncInMemoryStreamableEventSource}. If stream callbacks should be supported for
     * testing, use {@link AsyncInMemoryStreamableEventSource#AsyncInMemoryStreamableEventSource(boolean, boolean)}.
     */
    public AsyncInMemoryStreamableEventSource() {
        this(false, true);
    }

    /**
     * Construct a {@link AsyncInMemoryStreamableEventSource} toggling {@code streamCallbackSupported}.
     *
     * @param streamCallbackSupported A {@code boolean} dictating whether the {@link StreamableEventSource} should
     *                                support callbacks.
     * @param clearOnClose            When {@code true} clears all message when event source is closed.
     */
    public AsyncInMemoryStreamableEventSource(boolean streamCallbackSupported, boolean clearOnClose) {
        this.streamCallbackSupported = streamCallbackSupported;
        this.clearOnClose = clearOnClose;
    }

    @Override
    public @NonNull MessageStream<EventMessage> open(@NonNull StreamingCondition condition,
                                            @Nullable ProcessingContext context) {
        AsyncMessageStream stream = new AsyncMessageStream(condition);
        openStreams.add(stream);
        onOpen.run();
        return stream;
    }

    @Override
    public @NonNull CompletableFuture<TrackingToken> latestToken(@Nullable ProcessingContext context) {
        return CompletableFuture.completedFuture(
                eventStorage.isEmpty()
                        ? null
                        : new GlobalSequenceTrackingToken(eventStorage.lastKey() + 1)
        );
    }

    @Override
    public @NonNull CompletableFuture<TrackingToken> firstToken(@Nullable ProcessingContext context) {
        return CompletableFuture.completedFuture(new GlobalSequenceTrackingToken(-1));
    }

    @Override
    public @NonNull CompletableFuture<TrackingToken> tokenAt(@NonNull Instant at, @Nullable ProcessingContext context) {
        return eventStorage.entrySet()
                           .stream()
                           .filter(positionToEventEntry -> {
                               EventMessage event = positionToEventEntry.getValue();
                               Instant eventTimestamp = event.timestamp();
                               return eventTimestamp.equals(at) || eventTimestamp.isAfter(at);
                           })
                           .map(Map.Entry::getKey)
                           .min(Comparator.comparingLong(Long::longValue))
                           .map(position -> position - 1)
                           .map(GlobalSequenceTrackingToken::new)
                           .map(tt -> (TrackingToken) tt)
                           .map(CompletableFuture::completedFuture)
                           .orElseGet(() -> firstToken(context));
    }

    /**
     * Publishes the given {@code event} on this {@link StreamableEventSource}. This allows consumers of this message
     * source to receive the event as if it was published through actual channels.
     *
     * @param event The event to publish on this {@link StreamableEventSource}.
     */
    public synchronized void publishMessage(EventMessage event) {
        long position = nextIndex.getAndIncrement();
        eventStorage.put(position, event);

        // Notify all open streams
        openStreams.forEach(AsyncMessageStream::notifyEventAvailable);
    }

    /**
     * Return a {@link List} of {@link EventMessage EventMessages} that have been ignored by the stream consumer.
     *
     * @return A {@link List} of {@link EventMessage EventMessages} that have been ignored by the stream consumer.
     */
    public List<EventMessage> getIgnoredEvents() {
        return Collections.unmodifiableList(ignoredEvents);
    }

    /**
     * Manually toggles the callback that's triggered when new events are present.
     */
    public void runOnAvailableCallback() {
        openStreams.forEach(AsyncMessageStream::runCallback);
    }

    /**
     * Completes every currently open stream as if this source terminated them, without reporting an error and without
     * clearing the published events.
     * <p>
     * A completed stream reports {@link MessageStream#isCompleted()} as {@code true}, an empty
     * {@link MessageStream#error()}, and never returns another event. This mirrors a source that ends an otherwise
     * infinite stream, which {@link MessageStream#close()} cannot emulate here, as closing may clear the published
     * events. Each completed stream's availability callback is invoked, matching the {@code MessageStream} contract
     * that the callback fires on completion.
     */
    public void completeOpenStreams() {
        openStreams.forEach(AsyncMessageStream::complete);
    }

    /**
     * Completes every currently open stream with the given {@code cause}, as if this source failed them, without
     * clearing the published events.
     * <p>
     * Such a stream reports {@link MessageStream#isCompleted()} as {@code true} and the given {@code cause} as its
     * {@link MessageStream#error()}. This mirrors a source that cannot serve a stream at all, such as a backend that is
     * unreachable or does not know the requested context yet. Combined with {@link #setOnOpen(Runnable)} it also models
     * a source that keeps failing every stream opened against it.
     *
     * @param cause the error to complete every currently open stream with
     */
    public void failOpenStreams(Throwable cause) {
        openStreams.forEach(stream -> stream.completeExceptionally(cause));
    }

    /**
     * Set a handler to be called whenever a stream is opened.
     *
     * @param onOpen the handler to call
     */
    public void setOnOpen(Runnable onOpen) {
        this.onOpen = onOpen != null ? onOpen : () -> {
        };
    }

    /**
     * Set a handler to be called whenever a stream is closed.
     *
     * @param onClose the handler to call
     */
    public void setOnClose(Runnable onClose) {
        this.onClose = onClose != null ? onClose : () -> {
        };
    }

    /**
     * Destructively clears all events from this event source. This method is called when any stream is closed to match
     * the original {@code InMemoryStreamableEventSource} behavior.
     * <p>
     * <strong>Warning:</strong> This will delete ALL events from the source, providing test isolation
     * but making this implementation unsuitable for production use.
     */
    private synchronized void clearAllMessages() {
        eventStorage = new ConcurrentSkipListMap<>();
        nextIndex.set(0);
    }

    /**
     * Internal implementation of {@link MessageStream} that provides event streaming functionality.
     */
    private class AsyncMessageStream implements MessageStream<EventMessage> {

        private final AtomicLong currentPosition;
        private final AtomicReference<Runnable> callback = new AtomicReference<>(NO_OP_CALLBACK);
        private final StreamingCondition condition;
        private volatile boolean closed = false;
        private volatile @Nullable Throwable completionError;

        public AsyncMessageStream(StreamingCondition condition) {
            this.condition = condition;

            // Handle null condition defensively (can happen with mocking)
            if (condition == null) {
                this.currentPosition = new AtomicLong(0);
                return;
            }

            // Properly handle tracking token position (unlike the original implementation)
            TrackingToken startToken = condition.position();
            if (startToken == null) {
                // Start from the beginning
                this.currentPosition = new AtomicLong(0);
            } else {
                // Events are published with a token one higher than their storage index, so a token's position is the
                // index of the first event that has not been consumed yet. Matching the InMemoryEventStorageEngine,
                // which opens a stream at condition.position() directly.
                long tokenPosition = startToken.position().orElse(-1);
                this.currentPosition = new AtomicLong(Math.max(0, tokenPosition));
            }
        }

        @Override
        public @NonNull Optional<Entry<EventMessage>> next() {
            if (closed) {
                return Optional.empty();
            }

            while (true) {
                long position = currentPosition.get();
                EventMessage event = eventStorage.get(position);

                if (event == null) {
                    return Optional.empty();
                }

                currentPosition.incrementAndGet();

                Context context = Context.empty();
                context = TrackingToken.addToContext(context, new GlobalSequenceTrackingToken(position + 1));
                context = Tag.addToContext(context, Collections.emptySet());

                if (FAIL_PAYLOAD.equals(event.payload())) {
                    throw new IllegalStateException("Cannot retrieve event at position [" + position + "].");
                }

                if (matches(event, condition)) {
                    return Optional.of(new SimpleEntry<>(event, context));
                } else {
                    ignoredEvents.add(event);
                }
            }
        }

        @Override
        public @NonNull Optional<Entry<EventMessage>> peek() {
            if (closed) {
                return Optional.empty();
            }

            long position = currentPosition.get();
            EventMessage event = eventStorage.get(position);

            if (event == null) {
                return Optional.empty();
            }

            Context context = Context.empty();
            context = TrackingToken.addToContext(context, new GlobalSequenceTrackingToken(position + 1));
            context = Tag.addToContext(context, Collections.emptySet());

            if (FAIL_PAYLOAD.equals(event.payload())) {
                throw new IllegalStateException("Cannot retrieve event at position [" + position + "].");
            }

            if (matches(event, condition)) {
                return Optional.of(new SimpleEntry<>(event, context));
            } else {
                // If the event does not match, we need to look ahead without advancing currentPosition.
                // We must scan forward until we find a matching event or run out of events.
                long nextPos = position + 1;
                while (true) {
                    EventMessage nextEvent = eventStorage.get(nextPos);
                    if (nextEvent == null) {
                        return Optional.empty();
                    }
                    if (FAIL_PAYLOAD.equals(nextEvent.payload())) {
                        throw new IllegalStateException("Cannot retrieve event at position [" + nextPos + "].");
                    }
                    if (matches(nextEvent, condition)) {
                        Context nextContext = Context.empty();
                        nextContext = TrackingToken.addToContext(nextContext,
                                                                 new GlobalSequenceTrackingToken(nextPos + 1));
                        nextContext = Tag.addToContext(nextContext, Collections.emptySet());
                        return Optional.of(new SimpleEntry<>(nextEvent, nextContext));
                    }
                    nextPos++;
                }
            }
        }

        @Override
        public void setCallback(@NonNull Runnable callback) {
            this.callback.set(callback);
            if (streamCallbackSupported && hasNextAvailable()) {
                callback.run();
            }
        }

        @Override
        public @NonNull Optional<Throwable> error() {
            return Optional.ofNullable(completionError);
        }

        @Override
        public boolean isCompleted() {
            return closed;
        }

        @Override
        public boolean hasNextAvailable() {
            if (closed) {
                return false;
            }

            // Check if there's any event from current position onwards
            long position = currentPosition.get();
            return eventStorage.containsKey(position);
        }

        @Override
        public void close() {
            closed = true;
            openStreams.remove(this);

            if (clearOnClose) {
                // DESTRUCTIVE BEHAVIOR: Clear all messages when ANY stream is closed
                // This matches the original InMemoryStreamableEventSource behavior and provides
                // test isolation, allowing recovery scenarios where new events are published after errors
                clearAllMessages();
            }
            onClose.run();
        }

        public void notifyEventAvailable() {
            if (!closed) {
                Runnable currentCallback = callback.get();
                if (currentCallback != null) {
                    currentCallback.run();
                }
            }
        }

        public void runCallback() {
            if (!closed) {
                Runnable currentCallback = callback.get();
                if (currentCallback != null) {
                    currentCallback.run();
                }
            }
        }

        private void completeExceptionally(Throwable cause) {
            if (closed) {
                return;
            }
            completionError = cause;
            complete();
        }

        private void complete() {
            if (closed) {
                return;
            }
            Runnable currentCallback = callback.get();
            closed = true;
            if (currentCallback != null) {
                currentCallback.run();
            }
        }
    }

    /**
     * Checks whether the given event matches the streaming condition. Similar to the match method in
     * InMemoryEventStorageEngine.
     */
    private static boolean matches(EventMessage event, StreamingCondition condition) {
        // Handle null condition (can happen with mocking)
        if (condition == null) {
            return true; // Accept all events if no condition
        }

        // Always let FAIL_EVENT through to trigger the exception
        if (FAIL_PAYLOAD.equals(event.payload())) {
            return true;
        }

        QualifiedName qualifiedName = event.type().qualifiedName();
        Set<Tag> tags = Collections.emptySet(); // Simple events have no tags
        return condition.matches(qualifiedName, tags);
    }
}