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

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.BiFunction;

/**
 * An implementation of the {@link MessageStream} that wraps a stream that will become available asynchronously.
 *
 * @param <M> The type of {@link Message} contained in the {@link MessageStream.Entry entries} of this stream.
 * @author Allard Buijze
 * @author Steven van Beelen
 * @since 5.0.0
 */
public class DelayedMessageStream<M extends Message> implements MessageStream<M> {

    private final CompletableFuture<? extends MessageStream<M>> delegate;

    private DelayedMessageStream(CompletableFuture<? extends MessageStream<M>> delegate) {
        this.delegate = delegate;
    }

    /**
     * Creates a {@link MessageStream stream} that delays actions to its {@code delegate} when it becomes available.
     * <p>
     * If the given {@code delegate} has already {@link CompletableFuture#isDone() completed}, it returns the
     * {@code MessageStream} immediately from it. Otherwise, it returns a DelayedMessageStream instance wrapping the
     * given {@code delegate}.
     *
     * @param delegate A {@link CompletableFuture} providing access to the {@link MessageStream stream} to delegate to
     *                 when it becomes available.
     * @param <M>      The type of {@link Message} contained in the {@link MessageStream.Entry entries} of this stream.
     * @return A {@link MessageStream stream} that delegates all actions to the {@code delegate} when it becomes
     * available.
     */
    public static <M extends Message> MessageStream<M> create(
            CompletableFuture<? extends MessageStream<M>> delegate) {
        CompletableFuture<MessageStream<M>> safeDelegate = delegate
                .exceptionallyCompose(CompletableFuture::failedFuture)
                .thenApply(ms -> Objects.requireNonNullElse(ms, MessageStream.empty().cast()));
        if (safeDelegate.isDone()) {
            try {
                return delegate.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new DelayedMessageStream<>(safeDelegate);
            } catch (ExecutionException e) {
                return MessageStream.failed(e.getCause());
            }
        }
        return new DelayedMessageStream<>(safeDelegate);
    }

    /**
     * Creates a {@link MessageStream.Single single stream} that delays actions to its {@code delegate} when it becomes available.
     * <p>
     * If the given {@code delegate} has already {@link CompletableFuture#isDone() completed}, it returns the
     * {@code MessageStream} immediately from it. Otherwise, it returns a DelayedMessageStream instance wrapping the
     * given {@code delegate}.
     *
     * @param delegate A {@link CompletableFuture} providing access to the {@link MessageStream.Single stream} to delegate to
     *                 when it becomes available.
     * @param <M>      The type of {@link Message} contained in the {@link MessageStream.Entry entries} of this stream.
     * @return A {@link MessageStream.Single stream} that delegates all actions to the {@code delegate} when it becomes
     * available.
     */
    public static <M extends Message> MessageStream.Single<M> createSingle(
            CompletableFuture<MessageStream.Single<M>> delegate) {
        CompletableFuture<MessageStream.Single<M>> safeDelegate = delegate
                .exceptionallyCompose(CompletableFuture::failedFuture)
                .thenApply(ms -> Objects.requireNonNullElse(ms, MessageStream.empty().cast()));
        if (safeDelegate.isDone()) {
            try {
                return delegate.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new DelayedMessageStream.Single<>(safeDelegate);
            } catch (ExecutionException e) {
                return MessageStream.failed(e.getCause());
            }
        }
        return new DelayedMessageStream.Single<>(safeDelegate);
    }

    @Override
    public Optional<Entry<M>> next() {
        if (delegate.isDone() && !delegate.isCompletedExceptionally()) {
            return delegate.getNow(null).next();
        }
        return Optional.empty();
    }

    @Override
    public void setCallback(Runnable callback) {
        delegate.whenComplete((r, e) -> {
            if (r != null) {
                r.setCallback(callback);
            } else {
                callback.run();
            }
        });
    }

    @Override
    public Optional<Throwable> error() {
        if (delegate.isDone()) {
            if (delegate.isCompletedExceptionally() && !delegate.isCancelled()) {
                // unfortunately, CompletableFuture's don't treat cancellations the same way as other exceptions
                return Optional.of(delegate.exceptionNow());
            } else {
                try {
                    return delegate.getNow(null).error();
                } catch (CancellationException | CompletionException e) {
                    return Optional.of(e);
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean isCompleted() {
        return delegate.isDone() && (delegate.isCompletedExceptionally() || delegate.getNow(null).isCompleted());
    }

    @Override
    public boolean hasNextAvailable() {
        return delegate.isDone() && !delegate.isCompletedExceptionally() && delegate.getNow(null).hasNextAvailable();
    }

    @Override
    public void close() {
        if (delegate.isDone()) {
            if (!delegate.isCompletedExceptionally()) {
                delegate.getNow(null).close();
            }
        } else {
            delegate.cancel(false);
        }
    }

    @Override
    public <R> CompletableFuture<R> reduce(R identity, BiFunction<R, ? super Entry<M>, R> accumulator) {
        return delegate.thenCompose(delegateStream -> delegateStream.reduce(identity, accumulator));
    }

    @Override
    public Optional<Entry<M>> peek() {
        if (delegate.isDone() && !delegate.isCompletedExceptionally()) {
            return delegate.getNow(null).peek();
        }
        return Optional.empty();
    }

    /**
     * An implementation of the {@link DelayedMessageStream} that expects a message stream with only a
     * {@link MessageStream.Single entry}.
     *
     * @param <M> The type of {@link Message} contained in the {@link Entry} of this stream.
     */
    static class Single<M extends Message> extends DelayedMessageStream<M>
            implements MessageStream.Single<M> {

        /**
         * Construct a {@code DelayedMessageStream.Single} for the given {@code delegate}.
         *
         * @param delegate A {@link CompletableFuture} providing access to the {@link MessageStream.Single stream} to
         *                 delegate to when it becomes available.
         */
        Single(CompletableFuture<MessageStream.Single<M>> delegate) {
            super(delegate);
        }
    }

    /**
     * An implementation of the {@link DelayedMessageStream} that expects a message stream
     * {@link MessageStream.Empty without} any entry.
     *
     * @param <M> The type of {@link Message} for the empty {@link Entry} of this stream.
     */
    static class Empty<M extends Message> extends DelayedMessageStream<M> implements MessageStream.Empty<M> {

        /**
         * Construct a {@code DelayedMessageStream.Empty} for the given {@code delegate}.
         *
         * @param delegate A {@link CompletableFuture} providing access to the {@link MessageStream.Empty stream} to
         *                 delegate to when it becomes available.
         */
        Empty(CompletableFuture<MessageStream.Empty<M>> delegate) {
            super(delegate);
        }
    }
}
