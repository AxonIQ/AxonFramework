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

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Implementation of the {@link MessageStream} that maps each {@link Entry entry} to zero or more
 * output entries using a {@link BiConsumer} mapper.
 * <p>
 * For each entry from the delegate stream, the mapper is invoked with the entry and a {@link Consumer} it can call
 * zero or more times to emit output entries. The emitted entries are buffered and returned one at a time from
 * subsequent {@link MessageStream#next()} calls.
 * <p>
 * This is the {@code MessageStream} equivalent of {@link java.util.stream.Stream#mapMulti(BiConsumer)}. Unlike
 * {@link FlatMappedMessageStream}, the mapper is invoked synchronously: all output entries for a given input are
 * pushed to the consumer during the same {@code fetchNext} call. This makes it more efficient for the common
 * 1-to-1 case because no inner stream object is allocated per entry.
 *
 * @param <M> the type of {@link Message} carried by the delegate stream's entries
 * @param <N> the type of {@link Message} carried by this stream's output entries
 * @author John Hendrikx
 * @since 5.2.0
 */
class MapMultiMessageStream<M extends Message, N extends Message> extends AbstractMessageStream<N> {

    private final MessageStream<M> delegate;
    private final BiConsumer<? super Entry<M>, ? super Consumer<Entry<N>>> mapper;
    private final ArrayDeque<Entry<N>> buffer = new ArrayDeque<>();

    /**
     * Constructs a {@link MessageStream} that maps each entry from {@code delegate} to zero or more output entries
     * using {@code mapper}.
     *
     * @param delegate the stream whose entries are expanded by the mapper, cannot be {@code null}
     * @param mapper   a {@link BiConsumer} receiving each input {@link Entry} and a {@link Consumer}
     *                 to push zero or more output entries to, cannot be {@code null}
     * @throws NullPointerException when any argument is {@code null}
     */
    MapMultiMessageStream(MessageStream<M> delegate,
                          BiConsumer<? super Entry<M>, ? super Consumer<Entry<N>>> mapper) {
        this.delegate = Objects.requireNonNull(delegate, "The delegate parameter must not be null.");
        this.mapper = Objects.requireNonNull(mapper, "The mapper parameter must not be null.");

        initialize(delegate.error().map(FetchResult::<Entry<N>>error).orElse(FetchResult.notReady()));

        delegate.setCallback(this::signalProgress);
    }

    @Override
    protected FetchResult<Entry<N>> fetchNext() {
        for (;;) {

            /*
             * First drain any messages from the buffer that were added from
             * a previous map operation (which may be none):
             */

            if (!buffer.isEmpty()) {
                return FetchResult.of(buffer.poll());
            }

            /*
             * Buffer is empty now, if the main stream has messages, apply the map
             * operation to its next message to fill the buffer:
             */

            MessageStream.Entry<M> next = delegate.next().orElse(null);

            if (next == null) {
                return delegate.error()
                    .map(FetchResult::<Entry<N>>error)
                    .orElse(delegate.isCompleted() ? FetchResult.completed() : FetchResult.notReady());
            }

            /*
             * Fill buffer with mapper:
             */

            mapper.accept(next, (Consumer<Entry<N>>) buffer::add);
        }
    }

    @Override
    protected void onCompleted() {
        delegate.close();
    }

    @Override
    protected String describeDelegates() {
        return delegate.toString();
    }
}
