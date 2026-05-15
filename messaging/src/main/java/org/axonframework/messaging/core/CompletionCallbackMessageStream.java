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

import org.axonframework.messaging.core.MessageStream.Entry;

/**
 * Implementation of the {@link MessageStream} that invokes the given {@code completeHandler} once the
 * {@code delegate MessageStream} completes.
 *
 * @param <M> The type of {@link Message} contained in the {@link Entry entries} of this stream.
 * @author Allard Buijze
 * @author Steven van Beelen
 * @author John Hendrikx
 * @since 5.0.0
 */
class CompletionCallbackMessageStream<M extends Message> extends AbstractMessageStream<M> {

    private final Runnable completeHandler;
    private final MessageStream<M> delegate;

    /**
     * Construct a {@link MessageStream stream} invoking the given {@code completeHandler} when the given
     * {@code delegate} completes.
     *
     * @param delegate        The {@link MessageStream stream} that once completed results in the invocation of the
     *                        given {@code completeHandler}.
     * @param completeHandler The {@link Runnable} to invoke when the given {@code delegate} completes.
     */
    CompletionCallbackMessageStream(MessageStream<M> delegate, Runnable completeHandler) {
        this.delegate = delegate;
        this.completeHandler = completeHandler;

        if (delegate.isCompleted()) {
            initialize(delegate.error().map(FetchResult::<Entry<M>>error).orElse(FetchResult.completed()));
        }

        delegate.setCallback(this::signalProgress);
    }

    @Override
    protected FetchResult<Entry<M>> fetchNext() {
        return FetchResult.of(delegate);
    }

    @Override
    protected void onCompleted() {
        delegate.close();

        if (error().isEmpty()) {
            completeHandler.run();  // may throw exception, that's allowed
        }
    }

    @Override
    protected String describeDelegates() {
        return delegate.toString();
    }

    /**
     * A {@link CompletionCallbackMessageStream} implementation completing on only the first {@link MessageStream.Entry}
     * of this stream.
     *
     * @param <M> The type of {@link Message} contained in the {@link Entry} of this stream.
     */
    static class Single<M extends Message> extends CompletionCallbackMessageStream<M>
            implements MessageStream.Single<M> {

        /**
         * Construct a {@link MessageStream stream} invoking the given {@code completeHandler} when the given
         * {@code delegate} completes.
         *
         * @param delegate        The {@link MessageStream stream} that once completed results in the invocation of the
         *                        given {@code completeHandler}.
         * @param completeHandler The {@link Runnable} to invoke when the given {@code delegate} completes.
         */
        Single(MessageStream.Single<M> delegate, Runnable completeHandler) {
            super(delegate, completeHandler);
        }
    }

    /**
     * A {@link CompletionCallbackMessageStream} implementation completing on no {@link MessageStream.Entry} of this
     * stream.
     *
     * @param <M> The type of {@link Message} for the empty {@link Entry} of this stream.
     */
    static class Empty<M extends Message> extends CompletionCallbackMessageStream<M>
            implements MessageStream.Empty<M> {

        /**
         * Construct a {@link MessageStream stream} invoking the given {@code completeHandler} when the given
         * {@code delegate} completes.
         *
         * @param delegate        The {@link MessageStream stream} that once completed results in the invocation of the
         *                        given {@code completeHandler}.
         * @param completeHandler The {@link Runnable} to invoke when the given {@code delegate} completes.
         */
        Empty(MessageStream.Empty<M> delegate, Runnable completeHandler) {
            super(delegate, completeHandler);
        }
    }
}
