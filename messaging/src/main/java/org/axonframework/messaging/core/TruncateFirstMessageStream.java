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

/**
 * Implementation of the {@link MessageStream} that truncates all {@link Entry entries} of the {@code delegate} stream
 * except for the first entry.
 * <p>
 * This allows users to define a {@code MessageStream} of any type and force it to a
 * {@link MessageStream.Single} stream instance.
 *
 * @param <M> The type of {@link Message} contained in the singular {@link Entry} of this stream.
 * @author Allard Buijze
 * @author John Hendrikx
 * @since 5.0.0
 */
class TruncateFirstMessageStream<M extends Message>
        extends AbstractMessageStream<M>
        implements MessageStream.Single<M> {

    private final MessageStream<M> delegate;

    private boolean consumed;

    /**
     * Constructs the DelegatingMessageStream with given {@code delegate} to receive calls.
     *
     * @param delegate The instance to delegate calls to.
     */
    public TruncateFirstMessageStream(MessageStream<M> delegate) {
        this.delegate = delegate;

        delegate.setCallback(this::signalProgress);
    }

    @Override
    protected synchronized FetchResult<Entry<M>> fetchNext() {
        if (consumed) {
            return FetchResult.completed();
        }

        FetchResult<Entry<M>> result = FetchResult.of(delegate);

        if (result instanceof FetchResult.Value) {
            consumed = true;
        }

        return result;
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
