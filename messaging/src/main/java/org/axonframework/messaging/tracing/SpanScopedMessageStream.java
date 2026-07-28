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

package org.axonframework.messaging.tracing;

import org.axonframework.common.annotation.Internal;
import org.axonframework.messaging.core.AbstractMessageStream;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;

import java.util.Objects;

/**
 * {@link MessageStream} decorator that re-enters a span's {@link SpanScope} via
 * {@link SpanScope#within(java.util.function.Supplier)} around every pull of the delegate stream.
 * <p>
 * A branch-scoped span's initial scope window covers the synchronous invocation of the operation (see
 * {@link Span#branchStream}), but parts of the operation may execute only when its result stream is <em>drained</em> --
 * most notably a reactive handler returning a {@code Flux}, which is subscribed lazily on the stream's first pull.
 * Re-entering the scope around every pull lets provider-specific context-capture mechanisms observe the operation's
 * span when lazy work starts and restore it around downstream callbacks.
 * <p>
 * The wrapped scope's lifecycle is not managed here: closing rides on the composition in
 * {@link Span#branchStream(
 * org.axonframework.messaging.core.unitofwork.ProcessingContext, java.util.function.Function)}.
 *
 * @param <M> the type of {@link Message} carried by the stream
 * @author Mateusz Nowak
 * @since 5.3.0
 */
@Internal
final class SpanScopedMessageStream<M extends Message> extends AbstractMessageStream<M> {

    private final MessageStream<M> delegate;
    private final SpanScope scope;

    /**
     * Constructs a {@link MessageStream stream} pulling from the given {@code delegate} within the given
     * {@code scope}.
     *
     * @param delegate the stream to pull from within the scope
     * @param scope    the span scope to re-enter around every pull
     */
    SpanScopedMessageStream(MessageStream<M> delegate, SpanScope scope) {
        this.delegate = Objects.requireNonNull(delegate, "The delegate may not be null.");
        this.scope = Objects.requireNonNull(scope, "The scope may not be null.");

        if (delegate.isCompleted()) {
            initialize(delegate.error().map(FetchResult::<Entry<M>>error).orElse(FetchResult.completed()));
        }

        delegate.setCallback(this::signalProgress);
    }

    @Override
    protected FetchResult<Entry<M>> fetchNext() {
        return scope.within(() -> FetchResult.of(delegate));
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
