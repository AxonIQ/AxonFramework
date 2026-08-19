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

import java.util.Objects;
import java.util.function.Function;

/**
 * Implementation of the {@link MessageStream} that maps each {@link Entry entry} from an outer stream to
 * a new inner {@link MessageStream} using a mapper function, then concatenates the entries of all inner streams in
 * order.
 * <p>
 * Each outer entry produces exactly one inner stream. Entries of successive inner streams are emitted in the order the
 * outer entries appear. The next inner stream is not started until the current one completes. If any inner stream
 * completes with an error, that error is propagated and no further entries are emitted.
 * <p>
 * This is the {@code MessageStream} equivalent of {@link java.util.stream.Stream#flatMap(Function)}.
 *
 * @param <M> the type of {@link Message} carried by the outer stream's entries
 * @param <N> the type of {@link Message} carried by the inner streams' entries and this stream's entries
 * @author John Hendrikx
 * @since 5.2.0
 */
class FlatMappedMessageStream<M extends Message, N extends Message> extends AbstractMessageStream<N> {

    private final MessageStream<M> outer;
    private final Function<? super Entry<M>, ? extends MessageStream<? extends N>> mapper;

    @Nullable
    private MessageStream<? extends N> inner;

    /**
     * Constructs a {@link MessageStream} that maps each entry from {@code outer} to an inner stream using
     * {@code mapper}, emitting all inner entries in sequence.
     *
     * @param outer  the outer stream whose entries are mapped to inner streams, cannot be {@code null}
     * @param mapper the function mapping each outer {@link Entry} to a {@link MessageStream}, cannot be {@code null}
     * @throws NullPointerException when any argument is {@code null}
     */
    FlatMappedMessageStream(MessageStream<M> outer,
                            Function<? super Entry<M>, ? extends MessageStream<? extends N>> mapper) {
        this.outer = Objects.requireNonNull(outer, "The outer parameter must not be null.");
        this.mapper = Objects.requireNonNull(mapper, "The mapper parameter must not be null.");

        initialize(outer.error().map(FetchResult::<Entry<N>>error).orElse(FetchResult.notReady()));

        outer.setCallback(this::signalProgress);
    }

    @Override
    protected FetchResult<Entry<N>> fetchNext() {
        for (;;) {
            if (inner != null) {
                FetchResult<Entry<N>> innerResult = FetchResult.of(inner.cast());

                switch (innerResult) {
                    case FetchResult.Completed() -> {
                        inner.setCallback(() -> {});
                        inner.close();
                        inner = null;
                    }
                    default -> {
                        return innerResult;
                    }
                }
            }

            Entry<M> outerEntry = outer.next().orElse(null);

            if (outerEntry == null) {
                return outer.error()
                    .map(FetchResult::<Entry<N>>error)
                    .orElse(outer.isCompleted() ? FetchResult.completed() : FetchResult.notReady());
            }

            inner = mapper.apply(outerEntry);

            inner.setCallback(this::signalProgress);
        }
    }

    @Override
    protected void onCompleted() {
        if (inner != null) {
            inner.close();
        }
        outer.close();
    }

    @Override
    protected String describeDelegates() {
        return inner == null ? outer.toString() : outer + ", *" + inner;
    }
}
