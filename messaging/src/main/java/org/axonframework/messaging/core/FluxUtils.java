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
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Utility methods to work with Project Reactor's {@link Flux fluxes}.
 *
 * @author John Hendrikx
 * @since 5.0.0
 */
public final class FluxUtils {

    private FluxUtils() {
    }

    /**
     * Creates a Flux containing the {@link MessageStream.Entry entries} provided by the given {@code source}. Note that
     * multiple invocations of this method on the same {@code source}, or otherwise any components consuming entries
     * from the given {@code source} will cause entries to be consumed by only one of the fluxes or competing
     * consumers.
     *
     * @param source The MessageStream providing the elements.
     * @param <M>    The type of Message returned by the source.
     * @return A Flux with the elements provided by the source.
     */
    public static <M extends Message> Flux<MessageStream.Entry<M>> of(MessageStream<? extends M> source) {
        return Flux.create(emitter -> {
            FluxStreamAdapter<M> fluxTask = new FluxStreamAdapter<>(source, emitter);
            emitter.onRequest(i -> fluxTask.process());
            emitter.onCancel(source::close);
            source.setCallback(fluxTask::process);
        });
    }

    /**
     * Create a stream that provides the {@link Message Messages} returned by the given {@code flux}, automatically
     * wrapped in an {@link Entry}.
     *
     * @param flux The {@link Flux} providing the {@link Message Messages} to stream.
     * @param <M>  The type of {@link Message} contained in the {@link Entry entries} of this stream.
     * @return A stream of {@link Entry entries} that returns the {@link Message Messages} provided by the given
     * {@code flux}.
     */
    public static <M extends Message> MessageStream<M> asMessageStream(Flux<M> flux) {
        return asMessageStream(flux, message -> Context.empty());
    }

    /**
     * Create a stream that provides the {@link Message Messages} returned by the given {@code flux}, automatically
     * wrapped in an {@link Entry} with the resulting {@link Context} from the {@code contextSupplier}.
     *
     * @param flux            The {@link Flux} providing the {@link Message Messages} to stream.
     * @param contextSupplier A {@link Function} ingesting each {@link Message} from the given {@code flux} returning
     *                        the {@link Context} to set for the {@link Entry} the {@code Message} is wrapped in.
     * @param <M>             The type of {@link Message} contained in the {@link Entry entries} of this stream.
     * @return A stream of {@link Entry entries} that returns the {@link Message Messages} provided by the given
     * {@code flux} with a {@link Context} provided by the {@code contextSupplier}.
     */
    public static <M extends Message> MessageStream<M> asMessageStream(Flux<M> flux,
                                                                       Function<M, Context> contextSupplier) {
        return new FluxMessageStream<>(flux.map(message -> new SimpleEntry<>(message, contextSupplier.apply(message))));
    }

    /**
     * Converts a {@link Supplier} of {@link MessageStream} into a reactor-core {@link Publisher}. The provided
     * {@code stream} will supply the {@code MessageStream}, and its entries will be transformed into a reactive stream
     * of messages.
     *
     * @param <M>    The type of {@link Message} contained in the {@link MessageStream}.
     * @param stream A {@link Supplier} that provides a {@link MessageStream} of messages to be published as a reactive
     *               stream.
     * @return A {@link Publisher} emitting the messages from the provided {@link MessageStream}.
     */
    public static <M extends Message> Publisher<M> streamToPublisher(Supplier<MessageStream<M>> stream) {
        return Mono.fromSupplier(stream)
                   .flatMapMany(FluxUtils::of)
                   .map(MessageStream.Entry::message);
    }

    static class FluxStreamAdapter<M extends Message> {

        private final AtomicBoolean processingGate = new AtomicBoolean(false);
        private final MessageStream<M> source;
        private final FluxSink<MessageStream.Entry<M>> emitter;

        @SuppressWarnings("unchecked")
        public FluxStreamAdapter(MessageStream<? extends M> source, FluxSink<MessageStream.Entry<M>> emitter) {
            this.source = (MessageStream<M>) source;
            this.emitter = emitter;
        }

        public void process() {
            if (!processingGate.getAndSet(true)) {
                try {
                    long remaining = emitter.requestedFromDownstream();

                    while (remaining-- > 0 && source.hasNextAvailable() && !emitter.isCancelled()) {
                        source.next().ifPresent(emitter::next);
                    }

                    if (source.isCompleted()) {
                        source.error().ifPresentOrElse(emitter::error, emitter::complete);
                    }
                } catch (Exception e) {
                    emitter.error(e);
                    source.close();
                } finally {
                    processingGate.set(false);
                }
            }
        }
    }
}
