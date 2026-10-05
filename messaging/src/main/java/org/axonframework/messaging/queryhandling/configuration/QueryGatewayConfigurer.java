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
package org.axonframework.messaging.queryhandling.configuration;

import org.axonframework.common.annotation.Internal;
import org.axonframework.common.configuration.ComponentDefinition;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.lifecycle.Phase;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.core.conversion.MessageConverter;
import org.axonframework.messaging.queryhandling.QueryBus;
import org.axonframework.messaging.queryhandling.QueryPriorityCalculator;
import org.axonframework.messaging.queryhandling.QueryShutdownManager;
import org.axonframework.messaging.queryhandling.gateway.DefaultQueryGateway;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.axonframework.messaging.queryhandling.gateway.ShutdownTrackingQueryGateway;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Factory for creating a named {@link QueryGateway} component with optional shutdown-tracking decorators.
 * <p>
 * Each call to
 * {@link MessagingConfigurer#registerQueryGateway(String, java.util.function.Consumer)
 * MessagingConfigurer.registerQueryGateway(String, Consumer)} produces one independently configured
 * {@code QueryGateway} instance registered under the given name. The gateway starts from a bare
 * {@link DefaultQueryGateway} that shares infrastructure with the main configuration (same {@link QueryBus},
 * {@link MessageTypeResolver}, {@link QueryPriorityCalculator}, and {@link MessageConverter}), and is optionally
 * wrapped in a {@link ShutdownTrackingQueryGateway} when shutdown cancellation is configured.
 * <p>
 * This configurer is not intended to be instantiated directly. Access it via
 * {@link MessagingConfigurer#registerQueryGateway(String, java.util.function.Consumer)}:
 * <pre>{@code
 * MessagingConfigurer.create()
 *     .registerQueryGateway("reporting", g -> g
 *         .cancellingSubscriptionQueryOnShutdown(Duration.ofSeconds(10))
 *         .cancellingStreamingQueryOnShutdown(Duration.ofSeconds(5))
 *     );
 * }</pre>
 *
 * @author Allard Buijze
 * @since 5.4.0
 */
public class QueryGatewayConfigurer {

    private final String name;
    private @Nullable QueryShutdownManager subscriptionQueryShutdownManager;
    private @Nullable QueryShutdownManager streamingQueryShutdownManager;

    /**
     * Creates a new {@code QueryGatewayConfigurer} that will register the gateway under the given {@code name}.
     * <p>
     * This constructor is intended for use by {@link MessagingConfigurer} only. Users should access this configurer via
     * {@link MessagingConfigurer#registerQueryGateway(String, java.util.function.Consumer)}.
     *
     * @param name the name under which the produced {@link QueryGateway} is registered
     */
    @Internal
    public QueryGatewayConfigurer(String name) {
        this.name = Objects.requireNonNull(name, "name must not be null");
    }

    /**
     * Accepts the default configuration for this gateway without applying any decorators.
     * <p>
     * This method is a no-op provided for readability when registering a plain
     * {@link org.axonframework.messaging.queryhandling.gateway.DefaultQueryGateway} with no shutdown tracking:
     * <pre>{@code
     * MessagingConfigurer.create()
     *     .registerQueryGateway("reporting", QueryGatewayConfigurer::withDefaults);
     * }</pre>
     *
     * @return this configurer, unchanged
     */
    public QueryGatewayConfigurer withDefaults() {
        return this;
    }

    /**
     * Configures all subscription queries dispatched through this gateway to be cancelled immediately when the
     * application shuts down.
     * <p>
     * With this option set, callers do not need to wrap subscription query results with
     * {@link QueryShutdownManager#track(org.reactivestreams.Publisher)} manually; the gateway handles tracking for
     * every dispatched subscription query automatically.
     * <p>
     * Cancellation happens at {@link Phase#OUTBOUND_QUERY_CONNECTORS} during application shutdown.
     *
     * @return this configurer, for fluent chaining
     */
    public QueryGatewayConfigurer cancellingSubscriptionQueryOnShutdown() {
        return cancellingSubscriptionQueryOnShutdown(QueryShutdownManager.closeImmediately());
    }

    /**
     * Configures all subscription queries dispatched through this gateway to be cancelled when the application shuts
     * down, waiting up to the given {@code gracePeriod} for them to complete naturally first.
     * <p>
     * With this option set, callers do not need to wrap subscription query results with
     * {@link QueryShutdownManager#track(org.reactivestreams.Publisher)} manually; the gateway handles tracking for
     * every dispatched subscription query automatically.
     * <p>
     * Cancellation happens at {@link Phase#OUTBOUND_QUERY_CONNECTORS} during application shutdown.
     *
     * @param gracePeriod the maximum time to wait for natural completion before force-closing
     * @return this configurer, for fluent chaining
     */
    public QueryGatewayConfigurer cancellingSubscriptionQueryOnShutdown(Duration gracePeriod) {
        Objects.requireNonNull(gracePeriod, "gracePeriod must not be null");
        return cancellingSubscriptionQueryOnShutdown(QueryShutdownManager.withGracePeriod(gracePeriod));
    }

    /**
     * Configures all subscription queries dispatched through this gateway to be tracked by the given
     * {@code shutdownManager}, cancelling them according to that manager's policy when the application shuts down.
     * <p>
     * Use this overload to share a single {@link QueryShutdownManager} between several gateways, or when the manager is
     * also used for call-site tracking through {@link QueryShutdownManager#track(org.reactivestreams.Publisher)}.
     * <p>
     * The given manager's {@link QueryShutdownManager#shutdown()} is called at {@link Phase#OUTBOUND_QUERY_CONNECTORS}
     * during application shutdown.
     *
     * @param shutdownManager the manager to track dispatched subscription queries with
     * @return this configurer, for fluent chaining
     */
    public QueryGatewayConfigurer cancellingSubscriptionQueryOnShutdown(QueryShutdownManager shutdownManager) {
        this.subscriptionQueryShutdownManager =
                Objects.requireNonNull(shutdownManager, "shutdownManager must not be null");
        return this;
    }

    /**
     * Configures all streaming queries dispatched through this gateway to be cancelled immediately when the application
     * shuts down.
     * <p>
     * With this option set, callers do not need to wrap streaming query results with
     * {@link QueryShutdownManager#track(org.reactivestreams.Publisher)} manually; the gateway handles tracking for
     * every dispatched streaming query automatically.
     * <p>
     * Cancellation happens at {@link Phase#OUTBOUND_QUERY_CONNECTORS} during application shutdown.
     *
     * @return this configurer, for fluent chaining
     */
    public QueryGatewayConfigurer cancellingStreamingQueryOnShutdown() {
        return cancellingStreamingQueryOnShutdown(QueryShutdownManager.closeImmediately());
    }

    /**
     * Configures all streaming queries dispatched through this gateway to be cancelled when the application shuts down,
     * waiting up to the given {@code gracePeriod} for them to complete naturally first.
     * <p>
     * A grace period is typically appropriate here, as streaming queries are finite by nature and expected to complete
     * shortly after shutdown begins.
     * <p>
     * With this option set, callers do not need to wrap streaming query results with
     * {@link QueryShutdownManager#track(org.reactivestreams.Publisher)} manually; the gateway handles tracking for
     * every dispatched streaming query automatically.
     * <p>
     * Cancellation happens at {@link Phase#OUTBOUND_QUERY_CONNECTORS} during application shutdown.
     *
     * @param gracePeriod the maximum time to wait for natural completion before force-closing
     * @return this configurer, for fluent chaining
     */
    public QueryGatewayConfigurer cancellingStreamingQueryOnShutdown(Duration gracePeriod) {
        Objects.requireNonNull(gracePeriod, "gracePeriod must not be null");
        return cancellingStreamingQueryOnShutdown(QueryShutdownManager.withGracePeriod(gracePeriod));
    }

    /**
     * Configures all streaming queries dispatched through this gateway to be tracked by the given
     * {@code shutdownManager}, cancelling them according to that manager's policy when the application shuts down.
     * <p>
     * Use this overload to share a single {@link QueryShutdownManager} between several gateways, or when the manager is
     * also used for call-site tracking through {@link QueryShutdownManager#track(org.reactivestreams.Publisher)}.
     * <p>
     * The given manager's {@link QueryShutdownManager#shutdown()} is called at {@link Phase#OUTBOUND_QUERY_CONNECTORS}
     * during application shutdown.
     *
     * @param shutdownManager the manager to track dispatched streaming queries with
     * @return this configurer, for fluent chaining
     */
    public QueryGatewayConfigurer cancellingStreamingQueryOnShutdown(QueryShutdownManager shutdownManager) {
        this.streamingQueryShutdownManager =
                Objects.requireNonNull(shutdownManager, "shutdownManager must not be null");
        return this;
    }

    /**
     * Builds a {@link ComponentDefinition} for a {@link QueryGateway} registered under this configurer's name.
     * <p>
     * The produced definition constructs a {@link DefaultQueryGateway} from shared infrastructure and wraps it in a
     * {@link ShutdownTrackingQueryGateway} if shutdown cancellation was configured. Otherwise, a
     * {@code DefaultQueryGatweay} is returned as is. The corresponding managers are then shut down at
     * {@link Phase#OUTBOUND_QUERY_CONNECTORS} during application shutdown.
     * <p>
     * This method is called by {@link MessagingConfigurer} and is not intended to be called directly.
     *
     * @return a {@link ComponentDefinition} for the named {@link QueryGateway}
     */
    public ComponentDefinition<QueryGateway> buildDefinition() {
        QueryShutdownManager subscriptionManager = subscriptionQueryShutdownManager;
        QueryShutdownManager streamingManager = streamingQueryShutdownManager;

        return subscriptionManager == null && streamingManager == null
                ? ComponentDefinition.ofTypeAndName(QueryGateway.class, name)
                                     .withBuilder(QueryGatewayConfigurer::buildDefaultGateway)
                : ComponentDefinition.ofTypeAndName(QueryGateway.class, name)
                                     .withBuilder(config -> {
                                         DefaultQueryGateway defaultQueryGateway = buildDefaultGateway(config);
                                         return ShutdownTrackingQueryGateway.build(
                                                 defaultQueryGateway, subscriptionManager, streamingManager
                                         );
                                     })
                                     .onShutdown(
                                             Phase.OUTBOUND_QUERY_CONNECTORS,
                                             (cfg, gateway) -> {
                                                 List<CompletableFuture<Void>> futures = new ArrayList<>(2);
                                                 if (subscriptionManager != null) {
                                                     futures.add(subscriptionManager.shutdown());
                                                 }
                                                 if (streamingManager != null) {
                                                     futures.add(streamingManager.shutdown());
                                                 }
                                                 return CompletableFuture.allOf(
                                                         futures.toArray(new CompletableFuture[0])
                                                 );
                                             }
                                     );
    }

    private static DefaultQueryGateway buildDefaultGateway(Configuration config) {
        return new DefaultQueryGateway(
                config.getComponent(QueryBus.class),
                config.getComponent(MessageTypeResolver.class),
                config.getComponent(QueryPriorityCalculator.class),
                config.getComponent(MessageConverter.class)
        );
    }
}
