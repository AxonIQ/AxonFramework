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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A {@link MessageStream} implementation using a single {@link Entry entry} or {@link CompletableFuture} completing to
 * an entry as the source.
 * <p>
 * This stream does not permit {@code null} entries. Passing a {@code null} entry directly or providing a
 * {@link CompletableFuture} that resolves to {@code null} will result in a {@link NullPointerException}.
 *
 * @param <M> The type of {@link Message} contained in the singular {@link Entry} of this stream.
 * @author Allard Buijze
 * @author Steven van Beelen
 * @author John Hendrikx
 * @since 5.0.0
 */
class SingleValueMessageStream<M extends Message> extends AbstractMessageStream<M>
        implements MessageStream.Single<M> {

    private final CompletableFuture<Entry<M>> source;
    private final AtomicBoolean read = new AtomicBoolean(false);

    /**
     * Constructs a {@link MessageStream stream} wrapping the given {@link Entry entry} into a
     * {@link CompletableFuture#completedFuture(Object) completed CompletableFuture} as the single entry in this
     * stream.
     *
     * @param entry The {@link Entry entry} which is the singular value contained in this {@link MessageStream stream};
     *              must not be {@code null}.
     * @throws NullPointerException If {@code entry} is {@code null}.
     */
    SingleValueMessageStream(Entry<M> entry) {
        this(CompletableFuture.completedFuture(Objects.requireNonNull(entry, "The entry parameter must not be null.")));
    }

    /**
     * Constructs a {@link MessageStream stream} with the given {@code source} as the provider of the single
     * {@link Entry entry} in this stream.
     * <p>
     * The {@code source} future must not resolve to {@code null}. If it does, the stream will complete exceptionally
     * with a {@link NullPointerException}.
     *
     * @param source The {@link CompletableFuture} resulting in the singular {@link Entry entry} contained in this
     *               {@link MessageStream stream}; must not be {@code null} and must not resolve to {@code null}.
     * @throws NullPointerException If {@code source} is {@code null}.
     */
    SingleValueMessageStream(CompletableFuture<Entry<M>> source) {
        this.source = Objects.requireNonNull(source, "The source parameter must not be null.");

        source.thenApply(e -> Objects.requireNonNull(e, "SingleValueMessageStream source completed with null entry"))
              .whenComplete((e, t) -> signalProgress());
    }

    @Override
    public CompletableFuture<Entry<M>> asCompletableFuture() {
        return source;
    }

    @Override
    public FetchResult<Entry<M>> fetchNext() {
        if (!source.isDone()) {
            return FetchResult.notReady();
        }

        if (source.isCompletedExceptionally()) {
            return FetchResult.error(source.exceptionNow());
        }

        Entry<M> current = source.resultNow();

        return read.compareAndSet(false, true) ? FetchResult.of(current) : FetchResult.completed();
    }

    @Override
    protected final void onCompleted() {
        if (!source.isDone()) {
            source.cancel(false);
        }
    }

    @Override
    protected String describeFlags() {
        return source.isDone() ? "" : "W";
    }
}
