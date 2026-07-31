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

import org.axonframework.messaging.core.MessageStream.Single;
import reactor.core.publisher.Mono;

import java.util.function.Function;

/**
 * Utility methods to work with Project Reactor's {@link Mono monos}.
 *
 * @author John Hendrikx
 * @since 5.0.0
 */
public abstract class MonoUtils {

    private MonoUtils() {
    }

    /**
     * Create a stream that returns a single {@link MessageStream.Entry entry} wrapping the {@link Message} from the
     * given {@code mono}, once it completes.
     * <p>
     * The stream will contain at most a single entry. It may also contain no entries if the mono completes empty. The
     * stream will complete with an exception when the given {@code mono} completes exceptionally.
     * <p>
     * The given {@code mono} is subscribed with {@link Mono#contextCapture() ThreadLocal context capture}:
     * {@link Mono#toFuture()} subscribes with a context-less {@code CoreSubscriber}, so without an explicit capture
     * the mono's Reactor {@code Context} would always be empty, and thread-bound state present at subscription --
     * such as the tracing span and observation a message handler runs under -- could never reach the mono's operators
     * or context-reading instrumentation (for example Spring Boot's R2DBC observation) downstream.
     *
     * @param mono the {@link Mono} providing the {@link Message} to contain in the stream
     * @param <M>  the type of {@link Message} contained in the {@link MessageStream.Entry entries} of this stream
     * @return a stream containing at most one {@link MessageStream.Entry entry} from the given {@code mono}
     */
    public static <M extends Message> Single<M> asSingle(Mono<M> mono) {
        return MessageStream.fromFuture(mono.contextCapture().toFuture());
    }

    /**
     * Create a stream that returns a single {@link MessageStream.Entry entry} wrapping the {@link Message} from the
     * given {@code mono}, once it completes.
     * <p>
     * The automatically generated {@code Entry} will have the {@link Context} as given by the {@code contextSupplier}.
     * <p>
     * The stream will contain at most a single entry. It may also contain no entries if the mono completes empty. The
     * stream will complete with an exception when the given {@code mono} completes exceptionally.
     * <p>
     * The given {@code mono} is subscribed with {@link Mono#contextCapture() ThreadLocal context capture} -- see
     * {@link #asSingle(Mono)} for the rationale.
     *
     * @param mono            the {@link Mono} providing the {@link Message} to contain in the stream
     * @param contextSupplier a {@link Function} ingesting the {@link Message} from the given {@code mono} returning the
     *                        {@link Context} to set for the {@link MessageStream.Entry} the {@code Message} is wrapped
     *                        in
     * @param <M>             the type of {@link Message} contained in the {@link MessageStream.Entry entries} of this
     *                        stream
     * @return a stream containing at most one {@link MessageStream.Entry entry} from the given {@code mono}
     */
    public static <M extends Message> Single<M> asSingle(Mono<M> mono,
                                                         Function<M, Context> contextSupplier) {
        return MessageStream.fromFuture(mono.contextCapture().toFuture(), contextSupplier);
    }

}
