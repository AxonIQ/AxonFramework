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

import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.queryhandling.QueryResponseMessage;
import org.axonframework.messaging.queryhandling.QueryShutdownManager;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * A {@link QueryGateway} decorator that automatically registers subscription queries and streaming
 * queries with a {@link QueryShutdownManager}, ensuring they are cancelled during application
 * shutdown.
 * <p>
 * This decorator wraps any {@link QueryGateway} implementation and transparently intercepts
 * {@link #subscriptionQuery} and {@link #streamingQuery} calls, passing their results through the
 * respective shutdown managers. All other gateway operations are delegated unchanged.
 * <p>
 * It is the gateway layer of a layered API: configure it once on the gateway and every dispatched
 * subscription or streaming query is tracked automatically, with no changes needed at call sites.
 * <p>
 * In a plain Java application, the recommended way to register this decorator is through
 * {@link org.axonframework.messaging.core.configuration.MessagingConfigurer#queryGateway(String, java.util.function.Consumer)},
 * which creates a named {@link QueryGateway} with shutdown tracking applied:
 * <pre>{@code
 * MessagingConfigurer.create()
 *     .queryGateway("reporting", g -> g
 *         .cancellingSubscriptionQueryOnShutdown(c -> c.closeImmediately())
 *     );
 * }</pre>
 * In a Spring Boot application, declare a named {@code @Bean} of type {@link QueryGateway} instead.
 * Axon's Spring integration bridges it into the component registry automatically:
 * <pre>{@code
 * @Bean
 * public QueryGateway sseGateway(QueryBus queryBus, MessageTypeResolver resolver,
 *                                QueryPriorityCalculator calculator, MessageConverter converter,
 *                                QueryShutdownManager shutdownManager) {
 *     DefaultQueryGateway base = new DefaultQueryGateway(queryBus, resolver, calculator, converter);
 *     return new ShutdownTrackingQueryGateway(base, shutdownManager, shutdownManager);
 * }
 * }</pre>
 * When call-site tracking is preferred over gateway-level tracking, wrap individual results with
 * {@link org.axonframework.messaging.queryhandling.QueryShutdownManager#track} instead.
 *
 * @author Allard Buijze
 * @since 5.2.0
 */
public class ShutdownTrackingQueryGateway implements QueryGateway {

    private final QueryGateway delegate;
    private final @Nullable QueryShutdownManager subscriptionQueryShutdownManager;
    private final @Nullable QueryShutdownManager streamingQueryShutdownManager;

    /**
     * Creates a new {@code ShutdownTrackingQueryGateway} that wraps the given {@code delegate} and
     * tracks query streams with the given shutdown managers.
     *
     * @param delegate                        the {@link QueryGateway} to delegate all operations to
     * @param subscriptionQueryShutdownManager the manager to track subscription query streams with,
     *                                        or {@code null} to skip subscription query tracking
     * @param streamingQueryShutdownManager   the manager to track streaming query streams with,
     *                                        or {@code null} to skip streaming query tracking
     */
    public ShutdownTrackingQueryGateway(QueryGateway delegate,
                                        @Nullable QueryShutdownManager subscriptionQueryShutdownManager,
                                        @Nullable QueryShutdownManager streamingQueryShutdownManager) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.subscriptionQueryShutdownManager = subscriptionQueryShutdownManager;
        this.streamingQueryShutdownManager = streamingQueryShutdownManager;
    }

    @Override
    public <R> CompletableFuture<R> query(Object query,
                                          Class<R> responseType,
                                          @Nullable ProcessingContext context) {
        return delegate.query(query, responseType, context);
    }

    @Override
    public <R> CompletableFuture<List<R>> queryMany(Object query,
                                                    Class<R> responseType,
                                                    @Nullable ProcessingContext context) {
        return delegate.queryMany(query, responseType, context);
    }

    @Override
    public <R> Publisher<R> streamingQuery(Object query,
                                           Class<R> responseType,
                                           @Nullable ProcessingContext context) {
        Publisher<R> result = delegate.streamingQuery(query, responseType, context);
        return streamingQueryShutdownManager != null
               ? streamingQueryShutdownManager.track(result)
               : result;
    }

    @Override
    public <R> Publisher<R> subscriptionQuery(Object query,
                                              Class<R> responseType,
                                              @Nullable ProcessingContext context,
                                              int updateBufferSize) {
        Publisher<R> result = delegate.subscriptionQuery(query, responseType, context, updateBufferSize);
        return subscriptionQueryShutdownManager != null
               ? subscriptionQueryShutdownManager.track(result)
               : result;
    }

    @Override
    public <R> Publisher<R> subscriptionQuery(Object query,
                                              Class<R> responseType,
                                              Function<QueryResponseMessage, R> mapper,
                                              @Nullable ProcessingContext context,
                                              int updateBufferSize) {
        Publisher<R> result = delegate.subscriptionQuery(query, responseType, mapper, context, updateBufferSize);
        return subscriptionQueryShutdownManager != null
               ? subscriptionQueryShutdownManager.track(result)
               : result;
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeWrapperOf(delegate);
        if (subscriptionQueryShutdownManager != null) {
            descriptor.describeProperty("subscriptionQueryShutdownManager", subscriptionQueryShutdownManager);
        }
        if (streamingQueryShutdownManager != null) {
            descriptor.describeProperty("streamingQueryShutdownManager", streamingQueryShutdownManager);
        }
    }
}
