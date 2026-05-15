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

import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * A {@link MessageStream} implementation that completes exceptionally through the given {@code error}.
 *
 * @param <M> The type of {@link Message} for the empty {@link Entry} of this stream.
 * @author Allard Buijze
 * @author Steven van Beelen
 * @since 5.0.0
 */
class FailedMessageStream<M extends Message> extends AbstractMessageStream<M> implements MessageStream.Empty<M> {

    /**
     * Constructs a {@link MessageStream stream} that will complete exceptionally with the given {@code error}.
     *
     * @param error The {@link Throwable} that caused this {@link MessageStream stream} to complete exceptionally.
     */
    FailedMessageStream(Throwable error) {
        initialize(FetchResult.error(error));
    }

    @Override
    public CompletableFuture<Entry<M>> asCompletableFuture() {
        return CompletableFuture.failedFuture(error().orElseThrow());
    }

    @Override
    protected FetchResult<Entry<M>> fetchNext() {
        throw new UnsupportedOperationException();
    }

    @SuppressWarnings("unchecked")
    @Override
    public <RM extends Message> Empty<RM> map(Function<Entry<M>, Entry<RM>> mapper) {
        return (FailedMessageStream<RM>) this;
    }

    @Override
    public <R> CompletableFuture<R> reduce(R identity,
                                           BiFunction<R, ? super Entry<M>, R> accumulator) {
        return CompletableFuture.failedFuture(error().orElseThrow());
    }

    @Override
    public Empty<M> onComplete(Runnable completeHandler) {
        return this;
    }

    @Override
    public MessageStream<M> concatWith(MessageStream<? extends M> other) {
        return this;
    }
}
