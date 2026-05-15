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

import org.axonframework.common.FutureUtils;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * A {@link MessageStream stream} implementation that contains no {@link Entry entries} at all.
 *
 * @param <M> The type of {@link Message} contained in the {@link Entry entries} of this stream.
 * @author Allard Buijze
 * @author Steven van Beelen
 * @author John Hendrikx
 * @since 5.0.0
 */
class EmptyMessageStream<M extends Message> extends AbstractMessageStream<M> implements MessageStream.Empty<M> {

    EmptyMessageStream() {
        initialize(FetchResult.completed());
    }

    @Override
    protected FetchResult<Entry<M>> fetchNext() {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<Entry<M>> asCompletableFuture() {
        return (error().isPresent())
                ? CompletableFuture.failedFuture(error().get())
                : FutureUtils.emptyCompletedFuture();
    }

    @Override
    public <R> CompletableFuture<@Nullable R> reduce(@Nullable R identity,
                                                     BiFunction<@Nullable R, ? super Entry<M>, @Nullable R> accumulator) {
        return (error().isPresent())
                ? CompletableFuture.failedFuture(error().get())
                : CompletableFuture.completedFuture(identity);
    }

    @Override
    public MessageStream<M> onErrorContinue(Function<Throwable, MessageStream<? extends M>> onError) {
        return this;
    }

    @Override
    public Empty<M> onComplete(Runnable completeHandler) {
        if (error().isPresent()) {
            return MessageStream.failed(error().get());
        }
        try {
            completeHandler.run();
            return this;
        } catch (Exception e) {
            return MessageStream.failed(e);
        }
    }
}
