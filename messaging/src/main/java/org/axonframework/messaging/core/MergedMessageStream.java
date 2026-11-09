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

package org.axonframework.messaging.core;

import org.jspecify.annotations.Nullable;


import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link MessageStream} implementation that merges two underlying message streams into a single stream. Messages
 * from both streams are interleaved based on a provided {@link Comparator}, which determines the order in which
 * messages are consumed.
 * <p>
 * The merged stream is considered completed only when both underlying streams are completed. If either stream has an
 * error, that error is propagated through the merged stream, with errors from the first stream taking precedence.
 * However, when one of the underlying streams propagates an error, this stream can still be consumed from as long as
 * any other underlying streams provide messages. Clients that wish to abort consuming messages when there is an error
 * can {@link #close()} the stream once {@link #error()} returns a non-empty Optional.
 * <p>
 * When a message is consumed via {@link #next()}, the implementation peeks at the head of both streams and uses the
 * comparator to decide which message to consume. This ensures messages are returned in the desired order without
 * requiring either stream to be fully buffered.
 *
 * @param <M> The type of {@link Message} in the stream
 * @author Allard Buijze
 * @since 5.1.0
 */
public class MergedMessageStream<M extends Message> implements MessageStream<M> {

    private final MessageStream<M> first;
    private final MessageStream<M> second;
    private final Comparator<Entry<M>> comparator;

    private final AtomicReference<@Nullable Runnable> callbackRef = new AtomicReference<>();
    private final AtomicBoolean callbackRunning = new AtomicBoolean(false);
    private final AtomicReference<@Nullable Throwable> error = new AtomicReference<>();


    /**
     * Constructs a {@code MergedMessageStream} that merges two message streams based on the given comparator.
     *
     * @param comparator The comparator used to determine the order in which messages from the two streams are
     *                   consumed. A result of {@code <= 0} means the message from the first stream is consumed first.
     * @param first      The first message stream to merge.
     * @param second     The second message stream to merge.
     */
    public MergedMessageStream(Comparator<Entry<M>> comparator,
                               MessageStream<M> first,
                               MessageStream<M> second) {
        this.comparator = Objects.requireNonNull(comparator, "comparator must not be null");
        this.first = Objects.requireNonNull(first, "first must not be null");
        this.second = Objects.requireNonNull(second, "second must not be null");
    }

    @Override
    public Optional<Entry<M>> next() {
        if (error.get() != null) {
            return Optional.empty();
        }
        Optional<Entry<M>> firstPeek = first.peek();
        Optional<Entry<M>> secondPeek = second.peek();
        if (firstPeek.isEmpty() && !second.isCompleted()) {
            return second.next();
        } else if (!first.isCompleted() && firstPeek.isPresent() &&
                (secondPeek.isEmpty() || comparator.compare(firstPeek.get(), secondPeek.get()) <= 0)) {
            return first.next();
        }
        return second.next();
    }

    @Override
    public Optional<Entry<M>> peek() {
        if (error.get() != null) {
            return Optional.empty();
        }
        Optional<Entry<M>> firstPeek = first.peek();
        Optional<Entry<M>> secondPeek = second.peek();
        if (firstPeek.isEmpty() && !second.isCompleted()) {
            return secondPeek;
        } else if (!first.isCompleted() && firstPeek.isPresent() &&
                (secondPeek.isEmpty() || comparator.compare(firstPeek.get(), secondPeek.get()) <= 0)) {
            return firstPeek;
        }
        return secondPeek;
    }

    @Override
    public void setCallback(Runnable callback) {
        callbackRef.set(callback);

        /*
         * The callbacks of the merged streams only pass on the signal: they run on the thread of whoever signalled,
         * where reading from the merged streams is not allowed.
         */

        Runnable forwarding = this::invokeCallbackIfNeeded;

        first.setCallback(forwarding);
        second.setCallback(forwarding);

        if (hasNextAvailable() || isCompleted() || error().isPresent()) {
            invokeCallbackIfNeeded();
        }
    }

    private void invokeCallbackIfNeeded() {
        Runnable callback = callbackRef.get();
        if (callback == null || !callbackRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            callback.run();
        } catch (Throwable e) {
            this.error.set(e);
        } finally {
            callbackRunning.set(false);
        }
    }

    @Override
    public Optional<Throwable> error() {
        return Optional.ofNullable(this.error.get()).or(first::error).or(second::error);
    }

    @Override
    public boolean isCompleted() {
        return error.get() != null || (!hasNextAvailable() && first.isCompleted() && second.isCompleted());
    }

    @Override
    public boolean hasNextAvailable() {
        return error.get() == null && (first.hasNextAvailable() || second.hasNextAvailable());
    }

    @Override
    public void close() {
        first.close();
        second.close();
    }
}
