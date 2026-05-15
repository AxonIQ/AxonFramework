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

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MessageStream implementation that invokes the given {@code closeHandler} once the stream is closed. A stream is
 * considered closed when a consumer explicitly calls {@link #close()} or when the stream is completed.
 * <p>
 * Note that when close is called on the delegate, or when the client does not attempt to consume this stream, the
 * close handler may never be invoked, even though the stream is completed.
 *
 * @param <M> The type of Message handled by this MessageStream.
 * @author John Hendrikx
 * @since 5.0.0
 */
public class CloseCallbackMessageStream<M extends Message> extends AbstractMessageStream<M> {

    private final Runnable closeHandler;
    private final AtomicBoolean invoked = new AtomicBoolean(false);
    private final MessageStream<M> delegate;

    /**
     * Creates an instance of the CloseCallbackMessageStream, calling the given {@code closeHandler} once this
     * stream is closed, or the given {@code delegate} completes.
     *
     * @param delegate     The MessageStream to wrap with the close handler invocation logic
     * @param closeHandler The handler to invoke when the stream is closed or completed
     */
    public CloseCallbackMessageStream(MessageStream<M> delegate, Runnable closeHandler) {
        this.delegate = delegate;
        this.closeHandler = Objects.requireNonNull(closeHandler, "Close handler may not be null.");

        if (delegate.isCompleted()) {
            initialize(delegate.error().map(FetchResult::<Entry<M>>error).orElse(FetchResult.completed()));
        }

        delegate.setCallback(this::signalProgress);
    }

    /**
     * Creates an instance of the CloseCallbackMessageStream, calling the given {@code closeHandler} once this
     * stream is closed, or the given {@code delegate} completes.
     *
     * @param delegate     The MessageStream to wrap with the close handler invocation logic
     * @param closeHandler The handler to invoke when the stream is closed or completed
     */
    static <M extends Message> MessageStream.Single<M> single(Single<M> delegate, Runnable closeHandler) {
        return new CloseCallbackMessageStream<>(delegate, closeHandler).first();
    }

    /**
     * Creates an instance of the CloseCallbackMessageStream, calling the given {@code closeHandler} once this
     * stream is closed, or the given {@code delegate} completes.
     *
     * @param delegate     The MessageStream to wrap with the close handler invocation logic
     * @param closeHandler The handler to invoke when the stream is closed or completed
     */
    static <M extends Message> MessageStream.Empty<M> empty(Empty<M> delegate, Runnable closeHandler) {
        return new CloseCallbackMessageStream<>(delegate, closeHandler).ignoreEntries();
    }

    @Override
    protected FetchResult<Entry<M>> fetchNext() {
        return FetchResult.of(delegate);
    }

    @Override
    protected void onCompleted() {
        if (!invoked.getAndSet(true)) {
            closeHandler.run();
        }
    }

    @Override
    public String describeDelegates() {
        return delegate.toString();
    }
}
