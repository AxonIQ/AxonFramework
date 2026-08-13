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

package org.axonframework.messaging.core.annotation;

import org.jspecify.annotations.Nullable;
import org.axonframework.common.annotation.Internal;
import org.axonframework.messaging.core.DelayedMessageStream;
import org.axonframework.messaging.core.FluxUtils;
import org.axonframework.messaging.core.GenericMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageHandler;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.MonoUtils;
import org.axonframework.common.util.ClasspathResolver;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Utility class that can resolve the result of any {@link MessageHandler}
 * into the expected corresponding {@link MessageStream}.
 * <p>
 * Whether a returned {@link Iterable} represents <em>several</em> results or <em>one</em> result that happens to be a
 * collection cannot be derived from the value itself. Callers therefore choose the cardinality up front:
 * {@link #resolveToStream(Object, MessageTypeResolver)} spreads an {@code Iterable} over as many
 * {@link Message Messages} as it holds elements, while {@link #resolveToSingleStream(Object, MessageTypeResolver)}
 * carries it as the {@link Message#payload()} of a single {@code Message}. Both unwrap asynchronous containers such as
 * {@link CompletableFuture} first, so that {@code List<T>} and {@code CompletableFuture<List<T>>} yield the same
 * cardinality.
 * <p>
 * This utility class currently has a drawback, which is that it only takes the "top level" type into account.
 * Differently put, if we receive a {@code Mono<Message>} as the given {@code result}, we will push that
 * {@code Message} through the given {@code typeResolver} and make it the {@link Message#payload()}.
 * <p>
 * This is a known limitation that will be supported in due time.
 *
 * @author Simon Zambrovski
 * @author Steven van Beelen
 * @since 5.0.0
 */
@Internal
public class MessageStreamResolverUtils {

    /**
     * Resolves the given {@code result} into a {@link MessageStream} that may carry several {@link Message Messages},
     * using the {@code typeResolver} when a {@code Message} is constructed to define the {@link MessageType}.
     * <p>
     * Is able to switch between {@link Optional}, {@link CompletableFuture}, {@link Iterable}, {@link Stream},
     * {@link Mono}, and {@link Flux}. An {@code Iterable} or {@code Stream} is spread over as many {@code Messages} as
     * it holds elements, also when wrapped in a {@code CompletableFuture} or {@code Optional}. If none of the above
     * apply, {@link MessageStream#just(Message)} will be used. If the given {@code result} is {@code null},
     * {@link MessageStream#empty()} is returned.
     * <p>
     * Use {@link #resolveToSingleStream(Object, MessageTypeResolver)} for handlers that produce exactly one result, as
     * those must carry a returned collection as a whole instead of spreading it.
     *
     * @param result       The result to map into a {@link MessageStream}.
     * @param typeResolver The {@code MessageTypeResolver} used to resolve the {@link MessageType} for
     *                     {@link Message Messages} that are held in the returned
     *                     {@link MessageStream}.
    * @param result       the result to map into a {@link MessageStream}
    * @param typeResolver the {@code MessageTypeResolver} used to resolve the {@link MessageType} for
    *                     {@link Message Messages} that are held in the returned
    *                     {@link MessageStream}
    * @return a {@code MessageStream} based on the given {@code result}
     */
    public static MessageStream<?> resolveToStream(@Nullable Object result,
                                                   MessageTypeResolver typeResolver) {
        return resolve(result, typeResolver, true);
    }

    /**
     * Resolves the given {@code result} into a {@link MessageStream} carrying at most one {@link Message}, using the
     * {@code typeResolver} when that {@code Message} is constructed to define the {@link MessageType}.
     * <p>
     * Behaves like {@link #resolveToStream(Object, MessageTypeResolver)}, except that a returned {@link Iterable}
     * becomes the {@link Message#payload()} of a single {@code Message} rather than being spread over one {@code
     * Message} per element. This suits handlers that produce exactly one result, such as
     * {@link org.axonframework.messaging.commandhandling.CommandHandler command handlers}, for which spreading would
     * mean silently discarding all but the first element.
     *
     * @param result       the result to map into a {@link MessageStream}
     * @param typeResolver the {@code MessageTypeResolver} used to resolve the {@link MessageType} for the
     *                     {@link Message} that is held in the returned {@link MessageStream}
     * @return a {@code MessageStream} based on the given {@code result}
     */
    public static MessageStream<?> resolveToSingleStream(@Nullable Object result,
                                                         MessageTypeResolver typeResolver) {
        return resolve(result, typeResolver, false);
    }

    private static MessageStream<?> resolve(@Nullable Object result,
                                            MessageTypeResolver typeResolver,
                                            boolean spreadIterables) {
        Objects.requireNonNull(typeResolver, "The Message Type Resolver must not be null.");
        if (result == null) {
            return MessageStream.empty();
        }

        // Handle Project Reactor types first with traditional if-statements
        if (ClasspathResolver.projectReactorOnClasspath()) {
            if (result instanceof Mono<?> mono) {
                return MonoUtils.asSingle(mono.map(r -> new GenericMessage(typeResolver.resolveOrThrow(r), r)));
            }
            if (result instanceof Flux<?> flux) {
                return FluxUtils.asMessageStream(flux.map(r -> new GenericMessage(typeResolver.resolveOrThrow(r), r)));
            }
        }

        // Handle standard types with pattern matching switch
        return switch (result) {
            case MessageStream<?> messageStream -> messageStream;
            case CompletableFuture<?> future -> DelayedMessageStream.create(
                    future.thenApply(r -> asMessageStream(resolve(r, typeResolver, spreadIterables)))
            );
            case Optional<?> optional when optional.isPresent() -> resolve(optional.get(),
                                                                           typeResolver,
                                                                           spreadIterables);
            case Optional<?> empty -> MessageStream.empty();
            case Iterable<?> iterable when spreadIterables -> MessageStream.fromStream(
                    StreamSupport.stream(iterable.spliterator(), false)
                                 .map(r -> new GenericMessage(typeResolver.resolveOrThrow(r), r))
            );
            case Stream<?> stream -> MessageStream.fromStream(
                    stream.map(r -> new GenericMessage(typeResolver.resolveOrThrow(r), r))
            );
            default -> MessageStream.just(new GenericMessage(typeResolver.resolveOrThrow(result), result));
        };
    }

    @SuppressWarnings("unchecked")
    private static MessageStream<Message> asMessageStream(MessageStream<?> stream) {
        return (MessageStream<Message>) stream;
    }

    private MessageStreamResolverUtils() {
        // Utility class
    }
}
