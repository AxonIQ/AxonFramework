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
package org.axonframework.messaging.queryhandling.gateway;

import org.jspecify.annotations.Nullable;
import org.axonframework.common.infra.DescribableComponent;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.queryhandling.QueryBus;
import org.axonframework.messaging.queryhandling.QueryMessage;
import org.axonframework.messaging.queryhandling.QueryResponseMessage;
import org.axonframework.messaging.queryhandling.SubscriptionQueryUpdateMessage;
import org.reactivestreams.Publisher;
import reactor.util.concurrent.Queues;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Interface towards the Query Handling components of an application.
 * <p>
 * This interface provides a friendlier API toward the {@link QueryBus}.
 *
 * @author Allard Buijze
 * @author Marc Gathier
 * @author Milan Savic
 * @author Steven van Beelen
 * @since 3.1.0
 */
public interface QueryGateway extends DescribableComponent {

    /**
     * Sends given {@code query} over the {@link QueryBus}, expecting a single response with the given
     * {@code responseType} from a single source.
     * <p>
     * Execution may be asynchronous, depending on the {@code QueryBus} implementation.
     * <p>
     * The given {@code query} is wrapped as the payload of the {@link QueryMessage} that is eventually posted on the
     * {@code QueryBus}, unless the {@code query} already implements {@link Message}. In that case, a
     * {@code QueryMessage} is constructed from that message's payload and
     * {@link Metadata}.
     *
     * @param query        the {@code query} to be sent
     * @param responseType a {@code Class} describing the desired response type
     * @param context      the processing context, if any, to dispatch the given {@code query} in
     * @param <R>          the generic type of the expected response
     * @return a {@code CompletableFuture} containing a single query response of type {@code responseType}
     */
    <R> CompletableFuture<R> query(Object query,
                                   Class<R> responseType,
                                   @Nullable ProcessingContext context);

    /**
     * Sends given {@code query} over the {@link QueryBus}, expecting a single response with the given
     * {@code responseType} from a single source.
     * <p>
     * Execution may be asynchronous, depending on the {@code QueryBus} implementation.
     * <p>
     * The given {@code query} is wrapped as the payload of the {@link QueryMessage} that is eventually posted on the
     * {@code QueryBus}, unless the {@code query} already implements {@link Message}. In that case, a
     * {@code QueryMessage} is constructed from that message's payload and
     * {@link Metadata}.
     *
     * @param query        the {@code query} to be sent
     * @param responseType a {@code Class} describing the desired response type
     * @param <R>          the generic type of the expected response
     * @return a {@code CompletableFuture} containing a single query response of type {@code responseType}
     * @see #query(Object, Class, ProcessingContext)
     */
    default <R> CompletableFuture<R> query(Object query,
                                           Class<R> responseType) {
        return query(query, responseType, null);
    }

    /**
     * Sends given {@code query} with the given {@code metadata} in the provided {@code context} (if available) over the
     * {@link QueryBus}, expecting a single response with the given {@code responseType} from a single source.
     * <p>
     * Execution may be asynchronous, depending on the {@code QueryBus} implementation.
     * <p>
     * The given {@code query} and {@code metadata} are wrapped as the payload of the {@link QueryMessage} that is
     * eventually posted on the {@code QueryBus}, unless the {@code query} already implements {@link Message}. In that
     * case, a {@code QueryMessage} is constructed from that message's payload and {@link Metadata}. The provided
     * {@code metadata} is attached afterward in this case.
     * <p>
     * Implementations must override this method to support attaching the given {@code metadata}; the default
     * implementation throws an {@link UnsupportedOperationException}.
     *
     * @param query        the {@code query} to be sent
     * @param responseType a {@code Class} describing the desired response type
     * @param metadata     the metadata to attach to the {@code query}
     * @param context      the processing context, if any, to dispatch the given {@code query} in
     * @param <R>          the generic type of the expected response
     * @return a {@code CompletableFuture} containing a single query response of type {@code responseType}
     * @since 5.3.0
     */
    default <R> CompletableFuture<R> query(Object query,
                                           Class<R> responseType,
                                           Metadata metadata,
                                           @Nullable ProcessingContext context) {
        throw new UnsupportedOperationException(
                "This QueryGateway does not support attaching metadata to a query."
        );
    }

    /**
     * Sends given {@code query} over the {@link QueryBus}, expecting multiple responses in the form of
     * {@code responseType} from a single source.
     * <p>
     * Execution may be asynchronous, depending on the {@code QueryBus} implementation.
     * <p>
     * The given {@code query} is wrapped as the payload of the {@link QueryMessage} that is eventually posted on the
     * {@code QueryBus}, unless the {@code query} already implements {@link Message}. In that case, a
     * {@code QueryMessage} is constructed from that message's payload and
     * {@link Metadata}.
     *
     * @param query        the {@code query} to be sent
     * @param responseType a {@code Class} describing the desired response type
     * @param context      the processing context, if any, to dispatch the given {@code query} in
     * @param <R>          the generic type of the expected response(s)
     * @return a {@code CompletableFuture} containing a list of query responses of type {@code responseType}
     */
    <R> CompletableFuture<List<R>> queryMany(Object query,
                                             Class<R> responseType,
                                             @Nullable ProcessingContext context);

    /**
     * Sends given {@code query} over the {@link QueryBus}, expecting multiple responses in the form of
     * {@code responseType} from a single source.
     * <p>
     * Execution may be asynchronous, depending on the {@code QueryBus} implementation.
     * <p>
     * The given {@code query} is wrapped as the payload of the {@link QueryMessage} that is eventually posted on the
     * {@code QueryBus}, unless the {@code query} already implements {@link Message}. In that case, a
     * {@code QueryMessage} is constructed from that message's payload and
     * {@link Metadata}.
     *
     * @param query        the {@code query} to be sent
     * @param responseType a {@code Class} describing the desired response type
     * @param <R>          the generic type of the expected response(s)
     * @return a {@code CompletableFuture} containing a list of query responses of type {@code responseType}
     * @see #queryMany(Object, Class, ProcessingContext)
     */
    default <R> CompletableFuture<List<R>> queryMany(Object query,
                                                     Class<R> responseType) {
        return queryMany(query, responseType, null);
    }

    /**
     * Sends given {@code query} with the given {@code metadata} in the provided {@code context} (if available) over the
     * {@link QueryBus}, expecting multiple responses in the form of {@code responseType} from a single source.
     * <p>
     * Execution may be asynchronous, depending on the {@code QueryBus} implementation.
     * <p>
     * The given {@code query} and {@code metadata} are wrapped as the payload of the {@link QueryMessage} that is
     * eventually posted on the {@code QueryBus}, unless the {@code query} already implements {@link Message}. In that
     * case, a {@code QueryMessage} is constructed from that message's payload and {@link Metadata}. The provided
     * {@code metadata} is attached afterward in this case.
     * <p>
     * Implementations must override this method to support attaching the given {@code metadata}; the default
     * implementation throws an {@link UnsupportedOperationException}.
     *
     * @param query        the {@code query} to be sent
     * @param responseType a {@code Class} describing the desired response type
     * @param metadata     the metadata to attach to the {@code query}
     * @param context      the processing context, if any, to dispatch the given {@code query} in
     * @param <R>          the generic type of the expected response(s)
     * @return a {@code CompletableFuture} containing a list of query responses of type {@code responseType}
     * @since 5.3.0
     */
    default <R> CompletableFuture<List<R>> queryMany(Object query,
                                                     Class<R> responseType,
                                                     Metadata metadata,
                                                     @Nullable ProcessingContext context) {
        throw new UnsupportedOperationException(
                "This QueryGateway does not support attaching metadata to a query."
        );
    }

    /**
     * Sends given {@code query} over the {@link QueryBus}, expecting a response as a
     * {@link org.reactivestreams.Publisher} of {@code responseType}.
     * <p>
     * The {@code query} is sent once the {@code Publisher} is subscribed to. The streaming query allows a client to
     * stream large result sets. Usage of this method requires <a href="https://projectreactor.io/">Project Reactor</a>
     * on the class path.
     * <p>
     * The given {@code query} is wrapped as the payload of the {@link QueryMessage} that is eventually posted on the
     * {@code QueryBus}, unless the {@code query} already implements {@link Message}. In that case, a
     * {@code QueryMessage} is constructed from that message's payload and
     * {@link Metadata}.
     * <p>
     * {@link org.reactivestreams.Publisher} is used for backwards compatibility reason, for clients that don't have
     * Project Reactor on class path. Check <a href="https://docs.axoniq.io/reference-guide/extensions/reactor">Reactor
     * Extension</a> for native Flux type and more. Use {@code Flux.from(publisher)} to convert to Flux stream.
     *
     * @param query        the {@code query} to be sent
     * @param responseType a {@link java.lang.Class} describing the desired response type
     * @param context      the processing context, if any, to dispatch the given {@code query} in
     * @param <R>          the generic type of the expected response(s)
     * @return a {@link org.reactivestreams.Publisher} streaming the results as dictated by the given
     * {@code responseType}
     */
    <R> Publisher<R> streamingQuery(Object query,
                                    Class<R> responseType,
                                    @Nullable ProcessingContext context);

    /**
     * Sends given {@code query} over the {@link QueryBus}, expecting a response as a
     * {@link org.reactivestreams.Publisher} of {@code responseType}.
     * <p>
     * The {@code query} is sent once the {@code Publisher} is subscribed to. The streaming query allows a client to
     * stream large result sets. Usage of this method requires <a href="https://projectreactor.io/">Project Reactor</a>
     * on the class path.
     * <p>
     * The given {@code query} is wrapped as the payload of the {@link QueryMessage} that is eventually posted on the
     * {@code QueryBus}, unless the {@code query} already implements {@link Message}. In that case, a
     * {@code QueryMessage} is constructed from that message's payload and
     * {@link Metadata}.
     * <p>
     * {@link org.reactivestreams.Publisher} is used for backwards compatibility reason, for clients that don't have
     * Project Reactor on class path. Check <a href="https://docs.axoniq.io/reference-guide/extensions/reactor">Reactor
     * Extension</a> for native Flux type and more. Use {@code Flux.from(publisher)} to convert to Flux stream.
     *
     * @param query        the {@code query} to be sent
     * @param responseType a {@link java.lang.Class} describing the desired response type
     * @param <R>          the generic type of the expected response(s)
     * @return a {@link org.reactivestreams.Publisher} streaming the results as dictated by the given
     * {@code responseType}
     * @see #streamingQuery(Object, Class, ProcessingContext)
     */
    default <R> Publisher<R> streamingQuery(Object query,
                                            Class<R> responseType) {
        return streamingQuery(query, responseType, null);
    }

    /**
     * Sends given {@code query} with the given {@code metadata} in the provided {@code context} (if available) over the
     * {@link QueryBus}, expecting a response as a {@link org.reactivestreams.Publisher} of {@code responseType}.
     * <p>
     * The {@code query} is sent once the {@code Publisher} is subscribed to. See
     * {@link #streamingQuery(Object, Class)} for the streaming semantics and Project Reactor requirements.
     * <p>
     * The given {@code query} and {@code metadata} are wrapped as the payload of the {@link QueryMessage} that is
     * eventually posted on the {@code QueryBus}, unless the {@code query} already implements {@link Message}. In that
     * case, a {@code QueryMessage} is constructed from that message's payload and {@link Metadata}. The provided
     * {@code metadata} is attached afterward in this case.
     * <p>
     * Implementations must override this method to support attaching the given {@code metadata}; the default
     * implementation throws an {@link UnsupportedOperationException}.
     *
     * @param query        the {@code query} to be sent
     * @param responseType a {@link java.lang.Class} describing the desired response type
     * @param metadata     the metadata to attach to the {@code query}
     * @param context      the processing context, if any, to dispatch the given {@code query} in
     * @param <R>          the generic type of the expected response(s)
     * @return a {@link org.reactivestreams.Publisher} streaming the results as dictated by the given
     * {@code responseType}
     * @since 5.3.0
     */
    default <R> Publisher<R> streamingQuery(Object query,
                                            Class<R> responseType,
                                            Metadata metadata,
                                            @Nullable ProcessingContext context) {
        throw new UnsupportedOperationException(
                "This QueryGateway does not support attaching metadata to a query."
        );
    }

    /**
     * Sends given {@code query} over the {@link QueryBus} as a subscription query, combining the initial result and
     * emitted update as a {@link Publisher} of the given {@code responseType}.
     * <p>
     * The {@code query} is sent once the {@code Publisher} is subscribed to. Furthermore, updates are received at the
     * moment the query is sent, and until it is cancelled by the caller or closed by the emitting side.
     * <p>
     * The given {@code query} is wrapped as the payload of the {@link QueryMessage} that is eventually posted on the
     * {@code QueryBus}, unless the {@code query} already implements {@link Message}. In that case, a
     * {@code QueryMessage} is constructed from that message's payload and
     * {@link Metadata}.
     * <p>
     * Note that any {@code null} results, on the initial result or the update, will be filtered out by the gateway. If
     * you require the {@code null} to be returned for the initial and update results, we suggest using the
     * {@code QueryBus} instead.
     * <p>
     * The returned Publisher must be subscribed to and consumed from before the buffer fills up. Once the buffer is
     * full, any attempt to add an update will complete the stream with an exception.
     *
     * @param query        the {@code query} to be sent
     * @param responseType the response type returned by this query as the initial result and the update
     * @param context      the processing context, if any, to dispatch the given {@code query} in
     * @param <R>          the type of all the responses
     * @return a {@link org.reactivestreams.Publisher} streaming the results as dictated by the given
     * {@code responseType}
     */
    default <R> Publisher<R> subscriptionQuery(Object query,
                                               Class<R> responseType,
                                               @Nullable ProcessingContext context) {
        return subscriptionQuery(query, responseType, context, Queues.SMALL_BUFFER_SIZE);
    }

    /**
     * Sends given {@code query} over the {@link QueryBus} as a subscription query, combining the initial result and
     * emitted update as a {@link Publisher} of the given {@code responseType}.
     * <p>
     * The {@code query} is sent once the {@code Publisher} is subscribed to. Furthermore, updates are received at the
     * moment the query is sent, and until it is cancelled by the caller or closed by the emitting side.
     * <p>
     * The given {@code query} is wrapped as the payload of the {@link QueryMessage} that is eventually posted on the
     * {@code QueryBus}, unless the {@code query} already implements {@link Message}. In that case, a
     * {@code QueryMessage} is constructed from that message's payload and
     * {@link Metadata}.
     * <p>
     * Note that any {@code null} results, on the initial result or the update, will be filtered out by the gateway. If
     * you require the {@code null} to be returned for the initial and update results, we suggest using the
     * {@code QueryBus} instead.
     * <p>
     * The returned Publisher must be subscribed to and consumed from before the buffer fills up. Once the buffer is
     * full, any attempt to add an update will complete the stream with an exception.
     *
     * @param query        the {@code query} to be sent
     * @param responseType the response type returned by this query as the initial result and the update
     * @param <R>          the type of all the responses
     * @return a {@link org.reactivestreams.Publisher} streaming the results as dictated by the given
     * {@code responseType}
     */
    default <R> Publisher<R> subscriptionQuery(Object query,
                                               Class<R> responseType) {
        return subscriptionQuery(query, responseType, (ProcessingContext) null);
    }

    /**
     * Sends given {@code query} over the {@link QueryBus} as a subscription query, combining the initial result and
     * emitted update as a {@link Publisher} of the given {@code responseType}.
     * <p>
     * The {@code query} is sent once the {@code Publisher} is subscribed to. Furthermore, updates are received at the
     * moment the query is sent, and until it is cancelled by the caller or closed by the emitting side.
     * <p>
     * The given {@code query} is wrapped as the payload of the {@link QueryMessage} that is eventually posted on the
     * {@code QueryBus}, unless the {@code query} already implements {@link Message}. In that case, a
     * {@code QueryMessage} is constructed from that message's payload and
     * {@link Metadata}.
     * <p>
     * Note that any {@code null} results, on the initial result or the update, will be filtered out by the gateway. If
     * you require the {@code null} to be returned for the initial and update results, we suggest using the
     * {@code QueryBus} instead.
     * <p>
     * The returned Publisher must be subscribed to and consumed from before the buffer fills up. Once the buffer is
     * full, any attempt to add an update will complete the stream with an exception.
     *
     * @param query            the {@code query} to be sent
     * @param responseType     the response type returned by this query as the initial result and the update
     * @param context          the processing context, if any, to dispatch the given {@code query} in
     * @param updateBufferSize the size of buffer which accumulates updates before a subscription to the {@code Flux} is
     *                         made
     * @param <R>              the type of all the responses
     * @return a {@link org.reactivestreams.Publisher} streaming the results as dictated by the given
     * {@code responseType}
     */
    <R> Publisher<R> subscriptionQuery(Object query,
                                       Class<R> responseType,
                                       @Nullable ProcessingContext context,
                                       int updateBufferSize);

    /**
     * Sends given {@code query} with the given {@code metadata} over the {@link QueryBus} as a subscription query,
     * combining the initial result and emitted update as a {@link Publisher} of the given {@code responseType}.
     * <p>
     * The {@code query} is sent once the {@code Publisher} is subscribed to. Furthermore, updates are received at the
     * moment the query is sent, and until it is cancelled by the caller or closed by the emitting side.
     * <p>
     * The given {@code query} and {@code metadata} are wrapped as the payload of the {@link QueryMessage} that is
     * eventually posted on the {@code QueryBus}, unless the {@code query} already implements {@link Message}. In that
     * case, a {@code QueryMessage} is constructed from that message's payload and {@link Metadata}. The provided
     * {@code metadata} is attached afterward in this case.
     * <p>
     * Implementations must override this method to support attaching the given {@code metadata}; the default
     * implementation throws an {@link UnsupportedOperationException}.
     * <p>
     * Note that any {@code null} results, on the initial result or the update, will be filtered out by the gateway. If
     * you require the {@code null} to be returned for the initial and update results, we suggest using the
     * {@code QueryBus} instead.
     * <p>
     * The returned Publisher must be subscribed to and consumed from before the buffer fills up. Once the buffer is
     * full, any attempt to add an update will complete the stream with an exception.
     *
     * @param query            the {@code query} to be sent
     * @param responseType     the response type returned by this query as the initial result and the update
     * @param metadata         the metadata to attach to the {@code query}
     * @param context          the processing context, if any, to dispatch the given {@code query} in
     * @param updateBufferSize the size of buffer which accumulates updates before a subscription to the {@code Flux} is
     *                         made
     * @param <R>              the type of all the responses
     * @return a {@link org.reactivestreams.Publisher} streaming the results as dictated by the given
     * {@code responseType}
     * @since 5.3.0
     */
    default <R> Publisher<R> subscriptionQuery(Object query,
                                               Class<R> responseType,
                                               Metadata metadata,
                                               @Nullable ProcessingContext context,
                                               int updateBufferSize) {
        throw new UnsupportedOperationException(
                "This QueryGateway does not support attaching metadata to a query."
        );
    }

    /**
     * Sends given {@code query} over the {@link QueryBus} and returns a {@link Publisher} supplying the initial update
     * followed by the update. The given {@code mapper} is used to map the {@link QueryResponseMessage} to the desired
     * object type. To distinguish between the initial result and the update, the given {@code mapper} can check
     * whether the given {@code responseMessage} is an instance of {@link SubscriptionQueryUpdateMessage}. In that case
     * the message is considered an update, otherwise it is considered the initial result.
     * <p>
     * The {@code query} is sent upon invocation of this method. Furthermore, updates are received at the moment the
     * query is sent, and until the subscription to the Publisher is canceled by the caller or closed by the emitting
     * side.
     * <p>
     * The given {@code query} is wrapped as the payload of the {@link QueryMessage} that is eventually posted on the
     * {@code QueryBus}, unless the {@code query} already implements {@link Message}. In that case, a
     * {@code QueryMessage} is constructed from that message's payload and
     * {@link Metadata}.
     * <p>
     * Note that any {@code null} results, on the initial result or the update, will be filtered out by the gateway. If
     * you require the {@code null} to be returned for the initial and update results, we suggest using the
     * {@code QueryBus} instead.
     * <p>
     * The returned Publisher must be subscribed to and consumed from before the buffer fills up. Once the buffer is
     * full, any attempt to add an update will complete the stream with an exception. To control the buffer size, use
     * {@link #subscriptionQuery(Object, Class, Function, ProcessingContext, int)}.
     *
     * @param query        the {@code query} to be sent
     * @param responseType the response type returned by this query as the initial result
     * @param mapper       a {@link Function} that maps the {@link QueryResponseMessage} to the desired response
     * @param context      the processing context, if any, to dispatch the given {@code query} in
     * @param <R>          the type payload to map the responses to
     * @return a {@link Publisher} which can be used to cancel receiving update
     * @see #subscriptionQuery(Object, Class, Function, ProcessingContext, int)
     */
    default <R> Publisher<R> subscriptionQuery(Object query,
                                               Class<R> responseType,
                                               Function<QueryResponseMessage, R> mapper,
                                               @Nullable ProcessingContext context) {
        return subscriptionQuery(query, responseType, mapper, context, Queues.SMALL_BUFFER_SIZE);
    }

    /**
     * Sends given {@code query} over the {@link QueryBus} and returns a {@link Publisher} supplying the initial update
     * followed by the update. The given {@code mapper} is used to map the {@link QueryResponseMessage} to the desired
     * object type. To distinguish between the initial result and the update, the given {@code mapper} can check
     * whether the given {@code responseMessage} is an instance of {@link SubscriptionQueryUpdateMessage}. In that case
     * the message is considered an update, otherwise it is considered the initial result.
     * <p>
     * The {@code query} is sent upon invocation of this method. Furthermore, updates are received at the moment the
     * query is sent, and until the subscription to the Publisher is canceled by the caller or closed by the emitting
     * side.
     * <p>
     * The given {@code query} is wrapped as the payload of the {@link QueryMessage} that is eventually posted on the
     * {@code QueryBus}, unless the {@code query} already implements {@link Message}. In that case, a
     * {@code QueryMessage} is constructed from that message's payload and
     * {@link Metadata}.
     * <p>
     * Note that any {@code null} results, on the initial result or the update, will be filtered out by the gateway. If
     * you require the {@code null} to be returned for the initial and update results, we suggest using the
     * {@code QueryBus} instead.
     * <p>
     * The returned Publisher must be subscribed to and consumed from before the buffer fills up. Once the buffer is
     * full, any attempt to add an update will complete the stream with an exception. To control the buffer size, use
     * {@link #subscriptionQuery(Object, Class, Function, ProcessingContext, int)}.
     *
     * @param query        the {@code query} to be sent
     * @param responseType the response type returned by this query as the initial result
     * @param mapper       a {@link Function} that maps the {@link QueryResponseMessage} to the desired response
     * @param <R>          the type payload to map the responses to
     * @return a {@link Publisher} which can be used to cancel receiving update
     * @see #subscriptionQuery(Object, Class, Function, ProcessingContext, int)
     */
    default <R> Publisher<R> subscriptionQuery(Object query,
                                               Class<R> responseType,
                                               Function<QueryResponseMessage, R> mapper) {
        return subscriptionQuery(query, responseType, mapper, null, Queues.SMALL_BUFFER_SIZE);
    }

    /**
     * Sends given {@code query} over the {@link QueryBus} and returns a {@link Publisher} supplying the initial update
     * followed by the update. The given {@code mapper} is used to map the {@link QueryResponseMessage} to the desired
     * object type. To distinguish between the initial result and the update, the given {@code mapper} can check
     * whether the given {@code responseMessage} is an instance of {@link SubscriptionQueryUpdateMessage}. In that case
     * the message is considered an update, otherwise it is considered the initial result.
     * <p>
     * The {@code query} is sent upon invocation of this method. Furthermore, updates are received at the moment the
     * query is sent, and until the subscription to the Publisher is canceled by the caller or closed by the emitting
     * side.
     * <p>
     * The given {@code query} is wrapped as the payload of the {@link QueryMessage} that is eventually posted on the
     * {@code QueryBus}, unless the {@code query} already implements {@link Message}. In that case, a
     * {@code QueryMessage} is constructed from that message's payload and
     * {@link Metadata}.
     * <p>
     * Note that any {@code null} results, on the initial result or the update, will be filtered out by the gateway. If
     * you require the {@code null} to be returned for the initial and update results, we suggest using the
     * {@code QueryBus} instead.
     *
     * @param query            the {@code query} to be sent
     * @param responseType     the response type returned by this query as the initial result
     * @param updateBufferSize the size of the buffer which accumulates updates to be processed
     * @param <R>              the type payload to map the responses to
     * @return a {@link Publisher} which can be used to cancel receiving update
     * @see #subscriptionQuery(Object, Class, Function, ProcessingContext, int)
     */
    default <R> Publisher<R> subscriptionQuery(Object query,
                                               Class<R> responseType,
                                               int updateBufferSize) {
        return subscriptionQuery(query, responseType, (ProcessingContext) null, updateBufferSize);
    }

    /**
     * Sends given {@code query} over the {@link QueryBus} and returns a {@link Publisher} supplying the initial update
     * followed by the update. The given {@code mapper} is used to map the {@link QueryResponseMessage} to the desired
     * object type. To distinguish between the initial result and the update, the given {@code mapper} can check
     * whether the given {@code responseMessage} is an instance of {@link SubscriptionQueryUpdateMessage}. In that case
     * the message is considered an update, otherwise it is considered the initial result.
     * <p>
     * The {@code query} is sent upon invocation of this method. Furthermore, updates are received at the moment the
     * query is sent, and until the subscription to the Publisher is canceled by the caller or closed by the emitting
     * side.
     * <p>
     * The given {@code query} is wrapped as the payload of the {@link QueryMessage} that is eventually posted on the
     * {@code QueryBus}, unless the {@code query} already implements {@link Message}. In that case, a
     * {@code QueryMessage} is constructed from that message's payload and
     * {@link Metadata}.
     * <p>
     * Note that any {@code null} results, on the initial result or the update, will be filtered out by the gateway. If
     * you require the {@code null} to be returned for the initial and update results, we suggest using the
     * {@code QueryBus} instead.
     *
     * @param query            the {@code query} to be sent
     * @param responseType     the response type returned by this query as the initial result
     * @param mapper           a {@link Function} that maps the {@link QueryResponseMessage} to the desired response;
     *                         messages for which the mapper returns a {@code null} value are filtered out
     * @param context          the processing context, if any, to dispatch the given {@code query} in
     * @param updateBufferSize the size of the buffer which accumulates updates to be processed
     * @param <R>              the type payload to map the responses to
     * @return a {@link Publisher} which can be used to cancel receiving update
     * @see QueryBus#subscriptionQuery(QueryMessage, ProcessingContext, int)
     */
    <R> Publisher<R> subscriptionQuery(Object query,
                                       Class<R> responseType,
                                       Function<QueryResponseMessage, R> mapper,
                                       @Nullable ProcessingContext context,
                                       int updateBufferSize);

    /**
     * Sends given {@code query} over the {@link QueryBus} and returns a {@link Publisher} supplying the initial update
     * followed by the update. The given {@code mapper} is used to map the {@link QueryResponseMessage} to the desired
     * object type. To distinguish between the initial result and the update, the given {@code mapper} can check
     * whether the given {@code responseMessage} is an instance of {@link SubscriptionQueryUpdateMessage}. In that case
     * the message is considered an update, otherwise it is considered the initial result.
     * <p>
     * The {@code query} is sent upon invocation of this method. Furthermore, updates are received at the moment the
     * query is sent, and until the subscription to the Publisher is canceled by the caller or closed by the emitting
     * side.
     * <p>
     * The given {@code query} is wrapped as the payload of the {@link QueryMessage} that is eventually posted on the
     * {@code QueryBus}, unless the {@code query} already implements {@link Message}. In that case, a
     * {@code QueryMessage} is constructed from that message's payload and
     * {@link Metadata}.
     * <p>
     * Note that any {@code null} results, on the initial result or the update, will be filtered out by the gateway. If
     * you require the {@code null} to be returned for the initial and update results, we suggest using the
     * {@code QueryBus} instead.
     *
     * @param query            the {@code query} to be sent
     * @param responseType     the response type returned by this query as the initial result
     * @param mapper           a {@link Function} that maps the {@link QueryResponseMessage} to the desired response;
     *                         messages for which the mapper returns a {@code null} value are filtered out
     * @param updateBufferSize the size of the buffer which accumulates updates to be processed
     * @param <R>              the type payload to map the responses to
     * @return a {@link Publisher} which can be used to cancel receiving update
     * @see #subscriptionQuery(Object, Class, Function, ProcessingContext, int)
     */
    default <R> Publisher<R> subscriptionQuery(Object query,
                                               Class<R> responseType,
                                               Function<QueryResponseMessage, R> mapper,
                                               int updateBufferSize) {
        return subscriptionQuery(query, responseType, mapper, null, updateBufferSize);
    }
}
