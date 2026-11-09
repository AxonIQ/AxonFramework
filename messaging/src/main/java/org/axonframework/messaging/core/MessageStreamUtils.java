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

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

    /**
     * The time a blocking reduction waits for its source to report progress, before it gives up.
     */
    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(30);

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
     * available for reading, or on a virtual thread, when entries are reported to be available for reading from the
     * given {@code source} later. The accumulator function does not need to be thread-safe.
     *
     * @param source      the {@link MessageStream} to consume messages from
     * @param identity    the initial value to use for the accumulation
     * @param accumulator the function to combine the current reduction result with the next
     *                    {@link MessageStream.Entry entry} from the {@link MessageStream}
     * @param <M>         the type of {@link Message} to consume from the {@link MessageStream}
     * @param <R>         the type of result expected from the reduction operation
     * @return a {@code CompletableFuture} that completes with the result of the reduction operation
     */
    public static <M extends Message, R> CompletableFuture<@Nullable R> reduce(MessageStream<M> source,
                                                                               @Nullable R identity,
                                                                               BiFunction<@Nullable R, ? super MessageStream.Entry<M>, @Nullable R> accumulator) {
        return reduce(source, identity, accumulator, Thread::startVirtualThread);
    }

    /**
     * Returns a {@code CompletableFuture} that completes with the given reduction of messages read from the
     * {@code source}, as {@link #reduce(MessageStream, Object, BiFunction)} does, but reading entries that are
     * reported to be available later on a thread of the given {@code executor}.
     * <p>
     * <em>Multi-threading</em><br/>
     * The accumulator function is invoked either on the thread calling this method, when entries are immediately
     * available for reading, or on a thread of the given {@code executor}, when entries are reported to be available
     * for reading from the given {@code source} later. The thread that reports this is usually shared with other
     * streams, and may only be used to submit the work to the {@code executor}. The given {@code executor} must
     * therefore not run tasks on the submitting thread: with an executor that does, such as
     * {@link org.axonframework.common.DirectExecutor}, the read fails, as reading is rejected on that thread. The
     * accumulator function does not need to be thread-safe.
     * <p>
     * <em>Blocking</em><br/>
     * When the given {@code executor} is {@code null}, all entries are read on the thread calling this method, for a
     * source that must be read from that thread. When the source has nothing available, this method blocks until it
     * reports progress, so the returned {@code CompletableFuture} is complete when this method returns. If the source
     * reports no progress for 30 seconds, or the calling thread is interrupted, the source is closed and the
     * {@code CompletableFuture} completes exceptionally with a {@link TimeoutException} or an
     * {@link InterruptedException}, the latter after restoring the interrupt status of the calling thread.
     *
     * @param source      the {@link MessageStream} to consume messages from
     * @param identity    the initial value to use for the accumulation
     * @param accumulator the function to combine the current reduction result with the next
     *                    {@link MessageStream.Entry entry} from the {@link MessageStream}
     * @param executor    the {@link Executor} on which to read entries that become available after this method
     *                    returned, for example a single thread executor for a source that must be read from one
     *                    specific thread, or {@code null} to read all entries on the calling thread
     * @param <M>         the type of {@link Message} to consume from the {@link MessageStream}
     * @param <R>         the type of result expected from the reduction operation
     * @return a {@code CompletableFuture} that completes with the result of the reduction operation
     * @throws NullPointerException  if {@code source} or {@code accumulator} is {@code null}
     * @throws IllegalStateException if the {@code executor} is {@code null}, and the calling thread is handling a
     *                               progress signal of a stream, as that thread must not block
     */
    public static <M extends Message, R> CompletableFuture<@Nullable R> reduce(MessageStream<M> source,
                                                                               @Nullable R identity,
                                                                               BiFunction<@Nullable R, ? super MessageStream.Entry<M>, @Nullable R> accumulator,
                                                                               @Nullable Executor executor) {
        return reduce(source, identity, accumulator, executor, IDLE_TIMEOUT);
    }

    static <M extends Message, R> CompletableFuture<@Nullable R> reduce(MessageStream<M> source,
                                                                        @Nullable R identity,
                                                                        BiFunction<@Nullable R, ? super MessageStream.Entry<M>, @Nullable R> accumulator,
                                                                        @Nullable Executor executor,
                                                                        Duration idleTimeout) {
        Objects.requireNonNull(source, "The source parameter must not be null.");
        Objects.requireNonNull(accumulator, "The accumulator parameter must not be null.");

        if (executor == null) {
            return reduceOnCallingThread(source, identity, accumulator, idleTimeout);
        }

        Reducer<M, R> reducer = new Reducer<>(source, identity, accumulator);
        AtomicBoolean scheduled = new AtomicBoolean();

        // Reads what is available right now on the calling thread, before registering the callback: registering it
        // while entries are available invokes it, which would hand that same read off to the executor.
        reducer.process();

        if (reducer.result().isDone()) {
            return reducer.result();
        }

        source.setCallback(() -> {
            if (scheduled.compareAndSet(false, true)) {  // signals that arrive before the read starts share one read
                try {
                    executor.execute(() -> {
                        scheduled.set(false);
                        reducer.process();
                    });
                }
                catch (RuntimeException e) {
                    reducer.fail(e);
                }
            }
        });

        return reducer.result();
    }

    private static <M extends Message, R> CompletableFuture<@Nullable R> reduceOnCallingThread(
            MessageStream<M> source,
            @Nullable R identity,
            BiFunction<@Nullable R, ? super MessageStream.Entry<M>, @Nullable R> accumulator,
            Duration idleTimeout
    ) {
        if (AbstractMessageStream.isHandlingSignal()) {
            throw new IllegalStateException(
                    "A reduction on the calling thread blocks until its stream reports progress, but this thread is "
                            + "handling a progress signal and is shared with other streams. Hand the reduction off to "
                            + "another thread."
            );
        }

        Reducer<M, R> reducer = new Reducer<>(source, identity, accumulator);
        Semaphore signals = new Semaphore(0);

        // The callback only counts signals: a signal that arrives while reading leaves a permit, so that it is not
        // lost, and the next wait ends at once.
        source.setCallback(signals::release);

        try {
            while (true) {
                signals.drainPermits();
                reducer.process();

                if (reducer.result().isDone()) {
                    break;
                }

                if (!signals.tryAcquire(idleTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
                    reducer.fail(new TimeoutException(
                            "The stream reported no progress within " + idleTimeout.toMillis() + " ms."
                    ));
                    break;
                }
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reducer.fail(e);
        }

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

        public void fail(Throwable error) {
            result.completeExceptionally(error);
            source.close();
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
