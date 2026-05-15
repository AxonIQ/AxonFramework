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

package org.axonframework.eventsourcing.eventstore;

import org.axonframework.common.Registration;
import org.axonframework.common.annotation.Internal;
import org.axonframework.messaging.core.AbstractMessageStream;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.eventhandling.EventMessage;

import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A {@link MessageStream} implementation that continuously fetches event messages from a configurable data source. This
 * stream has no defined end and will continue retrieving new batches of data as they become available.
 * <p>
 * The stream relies on externally provided functional strategies to control its behavior:
 * <ul>
 *     <li>A {@code fetcher} to obtain the next batch of elements. The fetcher owns all cursor/position
 *          state internally and is simply called each time more data is needed.</li>
 *     <li>A {@code converter} to transform fetched elements into {@link Entry} instances.</li>
 *     <li>A {@code callbackTracker} to manage callback registration for new data availability.</li>
 * </ul>
 * The stream supports lifecycle management through {@link #close()} and
 * callback registration via {@link #setCallback(Runnable)}.
 *
 * @param <E> the type of the raw elements returned by the fetcher before conversion to {@link EventMessage}s
 * @author John Hendrikx
 * @since 5.0.0
 */
@Internal
public final class ContinuousMessageStream<E> extends AbstractMessageStream<EventMessage> {

    private final Supplier<List<E>> fetcher;
    private final BiFunction<ContinuousMessageStream<?>, Runnable, Registration> callbackTracker;
    private final Function<E, Entry<EventMessage>> converter;

    private List<E> data = List.of();
    private Throwable error;
    private int position;  // position within data
    private Registration callbackRegistration;
    private boolean sealed;

    /**
     * Creates a new {@code ContinuousMessageStream} instance configured with the given strategies.
     *
     * @param fetcher         a supplier that returns the next batch of elements to emit. The fetcher is responsible
     *                        for tracking its own position; it is called repeatedly whenever the stream needs more data.
     *                        Must not return {@code null}, but may return an empty list to indicate no new data is currently available.
     * @param converter       a function converting each fetched element into an {@link Entry} containing an
     *                        {@link EventMessage}
     * @param callbackTracker a function that, given this stream and a callback {@link Runnable}, registers a listener
     *                        and returns a {@link Registration} allowing it to be canceled
     */
    public ContinuousMessageStream(
            Supplier<List<E>> fetcher,
            Function<E, Entry<EventMessage>> converter,
            BiFunction<ContinuousMessageStream<?>, Runnable, Registration> callbackTracker
    ) {
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.converter = Objects.requireNonNull(converter, "converter");
        this.callbackTracker = Objects.requireNonNull(callbackTracker, "callbackTracker");
    }

    @Override
    protected synchronized void onCompleted() {
        seal();
    }

    @Override
    protected synchronized FetchResult<Entry<EventMessage>> fetchNext() {
        if (callbackRegistration == null) {
            this.callbackRegistration = callbackTracker.apply(this, this::signalProgress);
        }

        if (position >= data.size()) {
            fetchMore();  // TODO #3854 - ContinuousMessageStream may block in its MessageStream::peek call (and methods that rely on it) which is not allowed

            if (sealed) {  // can happen if closed explicitely or because there was an error
                return error == null ? FetchResult.completed() : FetchResult.error(error);
            }

            if (data.isEmpty()) {
                return FetchResult.notReady();
            }
        }

        E element = data.get(position++);

        Entry<EventMessage> nextEntry = converter.apply(element);

        return FetchResult.of(nextEntry);
    }

    private void fetchMore() {
        if (!sealed) {
            try {
                this.data = fetcher.get();
                this.position = 0;
            }
            catch (Exception e) {
                error = e;

                seal();
                signalProgress();
            }
        }
    }

    private void seal() {
        if (!sealed) {
            sealed = true;

            if (callbackRegistration != null) {
                callbackRegistration.cancel();
            }
        }
    }
}