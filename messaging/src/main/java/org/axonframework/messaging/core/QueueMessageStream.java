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

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TransferQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link MessageStream} implementation backed by a {@link BlockingQueue}.
 * <p>
 * This stream acts as a bridge between a producer and a consumer:
 * {@link MessageStream.Entry entries} are {@link #offer(Message, Context) offered} into an
 * internal queue and consumed via the {@link MessageStream} API.
 * <p>
 * The stream supports both finite and dynamically produced sequences:
 * <ul>
 *     <li>While open, the stream may temporarily have no elements available,
 *     in which case consumption methods may indicate a "not ready" state.</li>
 *     <li>Once {@link #seal()} or {@link #sealExceptionally(Throwable)} is invoked,
 *     no further elements are accepted. Remaining buffered elements can still
 *     be consumed.</li>
 *     <li>After the queue is drained, the stream completes normally or
 *     exceptionally depending on how it was sealed.</li>
 * </ul>
 * <p>
 * If a callback is registered via {@link #setCallback(Runnable)}, it is invoked
 * when new elements become available. If the callback throws an exception, the
 * stream transitions to an error state immediately and any buffered elements
 * are discarded.
 *
 * @param <M> The type of {@link Message} contained in this stream.
 * @author Allard Buijze
 * @author John Hendrikx
 * @since 5.0.0
 */
public class QueueMessageStream<M extends Message> extends AbstractMessageStream<M> {

    /**
     * Represents the current state of the producing side of this message stream.
     * <p>
     * There are three possible states:
     * <ul>
     *     <li>Open - the producer can add new elements at any time</li>
     *     <li>Sealed - the producer has finished producing elements; no more
     *     elements can be added. Once all elements are consumed, the stream becomes
     *     completed</li>
     *     <li>Sealed with exception - the producer encountered an error; no
     *     more elements can be added. Once all elements that were buffered are
     *     consumed, the stream will complete with the exception.</li>
     * </ul>
     *
     * @param sealed {@code true} if the no more elements can be added by the
     *               producer, otherwise {@code false}
     * @param error  if {@code sealed} is {@code true} this indicates whether
     *               the stream was sealed with or without an error
     */
    record State(boolean sealed, Throwable error) {}

    private static final State OPEN = new State(false, null);

    private final AtomicReference<State> state = new AtomicReference<>(OPEN);
    private final BlockingQueue<Entry<M>> queue;

    /**
     * Constructs a {@link QueueMessageStream} backed by an unbounded queue.
     * <p>
     * Offering elements will succeed as long as sufficient memory is available.
     */
    public QueueMessageStream() {
        this(new LinkedBlockingQueue<>());
    }

    /**
     * Constructs a {@link QueueMessageStream} using the given {@code queue}
     * as its underlying buffer.
     * <p>
     * Both production and consumption semantics depend on the provided queue
     * implementation. Elements are added using {@link BlockingQueue#offer(Object)}
     * and consumed using {@link BlockingQueue#poll()}.
     * <p>
     * The queue must support buffering of elements. Implementations such as
     * {@link TransferQueue} that rely on direct handoff without internal storage
     * are not suitable.
     *
     * @param queue The queue used to buffer {@link MessageStream.Entry entries} between
     *              producer and consumer.
     */
    public QueueMessageStream(BlockingQueue<Entry<M>> queue) {
        this.queue = queue;
    }

    @Override
    protected FetchResult<Entry<M>> fetchNext() {
        Entry<M> next = queue.poll();

        if (next != null) {
            return FetchResult.of(next);
        }

        return switch(state.get()) {
            case State(boolean sealed, Throwable error) when sealed && error == null -> FetchResult.completed();
            case State(boolean sealed, Throwable error) when sealed -> FetchResult.error(error);
            default -> FetchResult.notReady();
        };
    }

    /**
     * Attempts to add the given {@code message} and {@code context} to this stream.
     * <p>
     * If successful, the element becomes available for consumption and any
     * registered callback is invoked to signal its availability.
     * <p>
     * If the callback throws an exception, the stream transitions to an error
     * state immediately. In that case, any buffered elements are discarded and
     * further interaction with the stream will reflect the error.
     * <p>
     * This method returns {@code false} if:
     * <ul>
     *     <li>the stream has been {@link #seal() sealed} or
     *     {@link #sealExceptionally(Throwable) sealed exceptionally},</li>
     *     <li>the underlying queue cannot accept the element (e.g. bounded queue is full), or</li>
     *     <li>the stream has been {@link #close() closed} by the consumer.</li>
     * </ul>
     *
     * @param message the message to add
     * @param context the context associated with the message
     * @return {@code true} if the element was accepted, otherwise {@code false}.
     */
    public boolean offer(M message, Context context) {
        if (state.get().equals(OPEN) && queue.offer(new SimpleEntry<>(message, context))) {
            signalProgress();

            return true;
        }

        return false;
    }

    /**
     * Seals this queue, preventing any further elements from being added.
     * The queue may still contain elements; once these have been consumed,
     * the stream completes.
     * <p>
     * Any {@link Message Messages} {@link #offer(Message, Context) offered} after sealing
     * will be rejected.
     */
    public void seal() {
        if (state.compareAndSet(OPEN, new State(true, null))) {
            signalProgress();
        }
    }

    /**
     * Seals this queue exceptionally, indicating that no further elements will be added
     * and that an error has occurred during production.
     * <p>
     * Any {@link Message Messages} {@link #offer(Message, Context) offered} after this method
     * is invoked will be rejected.
     * <p>
     * Already buffered elements may still be consumed via {@link #next()} or {@link #peek()}.
     * Once the queue is empty, the stream will complete and {@link #error()} will report
     * the provided {@link Throwable}.
     *
     * @param error the {@link Throwable} representing the error that caused the stream to fail
     */
    public void sealExceptionally(Throwable error) {
        if (state.compareAndSet(OPEN, new State(true, error))) {
            signalProgress();
        }
    }

    @Override
    protected final void onCompleted() {
        queue.clear();
        seal();
    }
}
