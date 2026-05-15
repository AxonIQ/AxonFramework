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

import java.util.Iterator;
import java.util.List;

/**
 * A {@link MessageStream} implementation using an {@link Iterator} as the source for {@link Entry entries}.
 *
 * @param <M> The type of {@link Message} contained in the {@link Entry entries} of this stream.
 * @author Allard Buijze
 * @author Steven van Beelen
 * @author John Hendrikx
 * @since 5.0.0
 */
class IteratorMessageStream<M extends Message> extends AbstractMessageStream<M> {

    private Iterator<Entry<M>> source;

    /**
     * Constructs a {@link MessageStream stream} using the given {@code source} to provide the {@link Entry entries}.
     *
     * @param source The {@link Iterator} providing the {@link Entry entries} for this {@link MessageStream stream}.
     */
    IteratorMessageStream(Iterator<Entry<M>> source) {
        this.source = source;
    }

    @Override
    protected FetchResult<Entry<M>> fetchNext() {
        return source.hasNext() ? FetchResult.of(source.next()) : FetchResult.completed();
    }

    @Override
    protected final void onCompleted() {
        this.source = List.<Entry<M>>of().iterator();

        signalProgress();
    }
}
