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

import java.util.function.Function;

import static org.axonframework.messaging.core.MessageStreamUtils.NO_OP_CALLBACK;

/**
 * Implementation of the {@link MessageStream} that when the stream completes exceptionally will continue on a
 * {@code MessageStream} returned by the given {@code onError} {@link Function}.
 *
 * @param <M> The type of {@link Message} contained in the {@link Entry entries} of this stream.
 * @author Allard Buijze
 * @author Steven van Beelen
 * @author John Hendrikx
 * @since 5.0.0
 */
class OnErrorContinueMessageStream<M extends Message> extends AbstractMessageStream<M> {

    private final Function<Throwable, MessageStream<? extends M>> onError;

    private MessageStream<M> current;
    private boolean switchedToContinuation;

    /**
     * Construct an {@link MessageStream stream} that will proceed on the resulting {@code MessageStream} from the given
     * {@code onError} when the {@code delegate} completes exceptionally
     *
     * @param delegate The delegate {@link MessageStream stream} to proceed from with the result of {@code onError}
     *                 <em>if</em> it completes exceptionally.
     * @param onError  A {@link Function} providing the replacement {@link MessageStream stream} to continue from if the
     *                 given {@code delegate} completes exceptionally.
     */
    OnErrorContinueMessageStream(MessageStream<M> delegate,
                                 Function<Throwable, MessageStream<? extends M>> onError) {
        this.onError = onError;
        this.current = delegate;

        delegate.setCallback(this::signalProgress);
    }

    @Override
    protected synchronized FetchResult<Entry<M>> fetchNext() {
        do {
            FetchResult<Entry<M>> result = FetchResult.of(current);

            if (switchedToContinuation || !(result instanceof FetchResult.Error)) {
                return result;
            }
        } while (switchToContinuation());

        return FetchResult.completed();
    }

    private boolean switchToContinuation() {
        switchedToContinuation = true;

        @SuppressWarnings("unchecked")
        MessageStream<M> continuation = (MessageStream<M>) onError.apply(current.error().get());

        current.setCallback(NO_OP_CALLBACK);
        current = continuation;
        current.setCallback(this::signalProgress);

        return true;
    }

    @Override
    protected synchronized void onCompleted() {
        current.close();
    }

    @Override
    protected String describeFlags() {
        return switchedToContinuation ? "ALT" : null;
    }

    @Override
    protected String describeDelegates() {
        return current.toString();
    }
}
