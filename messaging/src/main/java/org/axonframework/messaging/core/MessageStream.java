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

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.util.Objects.requireNonNull;

/**
 * Represents a stream of {@link Entry entries} containing {@link Message Messages} of type {@code M} that can be
 * consumed as they become available.
 * <p>
 * A Message Stream is asynchronous by nature. All operations are non-blocking by design, although some implementations
 * may choose to block for certain conditions. In that case, the streams must document so explicitly.
 * <p>
 * To get notified of anything potentially available for consumption, one must register a {@link #setCallback(Runnable)}
 * callback. This callback is invoked each time information is *potentially* available for consumption. There is no
 * guarantee that entries are available for consumption when this callback is invoked. When consuming the stream, one
 * must also ensure to check the {@link #isCompleted()} status and potentially the presence of errors using
 * {@link #error()}.
 * <p>
 * When clients choose not to consume a stream until completion (when {@link #isCompleted()} returns {@code true}), it
 * must be closed by calling {@link #close()}. This ensures that the producing side is notified of the closure and can
 * clean up resources.
 * <p>
 * A stream is terminal once completed (either normally or exceptionally).
 * After completion:
 * <ul>
 *     <li>{@link #next()} will never return entries</li>
 *     <li>{@link #hasNextAvailable()} will always return {@code false}</li>
 *     <li>{@link #error()} will report the failure if completed exceptionally</li>
 * </ul>
 *
 * @param <M> The type of {@link Message} contained in the {@link Entry entries} of this stream.
 * @author Allard Buijze
 * @author Ivan Dugalic
 * @author Milan Savić
 * @author Mitchell Herrijgers
 * @author Steven van Beelen
 * @author John Hendrikx
 * @since 5.0.0
 */
public interface MessageStream<M extends Message> {

    /**
     * Create a stream that provides the {@link Message Messages} returned by the given {@code iterable}, automatically
     * wrapped in an {@link Entry}.
     * <p>
     * The returned stream will provide the messages as provided by the {@link Iterable#iterator()} call on the given
     * {@code iterable}.
     *
     * @param iterable The {@link Iterable} providing the {@link Message Messages} to stream.
     * @param <M>      The type of {@link Message} contained in the {@link Entry entries} of this stream.
     * @return A stream of {@link Entry entries} that return the {@link Message Messages} provided by the given
     * {@code iterable}.
     */
    static <M extends Message> MessageStream<M> fromIterable(Iterable<? extends M> iterable) {
        return fromIterable(iterable, message -> Context.empty());
    }

    /**
     * Creates a MessageStream that provides the given {@code items} and then completes.
     *
     * @param items The items to return in the stream.
     * @param <M>   The type of message the stream contains
     * @return a MessageStream that contains the given {@code items} and then completes.
     */
    @SafeVarargs
    static <M extends Message> MessageStream<M> fromItems(M... items) {
        return fromIterable(List.of(items), message -> Context.empty());
    }

    /**
     * Create a stream that provides the {@link Message Messages} returned by the given {@code iterable}, automatically
     * wrapped in an {@link Entry} with the resulting {@link Context} from the {@code contextSupplier}.
     * <p>
     * The returned stream will provide the messages as provided by the {@link Iterable#iterator()} call on the given
     * {@code iterable}.
     *
     * @param iterable        The {@link Iterable} providing the {@link Message Messages} to stream.
     * @param contextSupplier A {@link Function} ingesting each {@link Message} from the given {@code iterable}
     *                        returning the {@link Context} to set for the {@link Entry} the {@code Message} is wrapped
     *                        in.
     * @param <M>             The type of {@link Message} contained in the {@link Entry entries} of this stream.
     * @return A stream of {@link Entry entries} that return the {@link Message Messages} provided by the given
     * {@code iterable} with a {@link Context} provided by the {@code contextSupplier}.
     */
    static <M extends Message> MessageStream<M> fromIterable(Iterable<? extends M> iterable,
                                                             Function<? super M, ? extends Context> contextSupplier) {
        return new IteratorMessageStream<>(StreamSupport.stream(iterable.spliterator(), false)
                                                        .<Entry<M>>map(message -> new SimpleEntry<>(
                                                                message,
                                                                contextSupplier.apply(message)
                                                        ))
                                                        .iterator());
    }

    /**
     * Create a stream that provides the {@link Message Messages} returned by the given {@code stream}, automatically
     * wrapped in an {@link Entry}.
     *
     * @param stream The {@link Stream} providing the {@link Message Messages} to stream.
     * @param <M>    The type of {@link Message} contained in the {@link Entry entries} of this stream.
     * @return A stream of {@link Entry entries} that return the {@link Message Messages} provided by the given
     * {@code stream}.
     */
    static <M extends Message> MessageStream<M> fromStream(Stream<? extends M> stream) {
        return fromStream(stream, message -> Context.empty());
    }

    /**
     * Create a stream that provides the {@link Message Messages} returned by the given {@code stream}, automatically
     * wrapped in an {@link Entry} with the resulting {@link Context} from the {@code contextSupplier}.
     *
     * @param stream          The {@link Stream} providing the {@link Message Messages} to stream.
     * @param contextSupplier A {@link Function} ingesting each {@link Message} from the given {@code stream} returning
     *                        the {@link Context} to set for the {@link Entry} the {@code Message} is wrapped in.
     * @param <M>             The type of {@link Message} contained in the {@link Entry entries} of this stream.
     * @return A stream of {@link Entry entries} that return the {@link Message Messages} provided by the given
     * {@code stream} with a {@link Context} provided by the {@code contextSupplier}.
     */
    static <M extends Message> MessageStream<M> fromStream(Stream<? extends M> stream,
                                                           Function<? super M, ? extends Context> contextSupplier) {
        return new IteratorMessageStream<>(stream.<Entry<M>>map(m -> new SimpleEntry<>(m, contextSupplier.apply(m)))
                                                 .iterator());
    }

    /**
     * Create a stream that provides the items of type {@code T} returned by the given {@code stream}, automatically
     * wrapped in an {@link Entry} with the resulting {@link Message} and {@link Context} from the
     * {@code messageSupplier} and the {@code contextSupplier} respectively.
     *
     * @param stream          The {@link Stream} providing the items of type {@code T} to map to a {@link Message} and
     *                        {@link Context}.
     * @param messageSupplier A {@link Function} ingesting each item of type {@code T} from the given {@code stream}
     *                        returning the {@link Message} to set for the {@link Entry} to add in the resulting
     *                        MessageStream.
     * @param contextSupplier A {@link Function} ingesting each item of type {@code T} from the given {@code stream}
     *                        returning the {@link Context} to set for the {@link Entry} to add in the resulting
     *                        MessageStream.
     * @param <T>             The type of item contained in the given {@code stream} that will be mapped to a
     *                        {@link Message} and {@link Context} by the {@code messageSupplier} and
     *                        {@code contextSupplier} respectively.
     * @param <M>             The type of {@link Message} contained in the {@link Entry entries} of this stream.
     * @return A stream of {@link Entry entries} that return the {@link Message Messages} resulting from the given
     * {@code messageSupplier} with a {@link Context} provided by the {@code contextSupplier}.
     */
    static <T, M extends Message> MessageStream<M> fromStream(Stream<? extends T> stream,
                                                              Function<? super T, ? extends M> messageSupplier,
                                                              Function<? super T, ? extends Context> contextSupplier) {
        return new IteratorMessageStream<>(stream.<Entry<M>>map(item -> new SimpleEntry<>(messageSupplier.apply(item),
                                                                                          contextSupplier.apply(item)))
                                                 .iterator());
    }

    /**
     * Create a stream that returns a single {@link Entry entry} wrapping the {@link Message} from the given
     * {@code future}, once the given {@code future} completes.
     * <p>
     * The stream will contain at most a single entry. It may also contain no entries if the future returns
     * {@code null}. The stream will complete with an exception when the given {@code future} completes exceptionally.
     *
     * @param future The {@link CompletableFuture} providing the {@link Message} to contain in the stream.
     * @param <M>    The type of {@link Message} contained in the {@link Entry entries} of this stream.
     * @return A stream containing at most one {@link Entry entry} from the given {@code future}.
     */
    static <M extends Message> Single<M> fromFuture(CompletableFuture<? extends @Nullable M> future) {
        return fromFuture(future, message -> Context.empty());
    }

    /**
     * Create a stream that returns a single {@link Entry entry} wrapping the {@link Message} from the given
     * {@code future}, once the given {@code future} completes.
     * <p>
     * The automatically generated {@code Entry} will have the {@link Context} as given by the {@code contextSupplier}.
     * <p>
     * The stream will contain at most a single entry. It may also contain no entries if the future returns
     * {@code null}. The stream will complete with an exception when the given {@code future} completes exceptionally.
     *
     * @param future          The {@link CompletableFuture} providing the {@link Message} to contain in the stream.
     * @param contextSupplier A {@link Function} ingesting the {@link Message} from the given {@code future} returning
     *                        the {@link Context} to set for the {@link Entry} the {@code Message} is wrapped in.
     * @param <M>             The type of {@link Message} contained in the {@link Entry entries} of this stream.
     * @return A stream containing at most one {@link Entry entry} from the given {@code future} with a {@link Context}
     * provided by the {@code contextSupplier}.
     */
    static <M extends Message> Single<M> fromFuture(CompletableFuture<? extends @Nullable M> future,
                                                    Function<M, Context> contextSupplier) {
        return DelayedMessageStream.createSingle(
                future.thenApply(message -> MessageStream.just(message, contextSupplier))
        );
    }

    /**
     * Create a stream containing the single given {@code message}, automatically wrapped in an {@link Entry}.
     * <p>
     * Once the {@code Entry} is consumed, the stream is considered completed. If {@code message} is {@code null}, the
     * returned stream contains no entries and behaves like {@link #empty()}.
     *
     * @param message The {@link Message} to wrap in an {@link Entry} and return in the stream, or {@code null} to
     *                produce an empty stream.
     * @param <M>     The type of {@link Message} given.
     * @return A stream consisting of a single {@link Entry entry} wrapping the given {@code message}, or an
     * {@link Empty empty stream} if {@code message} is {@code null}.
     */
    static <M extends Message> Single<M> just(@Nullable M message) {
        return just(message, m -> Context.empty());
    }

    /**
     * Create a stream containing the single given {@code message}, automatically wrapped in an {@link Entry}.
     * <p>
     * Once the {@code Entry} is consumed, the stream is considered completed. If {@code message} is {@code null}, the
     * returned stream contains no entries and behaves like {@link #empty()}; the {@code contextSupplier} is not
     * invoked in that case.
     *
     * @param message         The {@link Message} to wrap in an {@link Entry} and return in the stream, or {@code null}
     *                        to produce an empty stream.
     * @param contextSupplier A {@link Function} ingesting the given {@code message} returning the {@link Context} to
     *                        set for the {@link Entry} the {@code message} is wrapped in.
     * @param <M>             The type of {@link Message} given.
     * @return A stream consisting of a single {@link Entry entry} wrapping the given {@code message} with a
     * {@link Context} provided by the {@code contextSupplier}, or an {@link Empty empty stream} if {@code message}
     * is {@code null}.
     */
    static <M extends Message> Single<M> just(@Nullable M message,
                                              Function<M, Context> contextSupplier) {
        if (message == null) {
            return empty().cast();
        }
        var safeMsg = requireNonNull(message);
        return new SingleValueMessageStream<>(new SimpleEntry<>(safeMsg, contextSupplier.apply(message)));
    }

    /**
     * Create a stream that completed with given {@code failure}.
     * <p>
     * All attempts to read from this stream will propagate this error.
     *
     * @param failure The {@link Throwable} to propagate to consumers of the stream.
     * @param <M>     The type of {@link Message} contained in the {@link Entry entries} of this stream.
     * @return A stream that is completed exceptionally.
     */
    static <M extends Message> Empty<M> failed(Throwable failure) {
        return new FailedMessageStream<>(failure);
    }

    /**
     * Create a stream that carries no {@link Entry} and is considered to be successfully completed.
     * <p>
     * Any attempt to convert this stream to a component that requires an entry to be returned (such as
     * {@link CompletableFuture}), will have it return {@code null}.
     *
     * @param <M> The type of {@link Message} contained in the {@link Entry entries} of this stream.
     * @return An empty stream.
     */
    static <M extends Message> Empty<M> empty() {
        return new EmptyMessageStream<>();
    }

    /**
     * Returns a {@link Single stream} that includes only the first message of {@code this} stream, unless it completes
     * without delivering any messages, in which case it completes the same way.
     * <p>
     * When the first message is delivered, the returned stream completes normally, independently of how this stream
     * completes. Upon consuming the first message, this stream is {@link #close()} immediately.
     *
     * @return A {@link Single stream} that includes only the first message of {@code this} stream.
     */
    default Single<M> first() {
        return new TruncateFirstMessageStream<>(this);
    }

    /**
     * Returns a stream that consumes all messages from this stream, but ignores the results and completes when this
     * stream completes.
     * <p>
     * Unlike simply closing the stream, the returned stream will still cause upstream entries to be consumed and any
     * registered callbacks to be invoked.
     *
     * @return An Empty stream that ignores all results.
     */
    default Empty<M> ignoreEntries() {
        return new IgnoredEntriesMessageStream<>(this).cast();
    }

    /**
     * Returns an Optional carrying the next {@link Entry entry} from the stream, if such entry was available. If no
     * entry was available for reading, this method returns an empty Optional.
     * <p>
     * This method will never block for elements becoming available.
     *
     * @return An optional carrying the next {@link Entry entry}, if available.
     */
    Optional<Entry<M>> next();

    /**
     * Returns an Optional carrying the next {@link Entry entry} from the stream (without moving the stream pointer), if
     * such entry was available. If no entry was available for reading, this method returns an empty Optional.
     * <p>
     * This method will never block for elements becoming available.
     *
     * @return An optional carrying the next {@link Entry entry}, if available.
     */
    Optional<Entry<M>> peek();

    /**
     * Registers the callback to invoke when {@link Entry entries} are available for reading or when the stream
     * completes (either normally or with an error), with the intent that a consumer of this stream will read the
     * available messages completely.
     * <p>
     * This has the following implications:
     * <ul>
     *     <li>When you register the callback on an already completed stream, the callback is invoked directly.</li>
     *     <li>When you register the callback on a stream having entries available, the callback is invoked, and
     *     you must consume the existing entries.</li>
     *     <li>A registered callback is invoked again when all entries were consumed and new entries arrive.</li>
     *     <li>Depending on the implementation of the stream, your callback <i>might</i> be invoked again while you
     *     are still consuming entries, in these cases it is your responsibility to synchronize the callback executions.
     *     Using {@link #reduce(Object, BiFunction)} might be a better fit because it guarantees isolated execution of
     *     callbacks using a <code>processingGate</code>.</li>
     * </ul>
     * <p>
     * Note that an invocation of the callback does not in any way guarantee that entries are indeed available, or
     * that the stream has indeed been completed. Implementations may choose to suppress repeated invocations of
     * the callback if no entries have been read in the meantime.
     * <p>
     * Any previously registered callback is replaced with the given {@code callback}.
     * <p>
     * The callback is called on an arbitrary thread, and it should keep work performed on this thread to a minimum
     * as this may interfere with other callbacks handled by the same thread. Any exception thrown by the callback
     * will result in the stream completing with this exception as the error, unless the callback was called to
     * indicate completion.
     *
     * @param callback The callback to invoke when {@link Entry entries} are available for reading, or the stream
     *                 completes.
     * @see #reduce(Object, BiFunction)
     */
    void setCallback(Runnable callback);

    /**
     * Indicates whether any error has been reported in this stream. Implementations may choose to not return any error
     * here until all {@link Entry entries} that were available for reading before any error occurred have been
     * consumed.
     *
     * @return An optional containing the possible error this stream completed with.
     */
    Optional<Throwable> error();

    /**
     * Indicates whether this stream has been completed. A completed stream will never return {@link Entry entries} from
     * {@link #next()}, and {@link #hasNextAvailable()} will always return {@code false}. If the stream completed with
     * an error, {@link #error()} will report so.
     *
     * @return {@code true} if the stream completed, otherwise {@code false}.
     */
    boolean isCompleted();

    /**
     * Indicates whether an {@link Entry entry} is available for immediate reading. When entries are reported available,
     * there is no guarantee that {@link #next()} will indeed return an entry. However, besides any concurrent activity
     * on this stream, it is guaranteed that no entries are available for reading when this method returns
     * {@code false}.
     *
     * @return {@code true} when there are {@link Entry entries} available for reading, {@code false} otherwise.
     */
    boolean hasNextAvailable();

    /**
     * Closes this stream, indicating that the consumer is no longer interested in receiving further entries.
     * <p>
     * This is a <b>consumer-driven cancellation</b> operation. It immediately transitions the stream into a
     * terminal state and discards any remaining or buffered entries, including any peeked entries.
     * After this call returns:
     * <ul>
     *     <li>{@link #next()} will not return further entries</li>
     *     <li>{@link #peek()} will return {@link Optional#empty()}</li>
     *     <li>{@link #hasNextAvailable()} will return {@code false}</li>
     * </ul>
     * <p>
     * This method is intended exclusively for consumer-side cancellation. It signals that no further processing
     * is required and allows implementations to release all resources immediately.
     * <p>
     * This operation is fundamentally different from <i>producer-side completion</i>. When an implementation
     * determines that no further elements will ever become available, it must <b>not</b> call {@code close()}</b>.
     * Instead, it must:
     * <ul>
     *     <li>stop producing new elements,</li>
     *     <li>allow any buffered elements to be consumed normally, and</li>
     *     <li>transition the stream to completion using its normal terminal signaling mechanism
     *         (e.g. completion or error through {@code next()}/{@code fetchNext()}).</li>
     * </ul>
     * <p>
     * Calling this method is only required when the consumer chooses not to fully consume the stream.
     * Streams must always release resources when they complete, regardless of whether {@code close()} is invoked.
     */
    void close();

    /**
     * Returns a stream that maps each {@link Entry entry} from this stream using given {@code mapper} function into an
     * entry carrying a {@code MessageEntry} with a {@link Message} of type {@code RM}.
     * <p>
     * The returned stream completes the same way {@code this} stream completes.
     *
     * @param mapper The function converting {@link Entry entries} from this stream from entries containing
     *               {@link Message message} of type {@code M} to {@code RM}.
     * @param <RM>   The declared type of {@link Message} contained in the returned {@link Entry entry}.
     * @return A stream with all {@link Entry entries} mapped according to the {@code mapper} function.
     */
    default <RM extends Message> MessageStream<RM> map(Function<Entry<M>, Entry<RM>> mapper) {
        return new MappedMessageStream<>(this, mapper);
    }

    /**
     * Returns a stream that maps each {@link Entry#message() message} from the {@link Entry entries} in this stream
     * using the given {@code mapper} function. This maps the {@link Message Messages} from type {@code M} to type
     * {@code RM}.
     * <p>
     * The returned stream completes the same way {@code this} stream completes.
     *
     * @param mapper The function converting {@link Entry#message() message} from the {@link Entry entries} in this
     *               stream from type {@code M} to {@code RM}.
     * @param <RM>   The declared type of {@link Message} contained in the returned {@link Entry entry}.
     * @return A stream with all {@link Entry entries} mapped according to the {@code mapper} function.
     */
    default <RM extends Message> MessageStream<RM> mapMessage(Function<M, RM> mapper) {
        return map(entry -> entry.map(mapper));
    }

    /**
     * Returns a {@link CompletableFuture} of type {@code R}, using the given {@code identity} as the initial value for
     * the given {@code accumulator}. Throws an exception if this stream is unbounded.
     * <p>
     * The {@code accumulator} will process all {@link Entry entries} within this stream until a single value of type
     * {@code R} is left.
     * <p>
     * Note that parallel processing <b>is not</b> supported!
     *
     * @param identity    The initial value given to the {@code accumulator}.
     * @param accumulator The {@link BiFunction} accumulating all {@link Entry entries} from this stream into a return
     *                    value of type {@code R}.
     * @param <R>         The result of the {@code accumulator} after reducing all {@link Entry entries} from this
     *                    stream.
     * @return A {@link CompletableFuture} carrying the result of the given {@code accumulator} that reduced the entire
     * stream.
     * @throws UnsupportedOperationException if this stream is unbounded
     */
    default <R> CompletableFuture<@Nullable R> reduce(@Nullable R identity,
                                                      BiFunction<@Nullable R, ? super Entry<M>, @Nullable R> accumulator) {
        return MessageStreamUtils.reduce(this, identity, accumulator);
    }

    /**
     * Invokes the given {@code onNext} each time an {@link Entry entry} is consumed from this stream.
     * <p>
     * Depending on the stream's implementation, the function may be invoked when the entry is provided to the
     * {@link Consumer}, or at the moment it's available for reading on the stream. Subscribing multiple times to the
     * resulting stream may cause the given {@code onNext} to be invoked more than once for an entry.
     *
     * @param onNext The {@link Consumer} to invoke for each {@link Entry entry}.
     * @return A stream that will invoke the given {@code onNext} for each {@link Entry entry}.
     */
    default MessageStream<M> onNext(Consumer<Entry<M>> onNext) {
        return new OnNextMessageStream<>(this, onNext);
    }

    /**
     * Returns a stream that, when {@code this} stream completes with an error, continues reading from the stream
     * provided by given {@code onError} function.
     *
     * @param onError The {@link Function} providing the replacement stream to continue with after an exception on
     *                {@code this} stream.
     * @return A stream that continues onto another stream when {@code this} stream completes with an error.
     */
    default MessageStream<M> onErrorContinue(Function<Throwable, MessageStream<? extends M>> onError) {
        return new OnErrorContinueMessageStream<>(this, onError);
    }

    /**
     * Returns a stream that will filter {@link MessageStream.Entry entries} based on the given {@code filter}.
     *
     * @param filter The {@link MessageStream.Entry} predicate, that will filter out entries. Returning {@code true}
     *               from this lambda will keep the entry, while returning {@code false} will remove it.
     * @return A stream for which the {@link MessageStream.Entry entries} have been filtered by the given
     * {@code filter}.
     */
    default MessageStream<M> filter(Predicate<Entry<M>> filter) {
        return new FilteringMessageStream<>(this, filter);
    }

    /**
     * Returns a stream that concatenates this stream with the given {@code other} stream, if this stream completes
     * successfully. Throws an exception if this stream is unbounded.
     * <p>
     * When {@code this} stream completes with an error, so does the returned stream.
     *
     * @param other The MessageStream to append to this stream.
     * @return A stream concatenating this stream with given {@code other}.
     * @throws UnsupportedOperationException if this stream is unbounded
     */
    default MessageStream<M> concatWith(MessageStream<? extends M> other) {
        return new ConcatenatingMessageStream<>(this, other);
    }

    /**
     * Returns a stream that invokes the given {@code completeHandler} when the stream completes normally.
     * Throws an exception if this stream is unbounded.
     *
     * @param completeHandler The {@link Runnable} to invoke when the stream completes normally.
     * @return A stream that invokes the {@code completeHandler} upon normal completion.
     * @throws UnsupportedOperationException if this stream is unbounded
     */
    default MessageStream<M> onComplete(Runnable completeHandler) {
        return new CompletionCallbackMessageStream<>(this, completeHandler);
    }

    /**
     * Casts this stream to the given type. This method is provided to be more flexible with generics. It is the
     * caller's responsibility to ensure the cast is valid. Failure to do so may result in {@link ClassCastException}
     * when reading elements.
     *
     * @param <T> The type of {@link Message} to cast the MessageStream to.
     * @return This instance, cast to the given {@link Message} of type {@code T}.
     */
    @SuppressWarnings("unchecked")
    default <T extends Message> MessageStream<T> cast() {
        return (MessageStream<T>) this;
    }

    /**
     * Returns a stream that, when it is either explicitly closed using {@link #close()}, or when this stream completes
     * (regularly or with an error) calls the given {@code closeHandler}.
     *
     * @param closeHandler The handler to invoke when this stream is closed or terminates.
     * @return a stream that invokes the given {@code closeHandler} upon closing or termination.
     */
    default MessageStream<M> onClose(Runnable closeHandler) {
        return new CloseCallbackMessageStream<>(this, closeHandler);
    }

    /**
     * A {@link MessageStream}-specific container of {@link Message} implementations.
     * <p>
     * May be implemented to support {@link Entry entries} that contain several objects. As such, this interface may be
     * regarded as a tuple.
     *
     * @param <M> The type of {@link Message} contained in this {@link Entry} implementation.
     * @author Allard Buijze
     * @author Milan Savić
     * @author Mitchell Herrijgers
     * @author Steven van Beelen
     * @since 5.0.0
     */
    interface Entry<M extends Message> extends Context {

        /**
         * Returns the {@link Message} implementation contained by this Entry.
         *
         * @return The {@link Message} implementation contained by this Entry.
         */
        M message();

        /**
         * Maps the {@link #message()} by running it through the given {@code mapper}. This adjusts the contained
         * {@link #message()} into a {@link Message} implementation of type {@code RM}.
         *
         * @param mapper A {@link Function} mapping the {@link #message()} of type {@code M} to a {@link Message} of
         *               type {@code RM}.
         * @param <RM>   The declared type of {@link Message} resulting from the given {@code mapper}.
         * @return The result of running the current {@link #message()} through the given {@code mapper}.
         */
        <RM extends Message> Entry<RM> map(Function<M, RM> mapper);

        @Override
        <T> Entry<M> withResource(ResourceKey<T> key, T resource);
    }

    /**
     * A {@code MessageStream} implementation that returns at most a <b>single</b> result before completing.
     *
     * @param <M> The type of {@link Message} contained in the singular {@link Entry} of this stream.
     * @author Allard Buijze
     * @author Mateusz Nowak
     * @author Mitchell Herrijgers
     * @author Steven van Beelen
     * @see #fromFuture(CompletableFuture)
     * @see #fromFuture(CompletableFuture, Function)
     * @see #just(Message)
     * @see #just(Message, Function)
     * @since 5.0.0
     */
    interface Single<M extends Message> extends MessageStream<M> {

        @Override
        default Single<M> first() {
            return this;
        }

        @Override
        default <RM extends Message> Single<RM> map(Function<Entry<M>, Entry<RM>> mapper) {
            return new MappedMessageStream.Single<>(this, mapper);
        }

        @Override
        default <RM extends Message> Single<RM> mapMessage(Function<M, RM> mapper) {
            return map(e -> e.map(mapper));
        }

        @Override
        default Single<M> filter(Predicate<Entry<M>> filter) {
            return new FilteringMessageStream.Single<>(this, filter);
        }

        @Override
        default Single<M> onNext(Consumer<Entry<M>> onNext) {
            return new OnNextMessageStream.Single<>(this, onNext);
        }

        @Override
        default Single<M> onComplete(Runnable completeHandler) {
            return new CompletionCallbackMessageStream.Single<>(this, completeHandler);
        }

        @SuppressWarnings("unchecked")
        @Override
        default <R extends Message> Single<R> cast() {
            return (Single<R>) this;
        }

        /**
         * Returns a {@link CompletableFuture} that completes when this stream has been fully consumed,
         * either normally or with an error.
         * <p>
         * The future completes with the first {@link Entry} observed during stream consumption,
         * or {@code null} if no entries were produced before completion.
         * <p>
         * This method always drives full stream execution (via {@code reduce}), even though only
         * the first encountered entry is retained. This is required to correctly support streams
         * that may:
         * <ul>
         *     <li>be composed via {@code concatWith}</li>
         *     <li>perform side-effect-only processing via {@code ignoreEntries()}</li>
         *     <li>defer emission until completion</li>
         * </ul>
         * <p>
         * As a result, this operation is not a simple "fetch first element" operation, but a
         * terminal stream execution that observes the first entry while ensuring complete
         * stream traversal.
         *
         * @return A {@link CompletableFuture} completed with the first observed {@link Entry},
         * {@code null} if none were produced, or completed exceptionally if the stream fails.
         * @throws UnsupportedOperationException if this stream is unbounded
         */
        default CompletableFuture<@Nullable Entry<M>> asCompletableFuture() {

            /*
             * NOTE: We intentionally use full reduce-based consumption instead of a single next()
             * because the stream may be composed (concatWith), side-effecting (onNext), or
             * value-suppressing (ignoreEntries). This ensures correct lifecycle execution
             * regardless of stream transformations.
             */
            return reduce(null, (accumulator, entry) -> accumulator != null ? accumulator : entry);
        }
    }

    /**
     * A {@code MessageStream} implementation that completes normally or with an error without returning any elements.
     * <p>
     * Any operations that would {@link #map(Function)} or {@link #reduce(Object, BiFunction)} the stream will do
     * nothing at all for an empty {@code MessageStream}.
     *
     * @param <M> The type of {@link Message} for the empty {@link Entry} of this stream.
     * @author Allard Buijze
     * @author Mateusz Nowak
     * @author Mitchell Herrijgers
     * @author Steven van Beelen
     * @see #empty()
     * @see #failed(Throwable)
     * @since 5.0.0
     */
    interface Empty<M extends Message> extends Single<M> {

        @Override
        default Empty<M> first() {
            return this;
        }

        @Override
        default <RM extends Message> Empty<RM> map(Function<Entry<M>, Entry<RM>> mapper) {
            return cast();
        }

        @Override
        default <RM extends Message> Empty<RM> mapMessage(Function<M, RM> mapper) {
            return cast();
        }

        @Override
        default Empty<M> onNext(Consumer<Entry<M>> onNext) {
            // Empty streams can never have their onNext called.
            return this;
        }

        @SuppressWarnings("unchecked")
        @Override
        default MessageStream<M> concatWith(MessageStream<? extends M> other) {
            return (MessageStream<M>) other;
        }

        @Override
        default Empty<M> onComplete(Runnable completeHandler) {
            return new CompletionCallbackMessageStream.Empty<>(this, completeHandler);
        }

        @SuppressWarnings("unchecked")
        @Override
        default <T extends Message> Empty<T> cast() {
            return (Empty<T>) this;
        }
    }
}
