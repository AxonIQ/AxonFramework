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

import org.axonframework.common.configuration.ComponentDefinition;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Factory for creating a named {@link QueryGateway} component with optional shutdown-tracking
 * decorators.
 * <p>
 * Each call to
 * {@link MessagingConfigurer#queryGateway(String, java.util.function.Consumer) MessagingConfigurer.queryGateway(String, Consumer)}
 * produces one independently configured {@code QueryGateway} instance registered under the given
 * name. The gateway starts from a bare {@link DefaultQueryGateway} that shares infrastructure
 * with the main configuration (same {@link QueryBus}, {@link MessageTypeResolver},
 * {@link QueryPriorityCalculator}, and {@link MessageConverter}), and is optionally wrapped in a
 * {@link ShutdownTrackingQueryGateway} when shutdown specs are configured.
 * <p>
 * This configurer is not intended to be instantiated directly. Access it via
 * {@link MessagingConfigurer#queryGateway(String, java.util.function.Consumer)}:
 * <pre>{@code
 * MessagingConfigurer.create()
 *     .queryGateway("reporting", g -> g
 *         .cancellingSubscriptionQueryOnShutdown(c -> c.withGracePeriod(Duration.ofSeconds(10)))
 *         .cancellingStreamingQueryOnShutdown(c -> c.withGracePeriod(Duration.ofSeconds(5)))
 *     );
 * }</pre>
 *
 * @author Allard Buijze
 * @since 5.2.0
 */
public class QueryGatewayConfigurer {

    private final String name;
    private @Nullable Function<QueryShutdownManager.Spec, QueryShutdownManager> subscriptionQueryShutdownSpec;
    private @Nullable Function<QueryShutdownManager.Spec, QueryShutdownManager> streamingQueryShutdownSpec;

    /**
     * Creates a new {@code QueryGatewayConfigurer} that will register the gateway under the given
     * {@code name}.
     * <p>
     * This constructor is intended for use by {@link MessagingConfigurer} only. Users should access
     * this configurer via
     * {@link MessagingConfigurer#queryGateway(String, java.util.function.Consumer)}.
     *
     * @param name the name under which the produced {@link QueryGateway} is registered
     */
    public QueryGatewayConfigurer(String name) {
        this.name = Objects.requireNonNull(name, "name must not be null");
    }

    /**
     * Accepts the default configuration for this gateway without applying any decorators.
     * <p>
     * This method is a no-op provided for readability when registering a plain
     * {@link org.axonframework.messaging.queryhandling.gateway.DefaultQueryGateway} with no
     * shutdown tracking:
     * <pre>{@code
     * MessagingConfigurer.create()
     *     .queryGateway("reporting", QueryGatewayConfigurer::withDefaults);
     * }</pre>
     *
     * @return this configurer, unchanged
     */
    public QueryGatewayConfigurer withDefaults() {
        return this;
    }

    /**
     * Configures all subscription queries dispatched through this gateway to be automatically
     * cancelled when the application shuts down.
     * <p>
     * The {@code specFn} receives a {@link QueryShutdownManager.Spec} and must return the
     * configured manager. With this option set, callers do not need to wrap subscription query
     * results with {@link QueryShutdownManager#track} manually; the gateway handles tracking
     * for every dispatched subscription query automatically.
     * <p>
     * The created manager's {@link QueryShutdownManager#shutdown()} is called at
     * {@link Phase#OUTBOUND_QUERY_CONNECTORS} during application shutdown.
     *
     * @param specFn a function that receives a {@link QueryShutdownManager.Spec} and returns
     *               the configured manager
     * @return this configurer, for fluent chaining
     */
    public QueryGatewayConfigurer cancellingSubscriptionQueryOnShutdown(
            Function<QueryShutdownManager.Spec, QueryShutdownManager> specFn) {
        this.subscriptionQueryShutdownSpec = Objects.requireNonNull(specFn, "specFn must not be null");
        return this;
    }

    /**
     * Configures all streaming queries dispatched through this gateway to be automatically
     * cancelled when the application shuts down.
     * <p>
     * The {@code specFn} receives a {@link QueryShutdownManager.Spec} and must return the
     * configured manager. With this option set, callers do not need to wrap streaming query
     * results with {@link QueryShutdownManager#track} manually; the gateway handles tracking
     * for every dispatched streaming query automatically.
     * <p>
     * A grace-period policy is typically appropriate here, as streaming queries are finite by
     * nature and expected to complete shortly after shutdown begins.
     * <p>
     * The created manager's {@link QueryShutdownManager#shutdown()} is called at
     * {@link Phase#OUTBOUND_QUERY_CONNECTORS} during application shutdown.
     *
     * @param specFn a function that receives a {@link QueryShutdownManager.Spec} and returns
     *               the configured manager
     * @return this configurer, for fluent chaining
     */
    public QueryGatewayConfigurer cancellingStreamingQueryOnShutdown(
            Function<QueryShutdownManager.Spec, QueryShutdownManager> specFn) {
        this.streamingQueryShutdownSpec = Objects.requireNonNull(specFn, "specFn must not be null");
        return this;
    }

    /**
     * Builds a {@link ComponentDefinition} for a {@link QueryGateway} registered under this
     * configurer's name.
     * <p>
     * The produced definition constructs a {@link DefaultQueryGateway} from shared infrastructure
     * and wraps it in a {@link ShutdownTrackingQueryGateway} if any shutdown spec was configured.
     * When shutdown specs are present, the corresponding managers are shut down at
     * {@link Phase#OUTBOUND_QUERY_CONNECTORS} during application shutdown.
     * <p>
     * This method is called by {@link MessagingConfigurer} and is not intended to be called
     * directly.
     *
     * @return a {@link ComponentDefinition} for the named {@link QueryGateway}
     */
    public ComponentDefinition<QueryGateway> buildDefinition() {
        QueryShutdownManager subscriptionManager = subscriptionQueryShutdownSpec != null
                ? subscriptionQueryShutdownSpec.apply(new QueryShutdownManager.Spec()) : null;
        QueryShutdownManager streamingManager = streamingQueryShutdownSpec != null
                ? streamingQueryShutdownSpec.apply(new QueryShutdownManager.Spec()) : null;

        ComponentDefinition<QueryGateway> definition =
                ComponentDefinition.ofTypeAndName(QueryGateway.class, name)
                                   .withBuilder(cfg -> {
                                       DefaultQueryGateway base = new DefaultQueryGateway(
                                               cfg.getComponent(QueryBus.class),
                                               cfg.getComponent(MessageTypeResolver.class),
                                               cfg.getComponent(QueryPriorityCalculator.class),
                                               cfg.getComponent(MessageConverter.class)
                                       );
                                       if (subscriptionManager != null || streamingManager != null) {
                                           return new ShutdownTrackingQueryGateway(base,
                                                                                   subscriptionManager,
                                                                                   streamingManager);
                                       }
                                       return base;
                                   });

        if (subscriptionManager != null || streamingManager != null) {
            definition = definition.onShutdown(Phase.OUTBOUND_QUERY_CONNECTORS, (cfg, gateway) -> {
                List<CompletableFuture<Void>> futures = new ArrayList<>(2);
                if (subscriptionManager != null) {
                    futures.add(subscriptionManager.shutdown());
                }
                if (streamingManager != null) {
                    futures.add(streamingManager.shutdown());
                }
                return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
            });
        }

        return definition;
    }
}
