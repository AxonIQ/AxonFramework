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

import org.axonframework.common.annotation.Internal;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * A {@link MessageStream} that defers creation of its delegate stream until the first message is requested.
 * <p>
 * The supplier is invoked at most once. Cancellation (via {@link #close()}) before any message request is a no-op: the
 * supplier is never called and the stream transitions to the completed-empty state.
 *
 * @param <M> the type of {@link Message} in the stream
 * @author Allard Buijze
 * @see MessageStream#concatWith(Supplier)
 * @since 5.2.0
 */
@Internal
public class LazyMessageStream<M extends Message> implements MessageStream<M> {

    private final Supplier<MessageStream<M>> supplier;
    private final AtomicReference<@Nullable MessageStream<M>> delegate = new AtomicReference<>();

    /**
     * Constructs a {@code LazyMessageStream} backed by the given {@code supplier}.
     *
     * @param supplier the supplier to invoke on first message request; must not be {@code null}
     */
    public LazyMessageStream(Supplier<MessageStream<M>> supplier) {
        this.supplier = requireNonNull(supplier, "supplier must not be null");
    }

    private MessageStream<M> initializeIfAbsent() {
        MessageStream<M> current = delegate.get();
        if (current != null) return current;
        synchronized (this) {
            current = delegate.get();
            if (current != null) return current;
            try {
                current = Objects.requireNonNullElse(supplier.get(), MessageStream.empty().cast());
            } catch (RuntimeException e) {
                current = MessageStream.failed(e);
            }
            delegate.set(current);
            return current;
        }
    }

    @Override
    public Optional<Entry<M>> next() {
        return initializeIfAbsent().next();
    }

    @Override
    public Optional<Entry<M>> peek() {
        return initializeIfAbsent().peek();
    }

    @Override
    public void setCallback(Runnable callback) {
        initializeIfAbsent().setCallback(callback);
    }

    @Override
    public Optional<Throwable> error() {
        return initializeIfAbsent().error();
    }

    @Override
    public boolean isCompleted() {
        return initializeIfAbsent().isCompleted();
    }

    @Override
    public boolean hasNextAvailable() {
        return initializeIfAbsent().hasNextAvailable();
    }

    @Override
    public void close() {
        // if we didn't initialize it yet, we may consider this one an empty stream.
        // It was closed before it got a chance to initialize. To avoid issues with components attempting to read after
        // close, we initialize it as empty.
        requireNonNull(delegate.updateAndGet(s -> s == null ? MessageStream.empty() : s)).close();
    }

    /**
     * A {@link LazyMessageStream} that expects a stream with only a single entry.
     *
     * @param <M> the type of {@link Message} in the stream
     */
    static class Single<M extends Message> extends LazyMessageStream<M> implements MessageStream.Single<M> {

        /**
         * Constructs a {@code LazyMessageStream.Single} backed by the given {@code supplier}.
         *
         * @param supplier the supplier to invoke on first message request; must not be {@code null}
         */
        Single(Supplier<MessageStream.Single<M>> supplier) {
            super(supplier::get);
        }
    }

    /**
     * A {@link LazyMessageStream} that expects an empty stream.
     * <p>
     * This subclass is always considered completed since it will never produce entries.
     *
     * @param <M> the type of {@link Message} for the empty stream
     */
    static class Empty<M extends Message> extends LazyMessageStream.Single<M> implements MessageStream.Empty<M> {

        /**
         * Constructs a {@code LazyMessageStream.Empty} backed by the given {@code supplier}.
         *
         * @param supplier the supplier to invoke on first message request; must not be {@code null}
         */
        Empty(Supplier<MessageStream.Empty<M>> supplier) {
            super(supplier::get);
        }
    }
}
