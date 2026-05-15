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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

/**
 * Utility methods to work with {@link MessageStream MessageStreams}.
 *
 * @author Allard Buijze
 * @since 5.0.0
 */
@Internal
public abstract class MessageStreamUtils {

    private MessageStreamUtils() {
    }

    /**
     * A reusable no-operation {@code Runnable} instance.
     * <p>
     * This constant can be used in contexts where a non-null callback is required, but no specific action
     * needs to be performed. When executed, it performs no operation and immediately returns.
     */
    public static final Runnable NO_OP_CALLBACK = () -> {
    };


    /**
     * Returns a {@code CompletableFuture} that completes with the given reduction of messages read from the
     * {@code source}. The reduction is computed by applying the given {@code accumulator} function on the result of the
     * previous invocation in combination with each {@link MessageStream.Entry entry} returned by the given
     * {@code source}. The very first invocation of the accumulator function is given the {@code identity}.
     * <p>
     * If the given {@code source} completes normally without producing any entries, the returned
     * {@code CompletableFuture} completes with the given {@code identity}.
     * <p>
     * If the given {@code source} completes with an error, whether entries have been produced or not, the returned
     * {@code CompletableFuture} completes exceptionally with that error.
     * <p>
     * <em>Multi-threading</em><br/>
     * The accumulator function is invoked either on the thread calling this method, when entries are immediately
     * available for reading, or on the thread on which entries are reported to be available for reading from the given
     * {@code source}. The accumulator function does not need to be thread-safe.
     *
     * @param source      The {@link MessageStream} to consume messages from.
     * @param identity    The initial value to use for the accumulation.
     * @param accumulator The function to combine the current reduction result with the next
     *                    {@link MessageStream.Entry entry} from the {@link MessageStream}.
     * @param <M>         The type of {@link Message} to consume from the {@link MessageStream}.
     * @param <R>         The type of result expected from the reduction operation.
     * @return A {@code CompletableFuture} that completes with the result of the reduction operation.
     */
    public static <M extends Message, R> CompletableFuture<@Nullable R> reduce(MessageStream<M> source,
                                                                               @Nullable R identity,
                                                                               BiFunction<@Nullable R, ? super MessageStream.Entry<M>, @Nullable R> accumulator) {
        var reducer = new Reducer<>(source, identity, accumulator);
        source.setCallback(reducer::process);
        return reducer.result();
    }

    private static class Reducer<M extends Message, R> {

        private final CompletableFuture<@Nullable R> result = new CompletableFuture<>();
        private final MessageStream<M> source;
        private final BiFunction<@Nullable R, ? super MessageStream.Entry<M>, @Nullable R> accumulator;
        private final AtomicBoolean processingGate = new AtomicBoolean(false);

        private final AtomicReference<@Nullable R> intermediateResult;

        public Reducer(MessageStream<M> source, @Nullable R identity,
                       BiFunction<@Nullable R, ? super MessageStream.Entry<M>, @Nullable R> accumulator) {
            this.source = source;
            this.intermediateResult = new AtomicReference<>(identity);
            this.accumulator = accumulator;
        }

        public CompletableFuture<@Nullable R> result() {
            return result;
        }

        public void process() {
            boolean continueOnCurrentThread = true;
            while (continueOnCurrentThread && !processingGate.getAndSet(true)) {
                try {
                    while (source.hasNextAvailable()) {
                        source.next().ifPresent(e -> intermediateResult.updateAndGet(i -> accumulator.apply(i, e)));
                    }
                    if (source.isCompleted()) {
                        source.error().ifPresentOrElse(result::completeExceptionally,
                                                       () -> result.complete(intermediateResult.get()));
                    }
                } catch (Exception e) {
                    result.completeExceptionally(e);
                    source.close();
                } finally {
                    processingGate.set(false);
                }
                continueOnCurrentThread =
                        !result.isDone() && (source.hasNextAvailable() || source.isCompleted());
            }
        }
    }
}
