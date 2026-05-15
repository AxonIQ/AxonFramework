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

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * An implementation of the {@link MessageStream} that invokes the given {@code filter} {@link Predicate} on each
 * {@link #next()} invocation to check if it should be filtered, yes or no.
 *
 * @param <M> The type of {@link Message} contained in the {@link Entry entries} of this stream.
 * @author Allard Buijze
 * @author Steven van Beelen
 * @author John Hendrikx
 * @since 5.0.0
 */
class FilteringMessageStream<M extends Message> extends AbstractMessageStream<M> {

    private final Predicate<Entry<M>> filter;
    private final MessageStream<M> delegate;

    /**
     * Construct a {@link MessageStream stream} that invokes the given {@code filter} {@link Predicate} each time a new
     * {@link Entry entry} is consumed by the given {@code delegate}.
     *
     * @param delegate The delegate {@link MessageStream stream} from which each consumed {@link Entry entry} is given
     *                 to the {@code onNext} {@link Consumer}.
     * @param filter   The {@link MessageStream.Entry entry} filter that will validate if the {@link #next()} invocation
     *                 should return the entry, yes or no.
     */
    FilteringMessageStream(MessageStream<M> delegate, Predicate<Entry<M>> filter) {
        this.delegate = delegate;
        this.filter = filter;

        initialize(delegate.error().map(FetchResult::<Entry<M>>error).orElse(FetchResult.notReady()));

        delegate.setCallback(this::signalProgress);
    }

    @Override
    protected FetchResult<Entry<M>> fetchNext() {
        Optional<Entry<M>> result = delegate.next();

        while (result.isPresent() && !filter.test(result.get())) {
            result = delegate.next();
        }

        if (delegate.error().isPresent()) {
            return FetchResult.error(delegate.error().orElseThrow());
        }

        return result.map(FetchResult::of)
            .orElseGet(() -> delegate.isCompleted() ? FetchResult.completed() : FetchResult.notReady());
    }

    @Override
    protected final void onCompleted() {
        delegate.close();
    }

    /**
     * Extension of the {@code FilteringMessageStream} that filters the entry of a single-value stream. This allows the
     * wrapped stream to also implement {@link MessageStream.Single}.
     *
     * @param <M> The type of {@link Message} contained in the {@link Entry entries} of this stream.
     */
    static class Single<M extends Message> extends FilteringMessageStream<M> implements MessageStream.Single<M> {

        /**
         * Construct a {@link MessageStream stream} filtering only the first {@link Entry} of the given
         * {@code delegate MessageStream}.
         *
         * @param delegate The {@link MessageStream stream} from which only the first {@link Entry} is mapped with the
         *                 given {@code mapper}.
         * @param filter   The {@link Predicate} filtering the first {@link Entry} from the given {@code delegate}.
         */
        Single(MessageStream.Single<M> delegate, Predicate<Entry<M>> filter) {
            super(delegate, filter);
        }
    }

    @Override
    protected String describeDelegates() {
        return delegate.toString();
    }
}
