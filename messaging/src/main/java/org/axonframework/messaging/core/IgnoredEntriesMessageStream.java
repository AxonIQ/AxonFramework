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
 * Implementation of the {@link MessageStream} that ignores all {@link Entry entries} of the {@code delegate} stream and
 * returns an empty stream.
 * <p>
 * This allows users to define a {@code MessageStream} of any type and force it to a
 * {@link MessageStream.Empty} stream instance, effectively ignoring the results while
 * maintaining the processing of the stream.
 *
 * @param <M> The type of {@link Message} from the delegate stream that will be ignored.
 * @author Mateusz Nowak
 * @author John Hendrikx
 * @since 5.0.0
 */
class IgnoredEntriesMessageStream<M extends Message> extends AbstractMessageStream<Message>
        implements MessageStream.Empty<Message> {

    private final MessageStream<M> delegate;

    /**
     * Constructs the IgnoreMessageStream with given {@code delegate} to receive and ignore entries from.
     *
     * @param delegate The instance to delegate calls to.
     */
    IgnoredEntriesMessageStream(MessageStream<M> delegate) {
        this.delegate = delegate;

        delegate.setCallback(this::onDelegateProgress);

        if (delegate.isCompleted()) {
            initialize(delegate.error()
                .map(FetchResult::<Entry<Message>>error)
                .orElse(FetchResult.completed())
            );
        }
    }

    private void onDelegateProgress() {
        while (delegate.hasNextAvailable()) {
            delegate.next();
        }

        signalProgress();
    }

    @Override
    protected FetchResult<Entry<Message>> fetchNext() {
        if (delegate.isCompleted()) {
            return delegate.error()
                .map(FetchResult::<Entry<Message>>error)
                .orElse(FetchResult.completed());
        }

        return FetchResult.notReady();
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
