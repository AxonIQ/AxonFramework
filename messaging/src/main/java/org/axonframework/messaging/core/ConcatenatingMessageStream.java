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

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Implementation of the {@link MessageStream} that concatenates two {@code MessageStreams}.
 * <p>
 * Will only start streaming {@link Entry entries} from the {@code second MessageStream} when the
 * {@code first MessageStream} completes successfully.
 *
 * @param <M> The type of {@link Message} contained in the {@link Entry entries} of this stream.
 * @author Allard Buijze
 * @author Steven van Beelen
 * @author John Hendrikx
 * @since 5.0.0
 */
class ConcatenatingMessageStream<M extends Message> extends AbstractMessageStream<M> {

    /*
     * Concurrency model:
     *
     * This class assumes concurrent interaction between:
     * - a consumer thread invoking fetchNext() and other message stream methods
     * - callback threads from the currently active stream invoking signalProgress() indirectly
     *
     * All mutable state (streams, active, and stream lifecycle transitions) is guarded by
     * this instance's monitor (using synchronized methods).
     *
     * Key properties:
     * - Stream switching (closing the previous stream, selecting the next, and registering a callback)
     *   is performed atomically under the same lock.
     * - The active stream and streams collection are always consistent with each other.
     * - Callbacks may arrive concurrently, but any interaction with state is serialized through
     *   the same monitor.
     *
     * Given the expected low contention and short critical sections, synchronized provides a
     * simple and reliable way to enforce these guarantees.
     */

    private final List<MessageStream<M>> streams;  // only access under synchronization

    private MessageStream<M> active;  // only access under synchronization

    /**
     * Construct a {@link MessageStream stream} that initially consume from the {@code first MessageStream}, followed by
     * the {@code second} if the {@code first MessageStream} completes successfully
     *
     * @param first  The initial {@link MessageStream stream} to consume entries from.
     * @param second The second {@link MessageStream stream} to start consuming from once the {@code first} stream
     *               completes successfully.
     */
    @SuppressWarnings("unchecked")
    ConcatenatingMessageStream(MessageStream<? extends M> first,
                               MessageStream<? extends M> second) {
        this.streams = new ArrayList<>(List.of(
            (MessageStream<M>) first,
            (MessageStream<M>) second
        ));

        switchStream();
    }

    @Override
    protected synchronized FetchResult<Entry<M>> fetchNext() {
        do {
            switch (FetchResult.of(active)) {
                case FetchResult.Completed(): continue;
                case FetchResult<Entry<M>> result: return result;
            }
        } while(switchStream());

        return FetchResult.completed();
    }

    private boolean switchStream() {
        if (active != null) {
            active.setCallback(() -> {});
            active.close();
        }

        if (streams.isEmpty()) {
            return false;
        }

        this.active = streams.removeFirst();

        active.setCallback(this::signalProgress);

        return true;
    }

    @Override
    protected synchronized void onCompleted() {
        active.close();
        streams.forEach(MessageStream::close);
    }

    @Override
    protected String describeDelegates() {
        return Stream.concat(Stream.of(active), streams.stream()).map(Object::toString).collect(Collectors.joining(", ", "*", ""));
    }
}
