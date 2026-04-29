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
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.publisher.Flux;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link MessageStream} implementation using a {@link Flux} as the source for {@link Entry entries}.
 *
 * @param <M> The type of {@link Message} contained in the {@link Entry entries} of this stream.
 * @author Allard Buijze
 * @author Steven van Beelen
 * @author John Hendrikx
 * @since 5.0.0
 */
class FluxMessageStream<M extends Message> extends AbstractMessageStream<M> {

    private final Flux<Entry<M>> source;
    private final BlockingQueue<FetchResult<Entry<M>>> peeked = new LinkedBlockingQueue<>(5);
    private final AtomicReference<@Nullable Subscription> subscription = new AtomicReference<>();
    private final AtomicBoolean subscribed = new AtomicBoolean(false);

    /**
     * Indicates that the producer side will no longer produce new elements. The
     * consumer side may still consume elements that are buffered.
     */
    private final AtomicBoolean sealed = new AtomicBoolean(false);

    /**
     * Constructs a {@link MessageStream stream} using the given {@code source} to provide the {@link Entry entries}.
     *
     * @param source The {@link Flux} providing the {@link Entry entries} for this {@link MessageStream stream}.
     */
    FluxMessageStream(Flux<Entry<M>> source) {
        this.source = source;
    }

    @Override
    protected FetchResult<Entry<M>> fetchNext() {
        if (subscribed.compareAndSet(false, true)) {
            subscribeToSource();
        }

        FetchResult<Entry<M>> next = peeked.poll();

        if (next == null) {
            return sealed.get() ? FetchResult.completed() : FetchResult.notReady();
        }

        if (next instanceof FetchResult.Value) {
            subscription.get().request(1);
        }

        return next;
    }

    @Override
    protected final void onCompleted() {
        seal();
    }

    private void subscribeToSource() {
        source.subscribe(new Subscriber<>() {

            @Override
            public void onSubscribe(Subscription s) {
                subscription.set(s);
                s.request(1);
            }

            @Override
            public void onNext(Entry<M> entry) {
                peeked.add(FetchResult.of(entry));

                signalProgress();

                // If the signal triggered an error (in the callback), clear buffered entries to ensure immediate error propagation
                if (error().isPresent()) {
                    peeked.clear();
                }
            }

            @Override
            public void onError(Throwable t) {
                peeked.add(FetchResult.error(t));

                seal();
                signalProgress();
            }

            @Override
            public void onComplete() {

                /*
                 * When the producer completes, do not close the stream directly (this is a consumer
                 * decision). Instead only seal the stream, which effectively allows the consumer
                 * to consume any remaining buffered elements before the stream transitions to
                 * completed.
                 *
                 * Progress is signaled as the stream may have been awaiting data when the producer
                 * completion signal arrived.
                 */

                seal();
                signalProgress();
            }
        });
    }

    private void seal() {
        sealed.set(true);
        Subscription s = subscription.get();
        if (s != null) {
            s.cancel();
        }
    }
}
