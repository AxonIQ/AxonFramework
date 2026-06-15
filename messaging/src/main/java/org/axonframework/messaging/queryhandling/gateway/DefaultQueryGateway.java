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
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.core.FluxUtils;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.conversion.MessageConverter;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.queryhandling.*;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.util.concurrent.Queues;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Default implementation of the {@link QueryGateway} interface.
 *
 * @author Allard Buijze
 * @author Marc Gathier
 * @author Steven van Beelen
 * @since 3.1.0
 */
public class DefaultQueryGateway implements QueryGateway {

    private final QueryBus queryBus;
    private final MessageTypeResolver messageTypeResolver;
    private final QueryPriorityCalculator priorityCalculator;
    private final MessageConverter converter;

    /**
     * Initialize the {@code DefaultQueryGateway} to send queries through the given {@code queryBus}.
     * <p>
     * The {@link QualifiedName names} for {@link QueryMessage QueryMessages} are resolved
     * through the given {@code nameResolver}.
     *
     * @param queryBus            The {@link QueryBus} to send queries on.
     * @param messageTypeResolver The {@link MessageTypeResolver} resolving the
     *                            {@link QualifiedName names} for
     *                            {@link QueryMessage QueryMessages} being dispatched on the {@code queryBus}.
     * @param priorityCalculator  The {@link QueryPriorityCalculator} determining the priority of queries.
     * @param converter           The converter to use for converting the result of query handling.
     */
    public DefaultQueryGateway(QueryBus queryBus,
                               MessageTypeResolver messageTypeResolver,
                               QueryPriorityCalculator priorityCalculator,
                               MessageConverter converter) {
        this.queryBus = Objects.requireNonNull(queryBus, "The QueryBus must not be null.");
        this.messageTypeResolver = Objects.requireNonNull(messageTypeResolver,
                                                          "The MessageTypeResolver must not be null.");
        this.priorityCalculator = Objects.requireNonNull(priorityCalculator,
                                                         "The QueryPriorityCalculator must not be null.");
        this.converter = Objects.requireNonNull(converter, "The MessageConverter must not be null.");
    }

    @Override
    public <R> CompletableFuture<R> query(Object query,
                                          Class<R> responseType,
                                          @Nullable ProcessingContext context) {
        QueryMessage queryMessage = asQueryMessage(query);
        MessageStream<QueryResponseMessage> resultStream = queryBus.query(queryMessage, context);
        CompletableFuture<R> resultFuture =
                resultStream.first()
                            .asCompletableFuture()
                            .thenApply(entry -> {
                                if (entry == null) {
                                    return null;
                                }
                                return entry.message().payloadAs(responseType, converter);
                            });
        // We cannot chain the whenComplete call, as otherwise CompletableFuture#cancel is not propagated to the lambda.
        resultFuture.whenComplete((r, e) -> {
            if (!resultStream.isCompleted()) {
                resultStream.close();
            }
        });
        return resultFuture;
    }

    @Override
    public <R> CompletableFuture<List<R>> queryMany(Object query,
                                                    Class<R> responseType,
                                                    @Nullable ProcessingContext context) {
        QueryMessage queryMessage = asQueryMessage(query);
        MessageStream<QueryResponseMessage> resultStream = queryBus.query(queryMessage, context);
        CompletableFuture<List<R>> resultFuture =
                resultStream.collect(ArrayList::new, (list, message) -> list.add(message.payloadAs(responseType, converter)));
        // We cannot chain the whenComplete call, as otherwise CompletableFuture#cancel is not propagated to the lambda.
        resultFuture.whenComplete((r, e) -> {
            if (!resultStream.isCompleted()) {
                resultStream.close();
            }
        });
        return resultFuture;
    }

    @Override
    public <R> Publisher<R> streamingQuery(Object query,
                                           Class<R> responseType,
                                           @Nullable ProcessingContext context) {
        return Mono.fromSupplier(() -> asQueryMessage(query))
                   .flatMapMany(queryMessage -> FluxUtils.of(queryBus.query(queryMessage, context)))
                   .map(MessageStream.Entry::message)
                   .mapNotNull(m -> m.payloadAs(responseType, converter));
    }

    @Override
    public <R> Publisher<R> subscriptionQuery(Object query, Class<R> responseType) {
        return subscriptionQuery(query, responseType, m -> m.payloadAs(responseType, converter), null, Queues.SMALL_BUFFER_SIZE);
    }

    @Override
    public <R> Publisher<R> subscriptionQuery(Object query,
                                              Class<R> responseType,
                                              @Nullable ProcessingContext context,
                                              int updateBufferSize) {
        return subscriptionQuery(query,
                                 responseType,
                                 m -> m.payloadAs(responseType, converter),
                                 context,
                                 updateBufferSize);
    }

    @Override
    public <R> Publisher<R> subscriptionQuery(Object query,
                                              Class<R> responseType,
                                              int updateBufferSize) {
        return subscriptionQuery(query,
                                 responseType,
                                 m -> m.payloadAs(responseType, converter),
                                 null,
                                 updateBufferSize);
    }

    @Override
    public <T> Publisher<T> subscriptionQuery(Object query,
                                              Class<T> responseType,
                                              Function<QueryResponseMessage, T> mapper,
                                              @Nullable ProcessingContext context,
                                              int updateBufferSize) {
        QueryMessage queryMessage = asQueryMessage(query);
        MessageStream<QueryResponseMessage> response = queryBus.subscriptionQuery(queryMessage,
                                                                                  context,
                                                                                  updateBufferSize);
        return FluxUtils.of(response)
                        .mapNotNull(m -> mapper.apply(m.message()))
                        .doOnCancel(response::close)
                        .doOnError((e) -> response.close());
    }

    private QueryMessage asQueryMessage(Object query) {
        if (query instanceof QueryMessage queryMessage) {
            return queryMessage;
        }
        return query instanceof Message message
                ? new GenericQueryMessage(message)
                : new GenericQueryMessage(resolveTypeFor(query), query);
    }

    private MessageType resolveTypeFor(Object payload) {
        return messageTypeResolver.resolveOrThrow(payload);
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeProperty("queryBus", queryBus);
        descriptor.describeProperty("messageTypeResolver", messageTypeResolver);
        descriptor.describeProperty("priorityCalculator", priorityCalculator);
    }
}
