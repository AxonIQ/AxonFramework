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
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.axonframework.messaging.core.MessageStreamUtils.NO_OP_CALLBACK;

/**
 * Abstract base implementation of {@link MessageStream} that provides full state management for
 * consumption, completion, error propagation, closing, and callback coordination.
 * <p>
 * This implementation enforces a non-blocking, pull-based consumption model with optional
 * callback-driven signaling. Consumers invoke {@link #next()} to attempt to retrieve entries.
 * When no entry is available at that time, the stream may enter an <i>awaiting data</i> state.
 * In this state, the stream expects an external signal indicating that progress may be possible again.
 * <p>
 * Implementations must provide data through {@link #fetchNext()}, which returns a
 * {@link FetchResult} describing the current availability:
 * <ul>
 *     <li>{@link FetchResult.Value} - an entry is available</li>
 *     <li>{@link FetchResult.NotReady} - no entry is currently available, but more may arrive</li>
 *     <li>{@link FetchResult.Completed} - the stream is exhausted and will produce no further entries</li>
 *     <li>{@link FetchResult.Error} - the stream has failed with an error</li>
 * </ul>
 * <p>
 * Returning {@link FetchResult.NotReady} transitions the stream into an <i>awaiting data</i> state.
 * Implementations are expected to invoke {@link #signalProgress()} when new data may be available,
 * or when the stream completes or fails asynchronously. This will trigger the registered callback
 * if the consumer is awaiting data. It is safe to invoke {@code signalProgress()} even if the stream
 * is not awaiting data; such calls are ignored.
 * <p>
 * The registered callback (see {@link #setCallback(Runnable)}) is invoked when:
 * <ul>
 *     <li>entries may be available for consumption,</li>
 *     <li>the stream completes (normally or exceptionally)</li>
 * </ul>
 * Invocation of the callback does not guarantee that entries are available; consumers must
 * always re-check the stream using {@link #next()}, {@link #peek()}, {@link #isCompleted()},
 * and {@link #error()}.
 * <p>
 * This class enforces a strict non-blocking contract: {@link #fetchNext()} must never block.
 * If no data is currently available, implementations must return {@link FetchResult.NotReady}
 * and rely on {@link #signalProgress()} to indicate future availability.
 * <p>
 * Stream completion is terminal. Once a {@link FetchResult.Completed} or {@link FetchResult.Error}
 * is returned, the stream will not produce further entries. Implementations must signal completion
 * exclusively via {@link #fetchNext()}; they should not invoke {@link #close()} directly unless
 * acting as a consumer of another (sub)stream they own.
 * <p>
 * If the registered callback throws an exception, the stream is completed exceptionally with that
 * error, unless the callback was called to signal completion. This ensures that failures in progress
 * signaling do not leave the stream in an unusable state.
 * <p>
 * Implementations may optionally initialize the stream state during construction using
 * {@link #initialize(FetchResult)}. If not invoked explicitly, the stream is implicitly initialized
 * on first interaction with a {@link FetchResult.NotReady} state. Initialization may only occur once
 * and cannot use {@link FetchResult.Value}.
 * <p>
 * This class is thread-safe; all state transitions are guarded to support concurrent interaction
 * between producers and consumers.
 * <p>
 * Subclasses may override {@link #onCompleted()} to perform cleanup when the stream reaches a
 * terminal state.
 * <p>
 * All methods in this class are either {@code final}, {@code private}, {@code abstract} or empty to
 * protect its invariants.
 *
 * @param <M> the type of {@link Message} contained in the {@link MessageStream.Entry entries} of this stream
 * @author Jan Galinski
 * @author John Hendrikx
 * @since 5.1.0
 */
@Internal
public abstract class AbstractMessageStream<M extends Message> implements MessageStream<M> {

    /**
     * Represents the result of attempting to fetch the next element from a {@link MessageStream}.
     * <p>
     * A {@code FetchResult} models four distinct outcomes:
     * <ul>
     *     <li>{@link Value} – an element is available and returned</li>
     *     <li>{@link Completed} – no element is available and no further elements will ever arrive</li>
     *     <li>{@link NotReady} – no element is available at present, but more may become available later</li>
     *     <li>{@link Error} – the stream has failed with an error</li>
     * </ul>
     * <p>
     * This abstraction allows implementations to distinguish between a stream that is
     * temporarily out of elements and one that has been fully exhausted.
     *
     * @param <T> the type of value returned when available
     */
    public sealed interface FetchResult<T extends @Nullable Entry<?>> {

        /**
         * Creates a {@code FetchResult} reflecting the current observable state of the given {@link MessageStream}.
         * <p>
         * This method inspects the provided {@code delegate} in a non-blocking manner and translates its state into a
         * corresponding {@code FetchResult}:
         * <ul>
         *     <li>If {@link MessageStream#hasNextAvailable()} returns {@code true}, this method
         *     retrieves the next entry via {@link MessageStream#next()} and returns a
         *     {@link FetchResult.Value}.</li>
         *     <li>If no entry is currently available and the stream is not completed, a
         *     {@link FetchResult.NotReady} is returned.</li>
         *     <li>If the stream is completed normally (i.e., {@link MessageStream#error()} is empty),
         *     a {@link FetchResult.Completed} is returned.</li>
         *     <li>If the stream is completed exceptionally, a {@link FetchResult.Error} is returned
         *     containing the reported error.</li>
         * </ul>
         * <p>
         * This method effectively adapts a {@link MessageStream} to the {@code FetchResult}-based
         * consumption model used by {@link AbstractMessageStream}.
         * <p>
         * Note that this method may consume an entry from the delegate when one is available,
         * as it invokes {@link MessageStream#next()}. As such, it should only be used in contexts
         * where advancing the delegate stream is intended.
         *
         * @param <M>      the message type contained in the stream
         * @param delegate the {@link MessageStream} to inspect, must not be {@code null}
         * @return a {@code FetchResult} representing the delegate's current state
         * @throws NullPointerException if {@code delegate} is {@code null}
         */
        static <M extends Message> FetchResult<Entry<M>> of(MessageStream<M> delegate) {
            if (delegate.hasNextAvailable()) {
                return FetchResult.of(delegate.next().orElse(null));
            }

            if (!delegate.isCompleted()) {
                return FetchResult.notReady();
            }

            return delegate.error()
                           .map(FetchResult::<Entry<M>>error)
                           .orElse(FetchResult.completed());
        }

        /**
         * Creates a {@code FetchResult} representing a successfully fetched value.
         *
         * @param <T>   the entry type
         * @param value the non-{@code null} value
         * @return a {@link Value} containing the given value, never {@code null}
         */
        static <T extends @Nullable Entry<?>> FetchResult<T> of(@Nullable T value) {
            return new Value<>(value);
        }

        /**
         * Creates a {@code FetchResult} representing a producer side error.
         *
         * @param <T>   the entry type
         * @param error the non-{@code null} error
         * @return an {@link Error} representing the failure, never {@code null}
         */
        static <T extends Entry<?>> FetchResult<T> error(Throwable error) {
            return new Error<>(error);
        }

        /**
         * Returns a {@code FetchResult} indicating that no element is available and no further elements will be
         * produced.
         *
         * @param <T> the entry type
         * @return a {@link Completed} result
         */
        @SuppressWarnings("unchecked")
        static <T extends Entry<?>> FetchResult<T> completed() {
            return (FetchResult<T>) Completed.INSTANCE;
        }

        /**
         * Returns a {@code FetchResult} indicating that no element is currently available, but more elements may become
         * available in the future.
         *
         * @param <T> the entry type
         * @return a {@link NotReady} result
         */
        @SuppressWarnings("unchecked")
        static <T extends Entry<?>> FetchResult<T> notReady() {
            return (FetchResult<T>) NotReady.INSTANCE;
        }

        /**
         * A {@link FetchResult} containing a successfully fetched value. The value can be {@code null} to support
         * {@link MessageStream#ignoreEntries()}.
         *
         * @param <T>   the entry type
         * @param value the value
         */
        record Value<T extends Entry<?>>(@Nullable T value) implements FetchResult<T> {

        }

        /**
         * A {@link FetchResult} representing a terminal error in the stream.
         *
         * @param <T>   the entry type
         * @param error the non-{@code null} error
         */
        record Error<T extends Entry<?>>(Throwable error) implements FetchResult<T> {

            /**
             * Compact constructor ensuring the given {@code error} is not {@code null}.
             *
             * @param error the non-{@code null} error
             */
            public Error {
                Objects.requireNonNull(error, "error");
            }
        }

        /**
         * A {@link FetchResult} indicating that the stream is exhausted and no further elements will be produced.
         *
         * @param <T> the entry type
         */
        record Completed<T extends Entry<?>>() implements FetchResult<T> {

            private static final Completed<?> INSTANCE = new Completed<>();
        }

        /**
         * A {@link FetchResult} indicating that no element is currently available, but the stream may produce more
         * elements in the future.
         *
         * @param <T> the entry type
         */
        record NotReady<T extends Entry<?>>() implements FetchResult<T> {

            private static final NotReady<?> INSTANCE = new NotReady<>();
        }
    }

    /*
     * Concurrency model:
     *
     * This class uses synchronized for all state access instead of Atomics or explicit Locks.
     *
     * Lock direction: consumer-side calls (next, peek, hasNextAvailable, setCallback, close)
     * acquire locks from outer to inner (downstream). Producer-side signals (signalProgress and
     * the callbacks they trigger) flow in the opposite direction: inner to outer (upstream).
     * To prevent deadlock, signalProgress releases its lock before invoking the callback rather
     * than holding it across the call.
     *
     * Ordering requirement: signalProgress() must only be called after the state change it
     * announces is fully visible via fetchNext(). This ensures that if a signal arrives while
     * awaitingData is false (the consumer has not yet entered the waiting state), the consumer
     * will still observe the updated state on its next fetchNext() call without needing a retry
     * or a repeated signal. See signalProgress() for details.
     *
     * Rationale:
     * - Multiple fields (callback, peekedEntry, error, completed, awaitingData) form a single
     *   logical state. Guarding them with a single monitor ensures consistency and avoids
     *   subtle race conditions that can occur when combining multiple atomic variables.
     * - This approach is easier to reason about and verify for correctness: all state
     *   transitions happen under one lock.
     * - Expected contention is low (typically one producer and one consumer), and all
     *   operations are short and non-blocking, making the cost of synchronization negligible
     *   on modern JVMs.
     *
     * If future profiling shows contention or scalability issues, this design can be revisited
     * and optimized in a targeted manner.
     */

    /**
     * Holds the {@link Runnable} that is called when the stream completes or items are available for reading.
     * <p>
     * Only access while synchronized on this class.
     */
    private Runnable callback = NO_OP_CALLBACK;

    /**
     * Contains the entry that was peeked because of a call to {@link #peek()} or {@link #hasNextAvailable()}. A call to
     * {@link #next()} will return this entry and clear it.
     * <p>
     * Only access while synchronized on this class.
     */
    @Nullable
    private Entry<M> peekedEntry;

    /**
     * Holds the error if the stream completed with an error. This must be {@code null} if completed is {@code false}.
     * <p>
     * Only access while synchronized on this class.
     */
    @Nullable
    private Throwable error;

    /**
     * Indicates that this stream is completed, either because all items were consumed and no more can become available,
     * or because an error occurred.
     * <p>
     * Only access while synchronized on this class.
     */
    private boolean completed;

    /**
     * Indicates that no data is available downstream and that {@link #signalProgress()} must be called by the
     * downstream producer when new data is available or a state change occurred (error or completion). This will then
     * in turn trigger the consumer via the callback. When this flag is {@code false} external triggers that would
     * normally lead to a callback are suppressed.
     * <p>
     * Only access while synchronized on this class.
     */
    private boolean awaitingData;

    /**
     * Indicates that this stream is fully initialized and is in a valid state. This flag is checked in the
     * {@link #initialize(FetchResult)} call to reject any attempt to initialize the stream when it was already
     * initialized (either by calling initialize, or by any other interaction with the stream).
     * <p>
     * Only access while synchronized on this class.
     */
    private boolean initialized;

    /**
     * This method can be used after the super constructor call completes in a subtype to set the initial state of the
     * stream. It may only be called during construction, and will throw an exception if the stream already has a valid
     * state.
     *
     * @param initialFetchResult the initial fetch result; must not be null and must not be a Value
     * @throws NullPointerException     if {@code initialFetchResult} is null
     * @throws IllegalArgumentException if {@code initialFetchResult} is a Value
     * @throws IllegalStateException    if already initialized
     */
    protected final synchronized void initialize(FetchResult<Entry<M>> initialFetchResult) {
        Objects.requireNonNull(initialFetchResult, "The initialFetchResult parameter cannot be null.");

        if (initialized) {
            throw new IllegalStateException("Stream is already initialized. Call this in the constructor only.");
        }

        switch (initialFetchResult) {
            case FetchResult.Value(Entry<M> ignored) -> throw new IllegalArgumentException("Value fetchResult not supported during initialization");
            case FetchResult.Error(Throwable throwable) -> completeExceptionally(throwable);
            case FetchResult.Completed() -> complete();
            case FetchResult.NotReady() -> awaitingData = true;
        }

        this.initialized = true;
    }

    @Override
    public final void setCallback(Runnable callback) {
        Objects.requireNonNull(callback, "The callback parameter cannot be null.");

        synchronized (this) {
            this.callback = callback;

            boolean wasCompleted = isCompleted();

            if (!hasNextAvailable() && !isCompleted()) {
                return;
            }

            if (!wasCompleted && isCompleted()) {

                /*
                 * complete() or completeExceptionally() was triggered by the hasNextAvailable()
                 * probe above, which already fired the callback - don't fire again
                 */

                return;
            }
        }

        invokeCallbackSafely();
    }

    /**
     * {@inheritDoc}
     * <p>
     * This method is intended to be invoked by consumers to indicate loss of interest.
     * Implementations should not call this method directly, unless they are acting as
     * a consumer of another (sub)stream they own.
     */
    @Override
    public final synchronized void close() {

        /*
         * Close is generally called by the consumer, indicating a loss of interest
         * in any further interaction with the stream. Any buffers should be discarded
         * and calls to #hasNextAvailable, #peek and #next should all indicate no
         * availability of further elements.
         */

        complete();
    }

    /**
     * Signals that the stream may have made progress.
     * <p>
     * This method should be invoked by implementations when an external event occurs that may
     * allow the consumer to make progress. This includes, but is not limited to:
     * <ul>
     *     <li>new entries becoming available,</li>
     *     <li>the stream completing normally, or</li>
     *     <li>the stream failing with an error.</li>
     * </ul>
     * <p>
     * If the stream is currently awaiting data (i.e., a previous call to {@link #next()} returned
     * no entry due to {@link FetchResult.NotReady}), this method invokes the registered callback.
     * Otherwise, this method has no effect.
     * <p>
     * This method may be invoked even if the stream is not currently awaiting data;
     * in that case, the call has no effect.
     *
     * <h3>Ordering requirement</h3>
     * Implementations <b>must ensure that any state changes that make progress observable
     * via {@link #fetchNext()} are fully applied before invoking this method</b>.
     * <p>
     * In other words, <code>signalProgress()</code> must only be called <i>after</i> the
     * stream's internal state has been updated in a way that a subsequent {@link #fetchNext()}
     * call can observe.
     * <p>
     * Failing to observe this ordering may result in a lost wake-up scenario where:
     * <ul>
     *     <li>progress is signalled, but</li>
     *     <li>the consumer observes no available element and returns {@link FetchResult.NotReady}</li>
     * </ul>
     * even though data is available.
     * <p>
     * This method is therefore strictly a <b>notification mechanism</b>, not a state publication
     * mechanism. Correctness must come from state visibility in {@link #fetchNext()}, not from
     * the timing of this signal.
     */
    protected final void signalProgress() {

        /*
         * External progress signals should only be honored if the stream is currently
         * in a state where it is awaiting data.
         *
         * This method assumes that any state change that makes progress visible to
         * fetchNext() has already been fully published before signalProgress() is called.
         *
         * If that ordering is violated, a race may occur where a signal is emitted
         * without the consumer observing any available data. This is considered an
         * implementation error in the stream producing the signal, not a failure of
         * this method.
         */

        synchronized (this) {
            if (!awaitingData) {
                return;
            }
        }

        invokeCallbackSafely();
    }

    @Override
    public final synchronized Optional<Throwable> error() {
        return Optional.ofNullable(error);
    }

    @Override
    public final synchronized boolean isCompleted() {
        return completed;
    }

    @Override
    public final synchronized boolean hasNextAvailable() {
        return peek().isPresent();
    }

    @Override
    public final synchronized Optional<Entry<M>> peek() {
        if (completed) {
            return Optional.empty();
        }

        if (peekedEntry == null) {
            peekedEntry = next().orElse(null);
        }

        return Optional.ofNullable(peekedEntry);
    }

    @Override
    public final synchronized Optional<Entry<M>> next() {
        if (!this.initialized) {
            initialize(FetchResult.notReady());
        }

        if (completed) {
            return Optional.empty();
        }

        if (peekedEntry != null) {
            Entry<M> value = peekedEntry;

            this.peekedEntry = null;

            return Optional.of(value);
        }

        /*
         * Set awaitingData to false here, so any signalProgress triggered by fetchNext
         * is always ignored.
         */

        this.awaitingData = false;

        return switch (fetchNext()) {
            case FetchResult.Value(Entry<M> v) -> Optional.ofNullable(v);
            case FetchResult.NotReady() -> {
                awaitingData = true;

                yield Optional.empty();
            }
            case FetchResult.Error(Throwable throwable) -> {
                completeExceptionally(throwable);

                yield Optional.empty();
            }
            case FetchResult.Completed() -> {
                complete();

                yield Optional.empty();
            }
        };
    }

    /**
     * Attempts to fetch the next available {@link Entry} from the underlying source.
     * <p>
     * This method is invoked by {@link #next()} when no previously peeked entry is available. Implementations must
     * return a {@link FetchResult} describing the current state of the stream:
     * <ul>
     *     <li>{@link FetchResult.Value} if an entry is immediately available,</li>
     *     <li>{@link FetchResult.NotReady} if no entry is currently available but more may arrive later,</li>
     *     <li>{@link FetchResult.Completed} if the stream is exhausted and will produce no further entries,</li>
     *     <li>{@link FetchResult.Error} if the stream has failed with an error.</li>
     * </ul>
     * <p>
     * Returning {@link FetchResult.NotReady} will transition the stream into an <i>awaiting data</i>
     * state. Implementations must subsequently invoke {@link #signalProgress()} when progress may
     * be possible again (e.g., when new data arrives or the stream completes).
     * <p>
     * Implementations must ensure that any state changes observable via this method are fully
     * applied <em>before</em> invoking {@link #signalProgress()}. A signal that arrives before
     * the consumer has entered the awaiting state is not replayed; correctness relies on this
     * method returning the updated state when the consumer calls it next. See
     * {@link #signalProgress()} for the full ordering contract.
     * <p>
     * This method must be non-blocking. It should return immediately with the best available
     * information about the stream's current state.
     * <p>
     * Implementations must not attempt to complete or close the stream directly.
     * Instead, they must return {@link FetchResult.Completed} or {@link FetchResult.Error}
     * to signal termination.
     *
     * @return a {@link FetchResult} representing the outcome of the fetch attempt
     */
    protected abstract FetchResult<Entry<M>> fetchNext();

    /**
     * Callback invoked when the stream is about to transition to a completed state, either successfully or
     * exceptionally. Subclasses may override this method to perform custom actions on completion.
     * <p>
     * If the implementation throws an exception, the stream still completes, but it will complete with the thrown
     * exception. If the stream was about to complete with an error, and the callback fails as well, the exception is
     * added as a suppressed exception.
     */
    protected void onCompleted() {
        // leave empty, don't add behavior that then must rely on a super() call!
    }

    private void completeExceptionally(Throwable throwable) {  // keep private to guard this class's invariants
        if (!completed) {
            this.error = throwable;

            complete();
        }
    }

    private void complete() {  // keep private to guard this class's invariants
        if (!completed) {
            try {
                onCompleted();
            } catch (Exception e) {
                if (this.error == null) {
                    this.error = e;
                } else {
                    this.error.addSuppressed(e);
                }
            }

            this.completed = true;
            this.awaitingData = false;
            this.peekedEntry = null;

            invokeCallbackSafely();
        }
    }

    private void invokeCallbackSafely() {
        try {
            Runnable cb;

            synchronized (this) {
                cb = callback;
            }

            cb.run();
        } catch (Throwable t) {
            synchronized (this) {
                completeExceptionally(t);
            }
        }
    }

    /**
     * Returns a structured diagnostic representation of this {@link MessageStream}.
     * <p>
     * The output is designed for debugging complex stream compositions and focuses on three orthogonal aspects:
     * <ul>
     *     <li><b>Lifecycle status</b> (terminal or transitional state)</li>
     *     <li><b>Transient flags</b> (abnormal or noteworthy conditions)</li>
     *     <li><b>Delegation structure</b> (wrapped or composed streams)</li>
     * </ul>
     *
     * <h3>Format</h3>
     * The general structure is:
     * <pre>
     * SimpleName[status|P|flags]{delegates}
     * </pre>
     *
     * <h3>Status section</h3>
     * The status reflects the current terminal or transitional condition:
     * <ul>
     *     <li>{@code ERROR} – the stream completed exceptionally</li>
     *     <li>{@code COMPLETED} – the stream completed normally</li>
     *     <li>{@code NOT_READY} – the stream is awaiting data</li>
     *     <li>absent – stream is in its normal active state</li>
     * </ul>
     *
     * <h3>Additional markers</h3>
     * <ul>
     *     <li>{@code P} – indicates a peeked entry is currently buffered</li>
     *     <li>{@link #describeFlags()} – optional subtype-specific diagnostic flags
     *     separated by {@code "|"}, only used for abnormal or noteworthy conditions</li>
     * </ul>
     *
     * <h3>Flags</h3>
     * Flags are intended for rare or abnormal conditions only, not normal operating state.
     * They should be short, human-readable identifiers separated by {@code "|"}.
     * If no flags are present, this section is omitted.
     *
     * <h3>Delegates</h3>
     * Delegates describe wrapped or composed streams that this stream builds upon.
     * Multiple delegates are comma-separated. The currently active delegate must be
     * prefixed with {@code "*"}.
     * <p>
     * This allows reconstruction of the stream pipeline structure for debugging purposes.
     *
     * @return a structured diagnostic string representing this stream and its composition
     */
    @Override
    public synchronized String toString() {
        String status = error != null ? "ERROR"
                : completed ? "COMPLETED"
                : awaitingData ? "NOT_READY"
                : null;
        String flags = describeFlags();  // pipe separated
        String delegates = describeDelegates();  // comma separated, with the active prepended with asterisk
        String statusDescription = Stream.of(status, (peekedEntry == null ? null : "P"), flags)
                                         .filter(Objects::nonNull).collect(Collectors.joining("|"));

        return getClass().getSimpleName().replace("MessageStream", "")
                + (statusDescription.isEmpty() ? "" : "[" + statusDescription + "]")
                + (delegates == null ? "" : "{" + delegates + "}");
    }

    /**
     * Subtypes should override this to return flags that apply to this stream for debugging purposes. Flags should be
     * short. Multiple flags should be separated with a pipe ("|").
     * <p>
     * Only include flags for abnormal states, to reduce visual noise.
     *
     * @return the flags that apply to this stream, or {@code null} if there are no relevant flags
     */
    @Nullable
    protected String describeFlags() {
        return null;
    }

    /**
     * Subtypes should override this to describe any (message stream) delegates they use for debugging purposes. This
     * allows to visualize a chain of message streams, their states and how they are linked.
     * <p>
     * If there are multiple delegates, then they should be comma separated with the active delegate prepended with an
     * asterisk ("*").
     *
     * @return the description of delegate streams, or {@code null} if there are no delegates
     */
    @Nullable
    protected String describeDelegates() {
        return null;
    }
}
